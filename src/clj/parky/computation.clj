(ns parky.computation
  (:require [java-time :as jt]
            [parky.layout :refer [*identity*]]
            [postal.core :as postal]
            [parky.config :refer [env]]
            [parky.db.core :as db]
            [clojure.tools.logging :as log]
            [conman.core :as conman]
            [clj-http.client :as client]
            [clojure.tools.reader.edn :as edn])
  (:import (java.time LocalDateTime ZoneId LocalDate LocalTime)
           (java.util.concurrent TimeUnit)))

(defonce last-computed (atom {}))

(defn- is-fn-bang-hour? [date zone bang-fn]
  (let [timezone (get-in zone [:timezone] "Europe/Berlin")
        bang-hour (get-in zone [:bang-hour] 16)
        bang-minute (get-in zone [:bang-minute] 0)]
    (bang-fn (jt/zoned-date-time) (jt/adjust (jt/zoned-date-time date timezone) (jt/local-time bang-hour bang-minute)))))


(defn is-before-bang-hour? [date zone]
  (is-fn-bang-hour? date zone jt/before?))

(defn is-after-bang-hour? [date zone]
  (is-fn-bang-hour? date zone jt/after?))

(defn flat-vals [eligibles] (reduce-kv (fn [m k v] (assoc m k (first v))) {} eligibles))

(defn- compute-winners [pendings zone date slots-count]
 (let [user-points (flat-vals (group-by :email (db/get-points {:tenant_id (get-in *identity* [:tenant :id])
                                                               :parking_zone (:zone zone)
                                                               :parking_name (:name zone)
                                                               :to date
                                                               :from (jt/adjust date jt/minus (jt/days (Integer/valueOf (or (:days-look-back zone) 30))))
                                                               :emails (map :email pendings)})))
       eligibles (for [pending pendings]
                   (assoc pending :points (or (get-in user-points [(:email pending) :points]) 0)))
       sorted-points (sort-by :points < (shuffle eligibles))] ;; we need to shuffle here, to randomize prefered type requests
   (log/info "Sorted by points" sorted-points)
   (let [winners (take slots-count sorted-points)]
     (log/info "Winners" winners)
     winners)))

(defn- notification [email subject body]
  (log/info "Send email" email subject)
  (future (let [result (postal/send-message (get-in env [:smtp :transport])
                                            {:from (get-in env [:smtp :from])
                                             :to (clojure.string/trim email)
                                             :subject subject
                                             :body body})]
            (log/info (:error result) (:message result)))))

(defn- notification-activated [email date parking-zone parking-name slot-name]
  (notification email
                (str "You have won a space " slot-name " in " parking-zone " " parking-name " on " (jt/format "yyyy-MM-dd" date))
                (str "Congratulations! Your " (get env :app-name "Holdy") " at http://" (get-in *identity* [:tenant :host]))))

(defn- notification-deactivated [email date parking-zone parking-name]
  (notification email
                (str "Sorry, there is no free space for your request in " parking-zone " " parking-name " on " (jt/format "yyyy-MM-dd" date))
                (str "Your " (get env :app-name "Holdy") " at https://" (get-in *identity* [:tenant :host]))))

(defn notification-deactivated-by-admin [email date parking-zone parking-name]
  (notification email
                (str "Sorry, admin has just cancelled your space in " parking-zone " " parking-name " on " (jt/format "yyyy-MM-dd" date))
                (str "Your " (get env :app-name "Holdy") " at https://" (get-in *identity* [:tenant :host]))))

(defn notification-gave-up [email date parking-zone parking-name]
  (notification email
                (str "Someone has given up their space in " parking-zone " " parking-name " on " (jt/format "yyyy-MM-dd" date))
                (str "If you still need the place, please make a reservation asap. If the space is still free, you will get it immediately. Your " (get env :app-name "Holdy") " at https://" (get-in *identity* [:tenant :host]))))

(defn notification-visitor-request [admin-email user-name email date parking-zone parking-name]
  (notification admin-email
                (str "Dear admin, visitor " user-name " " email " asks for space in " parking-zone " " parking-name)
                (str "Please check their request for " date ". Your " (get env :app-name "Holdy") " at https://" (get-in *identity* [:tenant :host]))))

