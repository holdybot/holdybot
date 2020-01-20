(ns parky.routes.home
  (:require
    [buddy.sign.jwt :as jwt]
    [buddy.core.hash :as hash]
    [buddy.core.codecs :refer [bytes->hex]]
    [parky.layout :as layout]
    [parky.layout :refer [*identity*]]
    [parky.db.core :as db]
    [parky.config :refer [env]]
    [clojure.java.io :as io]
    [parky.middleware :as middleware]
    [parky.computation :as computation]
    [parky.email :as email]
    [clj-http.client :as client]
    [compact-uuids.core :as uuid]
    [java-time :as jt]
    [ring.util.http-response :as response]
    [ring.middleware.http-response :refer [wrap-http-response]]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json]
    [conman.core :as conman]
    [clojure.tools.reader.edn :as edn]
    [schema.core :as s]
    [expiring-map.core :as em])
  (:import [com.github.scribejava.core.builder ServiceBuilder]
           [com.github.scribejava.apis MicrosoftAzureActiveDirectory20Api GoogleApi20 LinkedInApi20 FacebookApi]
           [com.github.scribejava.core.model Response OAuthRequest OAuth2AccessToken Verb]
           [com.github.scribejava.core.oauth OAuth20Service]
           (java.time ZoneId LocalDateTime ZonedDateTime)
           (java.net URLEncoder)))

(def cache (em/expiring-map 60 {:max-size 100})) ;; TODO: CHECK PERF!