(defn get-slots [date zone]
  (let [taken-slot-names (into #{} (map :slot_name (db/get-taken-slots {:tenant_id    (get-in *identity* [:tenant :id])
                                                                        :parking_day  date
                                                                        :parking_zone (:zone zone)
                                                                        :parking_name (:name zone)})))
        out-slots (into #{} (map :email (db/get-out-slots {:tenant_id    (get-in *identity* [:tenant :id])
                                                           :parking_day  date
                                                           :parking_zone (:zone zone)
                                                           :parking_name (:name zone)})))
        slots (filter #(and (not (taken-slot-names (:name %)))
                            (if (some? (:owner %)) (out-slots (:owner %)) true)) (get-in zone [:slots]))]
       (log/debug "Slots" taken-slot-names out-slots slots)
       slots))

(defn group-by-type [slots]
  (let [m (atom {})]
    (doseq [slot slots]
      (if (seq (:types slot))
        (doseq [type (:types slot)]
          (swap! m assoc type (conj (get @m type []) slot)))
        (swap! m assoc nil (conj (get @m nil []) slot))))
    @m))

(defn compute-user-slots [date zone winners slots]
  (let [yesterdays-active (if (seq slots)
                            (db/get-taken-slots-for-emails {:tenant_id    (get-in *identity* [:tenant :id])
                                                            :parking_day  (jt/minus date (jt/days 1))
                                                            :parking_zone (:zone zone)
                                                            :parking_name (:name zone)
                                                            :emails       (map :email winners)
                                                            :slot_names   (map :name slots)})
                            [])
        winners-by-email (flat-vals (group-by :email winners))
        yesterdays-active-winners (flat-vals (group-by :email yesterdays-active))
        slots-by-type (group-by-type slots)
        slot-set (vec (map :name slots))
        winners-atom (atom {})]
    (doseq [winner winners]
      (if (and
            (some? (get yesterdays-active-winners (:email winner)))
            (contains? (vec (map :name (get slots-by-type (:parking_type winner)))) (get-in yesterdays-active-winners [(:email winner) :slot_name])))
        (swap! winners-atom assoc (get-in yesterdays-active-winners [(:email winner) :slot_name]) (:email winner))
        (let [matching-slots (map :name (get slots-by-type (:parking_type winner)))
              free-slot (first (filter (complement (into #{} (keys @winners-atom))) (remove nil? (cons (get-in yesterdays-active-winners [(:email winner) :slot_name]) matching-slots))))]
          (when (some? free-slot)
            (swap! winners-atom assoc free-slot (:email winner))))))
    (let [assigned-winners (into #{} (vals @winners-atom))]
      (doseq [winner winners]
        (when (not (contains? assigned-winners (:email winner)))
          (let [some-free-slot (first (filter (complement (into #{} (keys @winners-atom))) slot-set))]
            (when some-free-slot
              (swap! winners-atom assoc some-free-slot (:email winner)))))))
    (vec (map (fn [[slot-name email]] [email slot-name (get-in winners-by-email [email :user_name])]) @winners-atom))))

;; [:email :slot_name :user_name]

(defn ms-teams-msg [parking-zone parking-name date winners]
  {"@context" "https://schema.org/extensions"
   "@type" "MessageCard"
   "potentialAction" [{"@type" "OpenUri"
                       "name" (str "Show in " (get env :app-name "Holdy"))
                       "targets" [{"os" "default"
                                   "uri" (str "https://" (get-in *identity* [:tenant :host]) "/#/calendar/" parking-zone "/" parking-name)}]}]
   "sections" [{"facts" (map (fn [[email slot-name user-name]]
                               {:name slot-name
                                :value user-name}) winners)
                "text" "Congratulations!"}]
   "summary" (str (get env :app-name "Holdy") " results")
   "themeColor" "0072C6"
   "title" (str (get env :app-name "Holdy") " winners " date)})

(defn slack-msg [parking-zone parking-name date winners]
  {"text" (str "*Congratulations!*\t" (get env :app-name "Holdy") " winners" date "\n\n" (clojure.string/join (map (fn [[email slot-name user-name]]
                                                                                                                       (str "\n*" slot-name "*\t" user-name)) winners)))
   "attachments" [{"fallback" (str "Show " (get env :app-name "Holdy") " https://" (get-in *identity* [:tenant :host]) "/#/calendar/" parking-zone "/" parking-name)
                   "actions" [{"type" "button"
                               "text" (str "Open in "(get env :app-name "Holdy"))
                               "url" (str "https://" (get-in *identity* [:tenant :host]) "/#/calendar/" parking-zone "/" parking-name)}]}]})

(defn notify-winners [zone date winners]
  (log/debug "notify" winners)
  (when (seq winners)
    (when-let [url (get-in zone [:teams-hook-url])]
      (future (client/post url {:form-params (ms-teams-msg (:zone zone) (:name zone) date winners) :content-type :json})))
    (when-let [url (get-in zone [:slack-hook-url])]
      (future (client/post url {:form-params (slack-msg (:zone zone) (:name zone) date winners) :content-type :json})))))

(defn activate-winners [zone date more-users-than-slots? winners slots deactivate-remainings? include-inactive?]
   (let [activation-points (if more-users-than-slots? 7 5)
         users-slots (compute-user-slots date zone winners slots)]
     (log/info "Winners" users-slots)
     (doseq [[email slot-name] users-slots]
       (log/info "Activating" email slot-name)
       (db/activate-parking! {:tenant_id (get-in *identity* [:tenant :id])
                              :parking_zone (:zone zone)
                              :parking_name (:name zone)
                              :parking_day date
                              :points activation-points
                              :slot_name slot-name
                              :email email
                              :statuses (if include-inactive? ["inactive" "pending"] ["pending"])
                              :on_behalf_of include-inactive?})
       (notification-activated email date (:zone zone) (:name zone) slot-name))
     (when deactivate-remainings?
       (let [remainings (db/get-pending-parkings-by-day {:tenant_id (get-in *identity* [:tenant :id])
                                                         :parking_day date
                                                         :parking_zone (:zone zone)
                                                         :parking_name (:name zone)})]
         (db/deactivate-remainings! {:tenant_id (get-in *identity* [:tenant :id])
                                     :parking_zone (:zone zone)
                                     :parking_name (:name zone)
                                     :parking_day date})
         (doseq [remaining remainings]
           (notification-deactivated (:email remaining) date (:zone zone) (:name zone)))))
     (when (seq users-slots)
       (notify-winners zone date users-slots))))

(defn- compute [zone date slots-count]
  (log/info "Start Computing" (:zone zone) (:name zone) date)
  (conman/with-transaction [parky.db.core/*db*]
                           (let [pendings (db/get-pending-parkings-by-day {:tenant_id (get-in *identity* [:tenant :id])
                                                                           :parking_day date
                                                                           :parking_zone (:zone zone)
                                                                           :parking_name (:name zone)})]
                             (when (seq pendings)
                               (let [more-users-than-slots? (> (count pendings) slots-count)
                                     winners (compute-winners pendings zone date slots-count)]
                                 (activate-winners zone date more-users-than-slots? winners (get-slots date zone) true false)))))
  (log/info "End Computing" (:zone zone) (:name zone) date))

(defn compute-now []
  (try
    (let [local-time (LocalTime/now (ZoneId/of "UTC"))
          current-seconds (+ (* 3600 (.getHour local-time)) (* 60 (.getMinute local-time)) (.getSecond local-time))
          date (jt/plus (jt/local-date) (jt/days 1))]
      (doseq [tenant-id (db/get-all-computable-tenants-id {:bang_seconds_utc   current-seconds
                                                           :computed_date date})]
        (let [tenant (db/get-whole-tenant-by-id tenant-id)
              settings (edn/read-string (:settings tenant))]
          (binding [*identity* {:tenant (merge tenant {:settings settings})}]
            (doseq [zone (:zones settings)]
              (let [slots-count (count (get-slots date zone))]
                (compute zone date slots-count)))
            (db/update-tenant-dates! {:computed_date date
                                      :bang_seconds_utc (- (+ (* 3600 (Integer/valueOf (get settings :bang-hour 16))) (* 60 (Integer/valueOf (get settings :bang-minute 0)))) (.getTotalSeconds (.getOffset (.getRules (ZoneId/of (get settings :timezone "Europe/Berlin"))) (LocalDateTime/now (ZoneId/of "UTC")))))
                                      :tenant_id (:id tenant)})))))
    (catch Exception e
      (log/error e))))