(defn is-root [email]
  (some? ((get env :root-users #{}) email)))

(defn admin-in [zones email]
  (let [a (atom {})]
    (doseq [zone zones]
      (when (or (is-root (get-in *identity* [:user :email])) (contains? (get zone :admins) email))
        (reset! a (merge-with clojure.set/union @a {(:zone zone) #{(:name zone)}}))))
    @a))

(defn find-settings [tenant_id]
  (let [settings (get cache tenant_id (edn/read-string (:settings (db/get-settings {:tenant_id tenant_id}))))]
    (when (and tenant_id (not (get cache tenant_id)))
      (em/assoc! cache tenant_id settings))
    (when (<= 100 (count cache))
      (log/warn "Settings cache filled"))
    settings))

(defn find-zone-settings [tenant_id zone name]
  (let [settings (find-settings tenant_id)]
    (let [found (first (filter #(and (= zone (:zone %)) (= name (:name %))) (:zones settings)))]
      (merge found (merge {:timezone (or (:timezone settings) "Europe/Berlin")
                           :bang-hour (Integer/valueOf (or (:bang-hour settings) 16))
                           :bang-minute (Integer/valueOf (or (:bang-minute settings) 0))})))))

(defn is-admin? [email zone]
  (let [loaded-tenant (if (nil? (:email (:tenant *identity*))) (db/get-tenant-by-id {:id (get-in *identity* [:tenant :id])}))]
    (or
      (is-root email)
      (#{(:email loaded-tenant) (:admin loaded-tenant)} email)
      (let [loaded-zone (if (nil? (:slots zone)) (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone)) zone)]
        (contains? (get-in loaded-zone [:admins]) email)))))

(defn has-own-space? [email zone]
  (some #(= email (:owner %)) (get-in zone [:slots])))

(def facebookv4 (FacebookApi/customVersion "4.0"))
(defn- get-user-info-facebook [code domain]
  (let [builder (doto (new ServiceBuilder (get-in env [:open-id-connect :facebook :api-key]))
                  (.apiSecret (get-in env [:open-id-connect :facebook :api-secret]))
                  (.defaultScope "email")
                  (.callback (str domain "/oauth-callback/facebook")))
        ^OAuth20Service service (.build builder facebookv4)
        ^OAuth2AccessToken token (.getAccessToken service code)
        request (new OAuthRequest Verb/GET "https://graph.facebook.com/v4.0/me?fields=name,email")]
    (.signRequest service token request)
    (let [^Response resp (.execute service request)
          user (json/read-str (.getBody resp))
          user-info {:user-name (get user "name")
                     :email (get user "email")}]
      user-info)))

(defn- get-user-info-azure [code domain]
  (let [builder (doto (new ServiceBuilder (get-in env [:open-id-connect :azure :api-key]))
                  (.apiSecret (get-in env [:open-id-connect :azure :api-secret]))
                  (.defaultScope "openid User.Read")
                  (.callback (str domain "/oauth-callback/azure")))
        ^OAuth20Service service (.build builder (MicrosoftAzureActiveDirectory20Api/instance))
        ^OAuth2AccessToken token (.getAccessToken service code)
        request (new OAuthRequest Verb/GET "https://graph.microsoft.com/v1.0/me")]
    (.signRequest service token request)
    (let [^Response resp (.execute service request)
          user (json/read-str (.getBody resp))
          user-info {:user-name (get user "displayName")
                     :email (get user "mail")}]

      user-info)))

(defn- get-user-info-google [code domain]
  (let [builder (doto (new ServiceBuilder (get-in env [:open-id-connect :google :api-key]))
                  (.apiSecret (get-in env [:open-id-connect :google :api-secret]))
                  (.defaultScope "openid email profile")
                  (.callback (str domain "/oauth-callback/google")))
        ^OAuth20Service service (.build builder (GoogleApi20/instance))
        ^OAuth2AccessToken token (.getAccessToken service code)
        request (new OAuthRequest Verb/GET "https://www.googleapis.com/oauth2/v3/userinfo")]
    (.signRequest service token request)
    (let [^Response resp (.execute service request)
          user (json/read-str (.getBody resp))
          user-info {:user-name (get user "name")
                     :email (get user "email")}]

      user-info)))

(defn- get-user-info-linkedin [code domain]
  (let [builder (doto (new ServiceBuilder (get-in env [:open-id-connect :linkedin :api-key]))
                  (.apiSecret (get-in env [:open-id-connect :linkedin :api-secret]))
                  (.defaultScope "r_liteprofile r_emailaddress")
                  (.callback (str domain "/oauth-callback/linkedin")))
        ^OAuth20Service service (.build builder (LinkedInApi20/instance))
        ^OAuth2AccessToken token (.getAccessToken service code)
        request (new OAuthRequest Verb/GET "https://api.linkedin.com/v2/me")]
    (.signRequest service token request)
    (let [^Response resp (.execute service request)
          user (json/read-str (.getBody resp))
          user-info {:user-name (str (get user "localizedFirstName") " " (get user "localizedLastName"))}
          email-request (new OAuthRequest Verb/GET "https://api.linkedin.com/v2/emailAddress?q=members&projection=(elements*(handle~))")]
      (.signRequest service token email-request)
      (let [^Response email-resp (.execute service email-request)
            email (json/read-str (.getBody email-resp))
            email-address (get-in (first (get email "elements")) ["handle~" "emailAddress"])]
           (merge user-info {:email email-address})))))


(defn home-page [request csrf-token session-state]
  (layout/render request "home.html" csrf-token session-state))

(defn parse-date [date]
  (jt/local-date "yyyy-MM-dd" date))

(defn add-owner-info [m has-own-space? out-map]
  (let [result (reduce-kv (fn [m k v]
                            (assoc m k {:data v
                                        :own (if has-own-space?
                                               (if (seq (get out-map k))
                                                 :out
                                                 :yes)
                                               :no)}))
                          {} m)]
    {:days result
     :own-default (if has-own-space? :yes :no)}))

(defn- generate-month [from to]
  (take-while #(jt/before? % to) (jt/iterate jt/plus from (jt/days 1))))

(defn- generate-filtered-days [from days filter-fn]
  (vec (take days (remove nil? (remove #(filter-fn %) (jt/iterate jt/plus from (jt/days 1)))))))

(defn get-analytics [tenant_id parking-zone parking date]
  (let [d (parse-date date)
        zone-settings (find-zone-settings tenant_id parking-zone parking)
        from (jt/adjust d :first-day-of-month)
        to (jt/adjust d :last-day-of-month)
        filtered-days (into #{} (remove nil? (map-indexed #(when %2 (inc %1)) (get zone-settings :disabled-days (repeat 7 false)))))
        month (remove #(filtered-days (.getValue (jt/day-of-week %))) (generate-month from to))
        max-slots (or (count (filter #(nil? (:owner %)) (:slots zone-settings))) 0)
        current-month (atom (computation/flat-vals (group-by :parking_day (map (fn [item]
                                                                                 {:actives 0
                                                                                  :outs max-slots
                                                                                  :parking_day (str item)}) month))))]
       (doseq [rec (db/get-active-count-for-time {:tenant_id (get-in *identity* [:tenant :id])
                                                  :from from
                                                  :to to
                                                  :parking_zone parking-zone
                                                  :parking_name parking})]
         (let [outs (:outs rec)]
           (swap! current-month assoc (:parking_day rec) (merge (get @current-month (:parking_day rec)) rec))
           (swap! current-month assoc-in [(:parking_day rec) :outs] (+ outs max-slots))))
    (sort-by :parking_day (vals @current-month))))

(defn get-score [parking-zone date days-look-back]
  (let [to (parse-date date)]
    (db/get-score {:tenant_id (get-in *identity* [:tenant :id])
                   :to to
                   :from (jt/adjust to jt/minus (jt/days days-look-back))
                   :parking_zone parking-zone})))

(defn- create-disabled-days [zones]
  (let [m (atom {})]
    (doseq [zone zones]
      (reset! m (assoc-in @m [(:zone zone) (:name zone)] (:disabled-days zone))))
    @m))

(defn- create-days-look-back [zones]
  (let [m (atom {})]
    (doseq [zone zones]
      (reset! m (assoc-in @m [(:zone zone) (:name zone)] (Integer/valueOf (or (:days-look-back zone) 30)))))
    @m))

(defn get-user-tenant-conf []
  (let [tenant (db/get-whole-tenant-by-id {:id (get-in *identity* [:tenant :id])})
        settings (edn/read-string (:settings tenant))]
    {:bang-seconds-utc (:bang_seconds_utc tenant)
     :is-admin (or (is-root (get-in *identity* [:user :email])) (some? (#{(:email tenant) (:admin tenant)} (get-in *identity* [:user :email]))))
     :is-admin-in (admin-in (:zones settings) (get-in *identity* [:user :email]))
     :zones (sort-by #(str (first %) (second %)) (map (fn [zone] [(:zone zone) (:name zone) (:disabled-days zone)]) (:zones settings)))
     :disabled-days (create-disabled-days (:zones settings))
     :days-look-back (create-days-look-back (:zones settings))}))

(defn get-timezones []
  (let [n (LocalDateTime/now (ZoneId/of "UTC"))]
    (->> (ZoneId/getAvailableZoneIds)
         (into (sorted-set-by #(compare (.atZone n (ZoneId/of %2)) (.atZone n (ZoneId/of %1)))))
         (map (fn [z]
                (let [zonedDateTime (.atZone n (ZoneId/of z))
                      offset (.getId (.getOffset zonedDateTime))
                      id (clojure.string/replace offset #"Z" "+00:00")]
                  [(str "UTC" id) z]))))))

(defmacro doseq-indexed [index-sym [item-sym coll] & body]
  `(doseq [[~index-sym ~item-sym] (map list (range) ~coll)]
     ~@body))

(defn get-settings []
  (let [settings (db/get-settings {:tenant_id (get-in *identity* [:tenant :id])})]
    (if (:settings settings)
      (let [parsed (edn/read-string (:settings settings))
            s (atom parsed)]
        (doseq-indexed idx [zone (:zones parsed)]
          (reset! s (assoc-in @s [:zones idx :admins] (clojure.string/join " " (get-in zone [:admins]))))
          (doseq-indexed i [slot (get-in @s [:zones idx :slots])]
                         (reset! s (assoc-in @s [:zones idx :slots i :types] (clojure.string/join " " (get-in slot [:types]))))))
        @s)
      {:timezone "Europe/Berlin" :bang-hour 16 :bang-minute 0 :zones []})))



(defn set-settings [settings]
  (let [s (atom settings)]
    (doseq-indexed idx [zone (:zones settings)]
                   (reset! s (assoc-in @s [:zones idx :admins] (into #{} (filter seq (clojure.string/split (get-in zone [:admins] "") #"[\s,;]")))))
                   (doseq-indexed i [slot (get-in @s [:zones idx :slots])]
                                  (reset! s (assoc-in @s [:zones idx :slots i :types] (into #{} (filter seq (clojure.string/split (get-in slot [:types] "") #"[\s,;]")))))))
    (db/set-settings {:tenant_id (get-in *identity* [:tenant :id])
                      :bang_seconds_utc (- (+ (* 3600 (Integer/valueOf (get settings :bang-hour 16))) (* 60 (Integer/valueOf (get settings :bang-minute 0)))) (.getTotalSeconds (.getOffset (.getRules (ZoneId/of (get settings :timezone "Europe/Berlin"))) (LocalDateTime/now (ZoneId/of "UTC")))))
                      :admin (:admin settings)
                      :settings (pr-str @s)})
    (em/assoc! cache (get-in *identity* [:tenant :id]) @s)))

(defn get-days [zone date email days]
  (let [from (parse-date date)
        zone-settings (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone))
        filtered-days (into #{} (remove nil? (map-indexed #(when %2 (inc %1)) (get zone-settings :disabled-days (repeat 7 false)))))
        date-list (generate-filtered-days from days #(filtered-days (.getValue (jt/day-of-week %))))
        fetch-fn (if (:visitor *identity*) db/get-visitor-parkings db/get-parkings)
        [data out loaded-zone] (conman/with-transaction [parky.db.core/*db*]
                                                        [(vec (fetch-fn {:tenant_id (get-in *identity* [:tenant :id])
                                                                         :email email
                                                                         :parking_zone (:zone zone)
                                                                         :parking_name (:name zone)
                                                                         :dates date-list}))
                                                         (vec (db/has-out-slots? {:tenant_id (get-in *identity* [:tenant :id])
                                                                                  :email email
                                                                                  :parking_zone (:zone zone)
                                                                                  :parking_name (:name zone)
                                                                                  :dates date-list}))
                                                         (if (nil? (:slots zone)) (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone)) zone)])]
    (add-owner-info (group-by #(str (:parking_day %)) data)
                    (has-own-space? email loaded-zone)
                    (group-by #(str (:parking_day %)) out))))

(defn get-types [zone]
  (->> (get-in zone [:slots])
    (map :types)
    (apply concat)
    (remove nil?)
    (distinct)
    (sort)))

(defn reserve-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (let [user {:tenant_id (get-in *identity* [:tenant :id])
                                       :user_name (:user-name user-info)
                                       :email (:email user-info)
                                       :parking_day date
                                       :parking_zone (:zone zone)
                                       :parking_name (:name zone)
                                       :on_behalf_of (get user-info :on-behalf-of false)
                                       :parking_type (:parking-type user-info)}]
                             (if (:visitor *identity*)
                               (do
                                 (db/add-visitor-parking! user)
                                 (doseq [admin (get-in zone [:admins])]
                                   (computation/notification-visitor-request admin (:user-name user-info) (:email user-info) date (:zone zone) (:name zone))))
                               (do
                                 (db/add-parking! user)
                                 (let [loaded-zone (if (nil? (:slots zone)) (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone)) zone)]
                                   (when (computation/is-after-bang-hour? (jt/minus date (jt/days 1)) loaded-zone)
                                     (computation/activate-winners zone date false [user] (computation/get-slots date loaded-zone) true false))))))))

(defn admin-activate-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (let [user (merge (db/load-parking {:tenant_id (get-in *identity* [:tenant :id])
                                                               :email (:email user-info)
                                                               :parking_day date
                                                               :parking_zone (:zone zone)
                                                               :parking_name (:name zone)})
                                             {:on_behalf_of (get user-info :on-behalf-of true)})
                                 loaded-zone (if (nil? (:slots zone)) (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone)) zone)]
                             (computation/activate-winners loaded-zone date false [user] (if slot-name [{:name slot-name}] (computation/get-slots date loaded-zone)) false true))))

(defn admin-show-select-click [zone date user-name email]
  (conman/with-transaction [parky.db.core/*db*]
                           (let [loaded-zone (if (nil? (:slots zone)) (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone)) zone)
                                 slot-names (computation/get-slots (parse-date date) loaded-zone)]
                             {:parking (:name zone)
                              :parking_zone (:zone zone)
                              :date date
                              :user-name user-name
                              :email email
                              :slot-names (vec (map :name slot-names))})))

(defn admin-unblock-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (db/unblock! {:tenant_id (get-in *identity* [:tenant :id])
                                         :email (:email user-info)
                                         :parking_day date
                                         :parking_zone (:zone zone)
                                         :parking_name (:name zone)})))

(defn admin-reserve-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (let [user {:tenant_id (get-in *identity* [:tenant :id])
                                       :user_name (:user-name user-info)
                                       :email (:email user-info)
                                       :parking_day date
                                       :parking_zone (:zone zone)
                                       :parking_name (:name zone)
                                       :on_behalf_of (get user-info :on-behalf-of true)
                                       :parking_type nil}]
                                (db/add-parking! user))))

(defn admin-deactivate-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (when (< 0 (db/deactivate-parking! {:tenant_id (get-in *identity* [:tenant :id])
                                                               :email (:email user-info)
                                                               :parking_day date
                                                               :parking_zone (:zone zone)
                                                               :parking_name (:name zone)
                                                               :on_behalf_of (get user-info :on-behalf-of true)}))
                             (computation/notification-deactivated-by-admin (:email user-info) date (:zone zone) (:name zone)))))

(defn cancel-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (db/delete-parking! {:tenant_id (get-in *identity* [:tenant :id])
                                                :email (:email user-info)
                                                :parking_day date
                                                :parking_zone (:zone zone)
                                                :parking_name (:name zone)})))

(defn out-of-office-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (db/create-out-of-office! {:tenant_id (get-in *identity* [:tenant :id])
                                                      :user_name (:user-name user-info)
                                                      :email (:email user-info)
                                                      :parking_day date
                                                      :parking_zone (:zone zone)
                                                      :parking_name (:name zone)
                                                      :on_behalf_of (get user-info :on-behalf-of false)})))

(defn slot-name-for-owner [email zone]
  (some #(when (= email (:owner %)) (:name %)) (:slots (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone)))))

(defn cancel-out-of-office-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (let [own-slot-name (slot-name-for-owner (:email user-info) zone)
                                 active-slots (db/has-active-slot-with-name? {:tenant_id (get-in *identity* [:tenant :id])
                                                                              :email  (:email user-info)
                                                                              :parking_day date
                                                                              :parking_zone (:zone zone)
                                                                              :parking_name (:name zone)
                                                                              :slot_name own-slot-name})]
                             (if (empty? active-slots)
                               (db/cancel-out-of-office! {:tenant_id (get-in *identity* [:tenant :id])
                                                          :email (:email user-info)
                                                          :parking_day date
                                                          :parking_zone (:zone zone)
                                                          :parking_name (:name zone)})
                               (response/conflict!)))))

(defn deactivate-click [zone date user-info slot-name]
  (conman/with-transaction [parky.db.core/*db*]
                           (let [user {:tenant_id (get-in *identity* [:tenant :id])
                                       :user_name (:user-name user-info)
                                       :email (:email user-info)
                                       :parking_day date
                                       :parking_zone (:zone zone)
                                       :parking_name (:name zone)
                                       :on_behalf_of (get user-info :on-behalf-of false)}]
                             (db/deactivate-parking! user)
                             (doseq [parking (db/get-inactive-parkings-by-day {:tenant_id (get-in *identity* [:tenant :id])
                                                                               :parking_day date
                                                                               :parking_zone (:zone zone)
                                                                               :parking_name (:name zone)})]
                               (computation/notification-gave-up (:email parking) date (:zone zone) (:name zone))))))

(comment
  (def zone "Europe/Berlin")
  (def parking-name "prague")
  (def date (jt/local-date))
  (def bang-hour 16))

(defn can-click? [date zone]
  (computation/is-before-bang-hour? date zone))

(defn click-parking [zone date user-info click-fn on-behalf-of-user-name on-behalf-of-email slot-name context-email]
  (let [user-data (if (and (some? on-behalf-of-user-name) (some? on-behalf-of-email) (is-admin? (:email user-info) zone))
                    (merge user-info {:user-name on-behalf-of-user-name
                                      :email on-behalf-of-email
                                      :on-behalf-of true})
                    user-info)
        real-context-email (or context-email (:email user-info))
        settings (find-zone-settings (get-in *identity* [:tenant :id]) (:zone zone) (:name zone))
        [parkings out] (conman/with-transaction [parky.db.core/*db*]
                                                (let [parsed-date (parse-date date)]
                                                    (when (can-click? parsed-date settings) (click-fn settings parsed-date user-data slot-name))
                                                    [(db/get-parkings-by-day {:tenant_id (get-in *identity* [:tenant :id]) :parking_zone (:zone zone) :parking_name (:name zone) :parking_day parsed-date})
                                                     (db/has-out-slots? {:tenant_id (get-in *identity* [:tenant :id]) :email real-context-email :parking_zone (:zone zone) :parking_name (:name zone) :dates [parsed-date]})]))]
    (add-owner-info {date (vec parkings)} (has-own-space? real-context-email settings) {date (vec out)})))

(defn- send-login-redirect [session-state user-info]
  (let [tenant (db/get-tenant-by-host {:host (:host session-state)})
        location (if (or (= "localhost:3000" (:host session-state)) (clojure.string/starts-with? (:host session-state) "local.")) (str "http://" (:host session-state)) (str "https://" (:host session-state)))
        is-admin (or (is-root (:email user-info)) (some? (#{(:email tenant) (:admin tenant)} (:email user-info))))
        settings (find-settings (:id tenant))
        is-user (or
                  (some #(clojure.string/ends-with? (:email user-info) %) (into #{} (filter seq (clojure.string/split (or (get-in settings [:allowed-email-suffixes]) "") #"[\s,;]"))))
                  (contains? (into #{} (filter seq (clojure.string/split (or (get-in settings [:allowed-emails]) "") #"[\s,;]"))) (:email user-info)))]
    (if (and (:activated tenant) (or (:allow-visitors settings) is-admin is-user))
      {:status  302
       :headers {"Location" location}
       :cookies {"ident" {:path      "/"
                          :http-only true
                          :value     (jwt/sign {:user    (merge user-info {:is-admin is-admin})
                                                :visitor (and
                                                           (not is-admin)
                                                           (not is-user))
                                                :created (System/currentTimeMillis)
                                                :tenant  {:id (:id tenant) :host (:host tenant)}} parky.middleware/jwt-secret)}}
       :body    ""}
      (layout/error-page {:status 401, :title "401 - Unauthorized"}))))

(defn login-redirect [req user-info-fn]
  (let [code (get-in req [:params :code])
        raw-session-state (get-in req [:params :state])
        session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)]
    (if (and session-state (get-in req [:cookies "x-csrf-token" :value]) (:csrf-token session-state) (= (get-in req [:cookies "x-csrf-token" :value]) (:csrf-token session-state)))
      (let [callback-location (if (= "localhost:3000" (:host session-state))
                                "http://localhost:3000"
                                (str "https://" (if (or
                                                      (clojure.string/ends-with? (:host session-state) ".parkybot.com")
                                                      (clojure.string/ends-with? (:host session-state) ".holdybot.com"))
                                                  (if (clojure.string/starts-with? (:host session-state) "local.")
                                                    (clojure.string/replace-first (clojure.string/replace-first (:host session-state) #":\d+" "") #"local\." "login.")
                                                    (clojure.string/replace-first (:host session-state) #"\w+\." "login."))
                                                  (:host session-state))))

            user-info (user-info-fn code callback-location)]
        (send-login-redirect session-state user-info))
      (layout/error-page {:status 401, :title "401 - Unauthorized"}))))


(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats
                 wrap-http-response]}
   ["/" {:get {:handler (fn [req]
                          (let [csrf-token (if (get-in req [:cookies "x-csrf-token" :value])
                                             (get-in req [:cookies "x-csrf-token" :value])
                                             (bytes->hex (hash/sha256 (.toString (java.util.UUID/randomUUID)))))
                                session-state (jwt/sign {:csrf-token csrf-token
                                                         :host (get-in req [:headers "x-forwarded-host"] (get-in req [:headers "host"] "localhost:3000"))} parky.middleware/jwt-secret)] ;; good enough for me today, ciao future myself! :)
                            (merge
                              (home-page req csrf-token session-state)
                              {:cookies {"x-csrf-token" {:path "/"
                                                         :http-only true
                                                         :value csrf-token}}})))}}]

   ["/days/:days/:parking-zone/:parking/:date" {:get {:parameters {:path {:date string?, :parking string?, :parking-zone string?, :days integer?}}
                                                      :handler (fn [req]
                                                                 (if (:user *identity*)
                                                                   (let [date (get-in req [:parameters :path :date])
                                                                         parking (get-in req [:parameters :path :parking])
                                                                         parking-zone (get-in req [:parameters :path :parking-zone])
                                                                         days (get-in req [:parameters :path :days])
                                                                         zone {:zone parking-zone :name parking}
                                                                         email (if (and (get-in req [:params :email]) (:user *identity*) (is-admin? (:email (:user *identity*)) zone))
                                                                                 (get-in req [:params :email])
                                                                                 (:email (:user *identity*)))]
                                                                     {:status 200
                                                                      :body (get-days zone date email days)})
                                                                   {:status (if (:user *identity*) 403 401)}))}}]

   ["/analytics/:parking-zone/:parking/:date" {:get {:parameters {:path {:parking-zone string?, :parking string?, :date string?}}}
                                                  :handler (fn [req]
                                                             (let [parking (get-in req [:parameters :path :parking])
                                                                   parking-zone (get-in req [:parameters :path :parking-zone])
                                                                   date (get-in req [:parameters :path :date])]
                                                               (if (and (:user *identity*) (:tenant *identity*))
                                                                 (let [tenant (db/get-tenant-by-id {:id (get-in *identity* [:tenant :id])})]
                                                                   (if (or (is-root (:email (:user *identity*))) #{(:email tenant) (:admin tenant)} (:email (:user *identity*)))
                                                                     {:status 200
                                                                      :body (get-analytics (:id tenant) parking-zone parking date)}
                                                                     {:status 403}))
                                                                 {:status 401})))}]

   #_["/excel/:parking-zone/:parking/:date" {:get {:parameters {:path {:parking-zone string?, :parking string?, :date string?}}}
                                             :handler (fn [req]
                                                        (let [parking (get-in req [:parameters :path :parking])
                                                              parking-zone (get-in req [:parameters :path :parking-zone])
                                                              date (get-in req [:parameters :path :date])]
                                                          (if (and (:user *identity*) (:tenant *identity*))
                                                            (let [tenant (db/get-tenant-by-id {:id (get-in *identity* [:tenant :id])})]
                                                              (if (or (is-root (:email (:user *identity*))) #{(:email tenant) (:admin tenant)} (:email (:user *identity*)))
                                                                {:status 200
                                                                 :body (get-analytics (:id tenant) parking-zone parking date)}
                                                                {:status 403}))
                                                            {:status 401})))}]

   ["/score/:parking-zone/:date/:days-look-back" {:get {:parameters {:path {:parking-zone string?, :date string?, :days-look-back integer?}}
                                                        :handler (fn [req]
                                                                   (let [parking-zone (get-in req [:parameters :path :parking-zone])
                                                                         date (get-in req [:parameters :path :date])
                                                                         days-look-back (get-in req [:parameters :path :days-look-back])]
                                                                     (if (and (:user *identity*))
                                                                       {:status 200
                                                                        :body (get-score parking-zone date days-look-back)}
                                                                       {:status (if (:user *identity*) 403 401)})))}}]

   ["/timezones" {:get {:handler (fn [req]
                                   (if (:user *identity*)
                                     {:status 200
                                      :body (get-timezones)}
                                     {:status (if (:user *identity*) 403 401)}))}}]

   ["/conf" {:get {:handler (fn [req]
                              (if (:user *identity*)
                                {:status 200
                                 :body (get-user-tenant-conf)}
                                {:status (if (:user *identity*) 403 401)}))}}]

   ["/settings" {:get  {:handler (fn [req]
                                   (if (and (:user *identity*) (:tenant *identity*))
                                     (let [tenant (db/get-tenant-by-id {:id (get-in *identity* [:tenant :id])})]
                                       (if (or (is-root (:email (:user *identity*))) #{(:email tenant) (:admin tenant)} (:email (:user *identity*)))
                                         {:status 200
                                          :body   (get-settings)}
                                         {:status 403}))
                                     {:status 401}))}
                 :post {:handler    (fn [req]
                                      (if (and (:user *identity*) (:tenant *identity*))
                                        (let [tenant (db/get-tenant-by-id {:id (get-in *identity* [:tenant :id])})
                                              settings (get-in req [:body-params :settings])]
                                             (if (or (is-root (:email (:user *identity*))) (#{(:email tenant) (:admin tenant)} (:email (:user *identity*))))
                                               (do
                                                 (set-settings settings)
                                                 {:status 200})
                                               {:status 403}))
                                        {:status 401}))}}]

   ["/types/:parking-zone/:parking" {:get {:parameters {:path {:parking string?, :parking-zone string?}}}
                                     :handler (fn [req]
                                                (if (and (:user *identity*) (:tenant *identity*))
                                                  (let [parking (get-in req [:parameters :path :parking])
                                                        parking-zone (get-in req [:parameters :path :parking-zone])]
                                                    {:status 200
                                                     :body (get-types (find-zone-settings (get-in *identity* [:tenant :id]) parking-zone parking))})
                                                  {:status 401}))}]

   ["/available" {:get {:parameters {:query {:name string?}}}
                  :handler (fn [req]
                             (let [name (get-in req [:parameters :query :name])]
                               {:status 200
                                :body {:data (nil? (db/get-tenant-by-host {:host name}))}}))}]

   ["/activate" {:get {:parameters {:query {:name string? :token string?}}}
                 :handler (fn [req]
                            (let [name (get-in req [:parameters :query :name])
                                  token (get-in req [:parameters :query :token])
                                  activation-result (db/activate-tenant! {:host name
                                                                          :activation_token token})]
                              (if (= 1 activation-result)
                                {:status 302
                                 :headers {"Location" (str "//" name)}
                                 :body ""}
                                (layout/error-page {:status 401, :title "401 - Unauthorized"}))))}]

   ["/create-new" {:get {:parameters {:query {:token string? :name string? :email string?}}}
                   :handler (fn [req]
                              (let [token (get-in req [:parameters :query :token])
                                    email (get-in req [:parameters :query :email])
                                    name (get-in req [:parameters :query :name])
                                    response (client/post "https://www.google.com/recaptcha/api/siteverify" {:query-params {:secret (get-in env [:recaptcha :secretkey])
                                                                                                                            :response token}
                                                                                                             :content-type :json
                                                                                                             :as :json})
                                    is-success (get-in response [:body :success])]
                                (if (and is-success (< 18 (count name))) ;; 6.8.3 ;; smthng.holdybot.com
                                  (do
                                    (let [activation-token (uuid/str (java.util.UUID/randomUUID))
                                          link (str "https://" (clojure.string/replace-first name #"[a-z0-9\-]+\." "app.") "/activate?name=" (URLEncoder/encode name "UTF-8") "&token=" (URLEncoder/encode activation-token "UTF-8"))]
                                      (db/create-tenant! {:host name :email email :activation_token activation-token :settings (pr-str {:timezone "Europe/Berlin" :bang-hour (+ 16 (rand-int 3)) :bang-minute (* 5 (rand-int 12)) :zones [{:zone (or (first (clojure.string/split name #"\.")) "Default") :name "1" :slots [{:name "Slot 1"}]}]})})
                                      (email/send-email email (str "Your " name " activation code") (str "Please activate your account by clicking on " link))
                                      (log/debug "email link to activate" link)
                                      {:status 200}))
                                  {:status 400})))}]

   ["/email-login" {:get {:handler (fn [req]
                                     (let [token (get-in req [:params :token])
                                           email (get-in req [:params :email])
                                           user-name (get-in req [:params :user-name])
                                           raw-session-state (get-in req [:params :state])
                                           session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)
                                           record (db/get-email-token {:host (:host session-state)
                                                                       :email email
                                                                       :token token})]
                                       (if (and session-state record (jt/before? (jt/minus (jt/local-date-time) (jt/days 1)) (:created record)) (= token (:token record)))
                                         (do
                                           (db/delete-email-token! {:host (:host session-state)
                                                                    :email email
                                                                    :token token})
                                           (send-login-redirect session-state {:user-name user-name :email email}))
                                         (layout/error-page {:status 401, :title "401 - Unauthorized"}))))}}]

   ["/create-login-token" {:get {:parameters {:query {:state string? :token string? :email string? :user-name string?}}}
                           :handler (fn [req]
                                      (let [token (get-in req [:parameters :query :token])
                                            email (get-in req [:parameters :query :email])
                                            user-name (get-in req [:parameters :query :user-name])
                                            raw-session-state (get-in req [:parameters :query :state])
                                                              session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)
                                            response (client/post "https://www.google.com/recaptcha/api/siteverify" {:query-params {:secret (get-in env [:recaptcha :secretkey])
                                                                                                                                    :response token}
                                                                                                                     :content-type :json
                                                                                                                     :as :json})
                                            is-success (get-in response [:body :success])]
                                        (if (and session-state is-success)
                                          (do
                                            (let [activation-token (uuid/str (java.util.UUID/randomUUID))
                                                  link (str "https://" (:host session-state) "/email-login?email=" (URLEncoder/encode email "UTF-8") "&user-name=" (URLEncoder/encode user-name "UTF-8") "&token=" (URLEncoder/encode activation-token "UTF-8") "&state=" (URLEncoder/encode raw-session-state "UTF-8"))]
                                              (db/create-login-token! {:host (:host session-state) :email email :token activation-token})
                                              (email/send-email email (str "Your " (:host session-state) " login link") (str "Please login to your account by clicking on " link))
                                              (log/debug "email link to login" link)
                                              {:status 200}))
                                          {:status 400})))}]

   ["/admin/activate/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                    :handler (fn [req]
                                                               (let [date (get-in req [:parameters :path :date])
                                                                     parking (get-in req [:parameters :path :parking])
                                                                     parking-zone (get-in req [:parameters :path :parking-zone])
                                                                     user-name (get-in req [:body-params :user-name])
                                                                     email (get-in req [:body-params :email])
                                                                     context-email (get-in req [:body-params :context-email])
                                                                     slot-name (get-in req [:body-params :slot-name])
                                                                     user-info (merge *identity* {:user {:user-name user-name
                                                                                                         :email email}})
                                                                     zone {:zone parking-zone :name parking}]
                                                                 (if (and (:user *identity*) (not (:visitor *identity*)) (is-admin? (:email (:user *identity*)) zone))
                                                                     {:status 200
                                                                      :body (click-parking zone date (:user user-info) admin-activate-click user-name email slot-name context-email)}
                                                                     {:status (if (:user *identity*) 403 401)})))}]

   ["/admin/cancel-out-of-office/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                                :handler (fn [req]
                                                                           (let [date (get-in req [:parameters :path :date])
                                                                                 parking (get-in req [:parameters :path :parking])
                                                                                 parking-zone (get-in req [:parameters :path :parking-zone])
                                                                                 user-name (get-in req [:body-params :user-name])
                                                                                 email (get-in req [:body-params :email])
                                                                                 context-email (get-in req [:body-params :context-email])
                                                                                 slot-name (get-in req [:body-params :slot-name])
                                                                                 user-info (merge *identity* {:user {:user-name user-name
                                                                                                                     :email email}})
                                                                                 zone {:zone parking-zone :name parking}]
                                                                             (if (and (:user *identity*) (not (:visitor *identity*)) (is-admin? (:email (:user *identity*)) zone))
                                                                               {:status 200
                                                                                :body (click-parking zone date (:user user-info) cancel-out-of-office-click user-name email slot-name context-email)}
                                                                               {:status (if (:user *identity*) 403 401)})))}]

   ["/admin/show-select/:parking-zone/:parking/:date" {:get {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                       :handler (fn [req]
                                                                  (let [date (get-in req [:parameters :path :date])
                                                                        parking (get-in req [:parameters :path :parking])
                                                                        parking-zone (get-in req [:parameters :path :parking-zone])
                                                                        user-name (get-in req [:query-params "user-name"])
                                                                        email (get-in req [:query-params "email"])
                                                                        zone {:zone parking-zone :name parking}]
                                                                    (if (and (:user *identity*) (not (:visitor *identity*)) (is-admin? (:email (:user *identity*)) zone))
                                                                      {:status 200
                                                                       :body (admin-show-select-click zone date user-name email)}
                                                                      {:status (if (:user *identity*) 403 401)})))}]

   ["/admin/reserve/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                   :handler (fn [req]
                                                              (let [date (get-in req [:parameters :path :date])
                                                                    parking-zone (get-in req [:parameters :path :parking-zone])
                                                                    parking (get-in req [:parameters :path :parking])
                                                                    user-name (get-in req [:body-params :user-name])
                                                                    email (get-in req [:body-params :email])
                                                                    context-email (get-in req [:body-params :context-email])
                                                                    user-info (merge *identity* {:user {:user-name user-name
                                                                                                        :email email}})
                                                                    zone {:zone parking-zone :name parking}]
                                                                (if (and (:user *identity*) (not (:visitor *identity*)) (is-admin? (:email (:user *identity*)) zone))
                                                                  {:status 200
                                                                   :body (click-parking zone date (:user user-info) admin-reserve-click user-name email nil context-email)}
                                                                  {:status (if (:user *identity*) 403 401)})))}]

   ["/admin/deactivate/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                      :handler (fn [req]
                                                                 (let [date (get-in req [:parameters :path :date])
                                                                       parking-zone (get-in req [:parameters :path :parking-zone])
                                                                       parking (get-in req [:parameters :path :parking])
                                                                       user-name (get-in req [:body-params :user-name])
                                                                       email (get-in req [:body-params :email])
                                                                       context-email (get-in req [:body-params :context-email])
                                                                       user-info (merge *identity* {:user {:user-name user-name
                                                                                                           :email email}})
                                                                       zone {:zone parking-zone :name parking}]
                                                                   (if (and (:user *identity*) (not (:visitor *identity*)) (is-admin? (:email (:user *identity*)) zone))
                                                                     {:status 200
                                                                      :body (click-parking zone date (:user user-info) admin-deactivate-click user-name email nil context-email)}
                                                                     {:status (if (:user *identity*) 403 401)})))}]



   ["/admin/unblock/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                   :handler (fn [req]
                                                              (let [date (get-in req [:parameters :path :date])
                                                                    parking-zone (get-in req [:parameters :path :parking-zone])
                                                                    parking (get-in req [:parameters :path :parking])
                                                                    user-name (get-in req [:body-params :user-name])
                                                                    email (get-in req [:body-params :email])
                                                                    context-email (get-in req [:body-params :context-email])
                                                                    user-info (merge *identity* {:user {:user-name user-name
                                                                                                        :email email}})
                                                                    zone {:zone parking-zone :name parking}]
                                                                (if (and (:user *identity*) (not (:visitor *identity*)) (is-admin? (:email (:user *identity*)) zone))
                                                                  {:status 200
                                                                   :body (click-parking zone date (:user user-info) admin-unblock-click user-name email nil context-email)}
                                                                  {:status (if (:user *identity*) 403 401)})))}]

   ["/reserve/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}
                                                    :handler (fn [req]
                                                               (if (:user *identity*)
                                                                 (let [date (get-in req [:parameters :path :date])
                                                                       parking-zone (get-in req [:parameters :path :parking-zone])
                                                                       parking (get-in req [:parameters :path :parking])
                                                                       parking-type (get-in req [:body-params :type])
                                                                       user-name (get-in req [:body-params :user-name])
                                                                       email (get-in req [:body-params :email])
                                                                       zone {:zone parking-zone :name parking}]
                                                                   {:status 200
                                                                      :body (click-parking zone date (merge (:user *identity*) {:parking-type parking-type}) reserve-click user-name email nil email)})
                                                                 {:status 401}))}}]

   ["/cancel/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                :handler (fn [req]
                                                           (if (:user *identity*)
                                                             (let [date (get-in req [:parameters :path :date])
                                                                   parking-zone (get-in req [:parameters :path :parking-zone])
                                                                   parking (get-in req [:parameters :path :parking])
                                                                   user-name (get-in req [:body-params :user-name])
                                                                   email (get-in req [:body-params :email])
                                                                   zone {:zone parking-zone :name parking}]
                                                               {:status 200
                                                                :body (click-parking zone date (:user *identity*) cancel-click user-name email nil email)})
                                                             {:status 401}))}]

   ["/outofoffice/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                 :handler (fn [req]
                                                            (if (and (:user *identity*) (not (:visitor *identity*)))
                                                              (let [date (get-in req [:parameters :path :date])
                                                                    parking-zone (get-in req [:parameters :path :parking-zone])
                                                                    parking (get-in req [:parameters :path :parking])
                                                                    user-name (get-in req [:body-params :user-name])
                                                                    email (get-in req [:body-params :email])
                                                                    zone {:zone parking-zone :name parking}]
                                                                {:status 200
                                                                 :body (click-parking zone date (:user *identity*) out-of-office-click user-name email nil email)})
                                                              {:status (if (and (:user *identity*) (:visitor *identity*)) 403 401)}))}]

   ["/cancel-outofoffice/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                        :handler (fn [req]
                                                                   (if (and (:user *identity*) (not (:visitor *identity*)))
                                                                     (let [date (get-in req [:parameters :path :date])
                                                                           parking-zone (get-in req [:parameters :path :parking-zone])
                                                                           parking (get-in req [:parameters :path :parking])
                                                                           user-name (get-in req [:body-params :user-name])
                                                                           email (get-in req [:body-params :email])
                                                                           zone {:zone parking-zone :name parking}]
                                                                       {:status 200
                                                                        :body (click-parking zone date (:user *identity*) cancel-out-of-office-click user-name email nil email)})
                                                                     {:status (if (and (:user *identity*) (:visitor *identity*)) 403 401)}))}]


   ["/deactivate/:parking-zone/:parking/:date" {:post {:parameters {:path {:date string?, :parking string?, :parking-zone string?}}}
                                                    :handler (fn [req]
                                                               (if (and (:user *identity*) (not (:visitor *identity*)))
                                                                 (let [date (get-in req [:parameters :path :date])
                                                                       parking-zone (get-in req [:parameters :path :parking-zone])
                                                                       parking (get-in req [:parameters :path :parking])
                                                                       user-name (get-in req [:body-params :user-name])
                                                                       email (get-in req [:body-params :email])
                                                                       zone {:zone parking-zone :name parking}]
                                                                   {:status 200
                                                                    :body (click-parking zone date (:user *identity*) deactivate-click user-name email nil email)})
                                                                 {:status (if (and (:user *identity*) (:visitor *identity*)) 403 401)}))}]

   ["/oauth-callback/azure-login" {:get {:handler (fn [req]
                                                    (login-redirect req get-user-info-azure))}}]

   ["/oauth-callback/azure" {:get {:handler (fn [req]
                                              (let [code (get-in req [:params :code])
                                                    raw-session-state (get-in req [:params :state])
                                                    session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)]
                                                (if session-state
                                                  (let [location (if (or (= "localhost:3000" (:host session-state)) (clojure.string/starts-with? (:host session-state) "local.")) (str "http://" (:host session-state)) (str "https://" (:host session-state)))]
                                                    {:status  302
                                                     :headers {"Location" (str location "/oauth-callback/azure-login?state=" (URLEncoder/encode raw-session-state "UTF-8") "&code=" (URLEncoder/encode code "UTF-8"))}
                                                     :body    ""})
                                                  (layout/error-page {:status 401, :title "401 - Unauthorized"}))))}}]

   ["/oauth-callback/google-login" {:get {:handler (fn [req]
                                                     (login-redirect req get-user-info-google))}}]

   ["/oauth-callback/google" {:get {:handler (fn [req]
                                               (let [code (get-in req [:params :code])
                                                     raw-session-state (get-in req [:params :state])
                                                     session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)]
                                                 (if session-state
                                                   (let [location (if (or (= "localhost:3000" (:host session-state)) (clojure.string/starts-with? (:host session-state) "local.")) (str "http://" (:host session-state)) (str "https://" (:host session-state)))]
                                                     {:status  302
                                                      :headers {"Location" (str location "/oauth-callback/google-login?state=" (URLEncoder/encode raw-session-state "UTF-8") "&code=" (URLEncoder/encode code "UTF-8"))}
                                                      :body    ""})
                                                   (layout/error-page {:status 401, :title "401 - Unauthorized"}))))}}]

   ["/oauth-callback/linkedin-login" {:get {:handler (fn [req]
                                                       (login-redirect req get-user-info-linkedin))}}]

   ["/oauth-callback/linkedin" {:get {:handler (fn [req]
                                                 (let [code (get-in req [:params :code])
                                                       raw-session-state (get-in req [:params :state])
                                                       session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)]
                                                   (if session-state
                                                     (let [location (if (or (= "localhost:3000" (:host session-state)) (clojure.string/starts-with? (:host session-state) "local.")) (str "http://" (:host session-state)) (str "https://" (:host session-state)))]
                                                       {:status  302
                                                        :headers {"Location" (str location "/oauth-callback/linkedin-login?state=" (URLEncoder/encode raw-session-state "UTF-8") "&code=" (URLEncoder/encode code "UTF-8"))}
                                                        :body    ""})
                                                     (layout/error-page {:status 401, :title "401 - Unauthorized"}))))}}]

   ["/oauth-callback/facebook-login" {:get {:handler (fn [req]
                                                       (login-redirect req get-user-info-facebook))}}]

   ["/oauth-callback/facebook" {:get {:handler (fn [req]
                                                 (let [code (get-in req [:params :code])
                                                       raw-session-state (get-in req [:params :state])
                                                       session-state (if raw-session-state (jwt/unsign raw-session-state parky.middleware/jwt-secret) nil)]
                                                   (if session-state
                                                     (let [location (if (or (= "localhost:3000" (:host session-state)) (clojure.string/starts-with? (:host session-state) "local.")) (str "http://" (:host session-state)) (str "https://" (:host session-state)))]
                                                       {:status  302
                                                        :headers {"Location" (str location "/oauth-callback/facebook-login?state=" (URLEncoder/encode raw-session-state "UTF-8") "&code=" (URLEncoder/encode code "UTF-8"))}
                                                        :body    ""})
                                                     (layout/error-page {:status 401, :title "401 - Unauthorized"}))))}}]

   ["/logout" {:post {:handler (fn [req]
                                 {:status 200
                                  :cookies {"ident" {:max-age 0
                                                     :path "/"
                                                     :http-only true}}})}}]
   ["/logout-all-users" {:post {:handler (fn [_]
                                           (if (and (:user *identity*) (:tenant *identity*))
                                             (let [tenant (db/get-tenant-by-id {:id (get-in *identity* [:tenant :id])})]
                                               (if (or (is-root (:email (:user *identity*))) (#{(:email tenant) (:admin tenant)} (:email (:user *identity*))))
                                                 (do
                                                   (db/update-jwt-valid-after {:tenant_id (get-in *identity* [:tenant :id])
                                                                               :jwt_valid_after (System/currentTimeMillis)})
                                                   {:status 200})
                                                 {:status 403}))
                                             {:status 401}))}}]])