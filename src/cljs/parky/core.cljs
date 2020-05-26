(ns parky.core
  (:require
    [goog.string :as gstring]
    [goog.string.format]
    [reagent.core :as r]
    [reagent.dom :as d]
    [goog.events :as events]
    [goog.history.EventType :as HistoryEventType]
    [parky.ajax :as ajax]
    [ajax.core :refer [GET POST]]
    [reitit.core :as reitit]
    [clojure.string :as string]
    [cljs-time.format :as f]
    [luminus-transit.time :as lt]
    [cljs-time.core :as t]
    [bulma-toast :as toast]
    [compact-uuids.core :as uuid]
    ["@hcaptcha/react-hcaptcha" :as Hcaptcha]
    [goog.functions :refer [debounce]])
  (:import goog.History))

;; only page should be a global state, others are local state of calendar
(defonce session (r/atom {:page          :home
                          :zone          nil
                          :parking       nil
                          :show-days-count (js/parseInt (or (.getItem (.-localStorage js/window) "days") 2))
                          :current-date (t/today)
                          :data {}
                          :show-select nil
                          :show-spinner false
                          :show-admin false
                          :show-confirm nil
                          :types nil
                          :created false}))
(defonce conf (r/atom {}))
(defonce score (r/atom {:data nil
                        :days-look-back 30}))
(defonce analytics (r/atom {:current-date (t/first-day-of-the-month- (t/today))
                            :data nil}))
(defonce settings (r/atom {:timezones []
                           :settings []}))

(defn empty-to-nil [val]
  (if (clojure.string/blank? val) nil val)) ;; TODO: how to fix that?

(defn is-valid-email [email]
  (some? (re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$" (if (nil? email) "" email))))

(defn is-valid-optional-email [email]
  (some? (re-matches #"^([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63})?$" (if (nil? email) "" email))))

(defn is-valid-emails [emails]
  (some? (re-matches #"^([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}[\s,;]*)*$" (if (nil? emails) "" emails))))

(defn- is-fn-bang-hour? [date bang-fn]
  (let [bang-seconds-utc (get @conf :bang-seconds-utc (* 3600 16))]
    (bang-fn (t/now) (t/plus (t/from-utc-time-zone date) (t/seconds bang-seconds-utc)))))

(defn is-before-bang-hour? [date]
  (is-fn-bang-hour? date t/before?))

(defn is-after-bang-hour? [date]
  (is-fn-bang-hour? date t/after?))

(defn- filter-days [disabled-days]
  (into #{} (remove nil? (map-indexed #(when %2 (inc %1)) (or disabled-days (vec (repeat 7 false)))))))

(defn- jump-to-day [from days direction-fn filter-fn]
  (last (take days (remove nil? (remove #(filter-fn %) (iterate #(direction-fn % (t/days 1)) (direction-fn from (t/days 1))))))))

(defn error-handler [response]
  (if (#{401} (:status response))
    (set! (.-location js/window) "/")
    (when-not (#{-1} (:status response))
      (toast/toast (clj->js {:message (case (:status response)
                                        403 "Forbidden"
                                        409 "Conflict"
                                        "Unexpected server error")
                             :type "is-danger"
                             :position "bottom-right"
                             :duration 4000
                             :dismissible true
                             :animate { :in "fadeIn", :out "fadeOut"}})))))

(defn logout! []
  (POST "/logout" {:handler (fn [response]
                              (set! js/user nil)
                              (set! (.-location js/window) "/"))
                   :error-handler error-handler}))

(defn- show-spinner []
  (swap! session assoc :show-spinner true))

(defn- hide-spinner []
  (swap! session assoc :show-spinner false))

(defn default-handler [data]
  (swap! session assoc-in [:data :days] (merge (get-in @session [:data :days]) (:days data)))
  (swap! session assoc-in [:data :own-default] (:own-default data)))


(defn admin-add-info! [parking-zone parking-name date event-name description callback]
  (show-spinner)
  (POST (str "/admin/add-info/" parking-zone "/" parking-name "/" date) {:params {:event-name event-name
                                                                                  :description description}
                                                                         :handler default-handler
                                                                         :error-handler error-handler
                                                                         :finally (fn []
                                                                                    (hide-spinner)
                                                                                    (callback))}))

(defn admin-delete-info! [parking-zone parking-name date event-name callback]
  (show-spinner)
  (POST (str "/admin/delete-info/" parking-zone "/" parking-name "/" date) {:params {:event-name event-name}
                                                                            :handler default-handler
                                                                            :error-handler error-handler
                                                                            :finally (fn []
                                                                                       (hide-spinner)
                                                                                       (callback))}))

(defn admin-activate! [parking-zone parking-name date user-name email slot-name]
  (show-spinner)
  (POST (str "/admin/activate/" parking-zone "/" parking-name "/" date) {:params {:user-name user-name
                                                                                     :email email
                                                                                     :context-email (get-in @session [:on-behalf-of :email] (goog.object/get js/user "email"))
                                                                                     :slot-name slot-name}
                                                                            :handler default-handler
                                                                            :error-handler error-handler
                                                                            :finally hide-spinner}))

(defn admin-cancel-out-of-office! [parking-zone parking-name date user-name email slot-name]
  (show-spinner)
  (POST (str "/admin/cancel-out-of-office/" parking-zone "/" parking-name "/" date) {:params {:user-name user-name
                                                                                                 :email email
                                                                                                 :context-email (get-in @session [:on-behalf-of :email] (goog.object/get js/user "email"))
                                                                                                 :slot-name slot-name}
                                                                                        :handler default-handler
                                                                                        :error-handler error-handler
                                                                                        :finally hide-spinner}))

(defn admin-show-select [parking-zone parking-name date user-name email]
  (show-spinner)
  (GET (str "/admin/show-select/" parking-zone "/" parking-name "/" date) {:params {:user-name user-name
                                                                                       :email email
                                                                                       :context-email (get-in @session [:on-behalf-of :email] (goog.object/get js/user "email"))}
                                                                              :handler #(swap! session assoc :show-select %)
                                                                              :error-handler error-handler
                                                                              :finally hide-spinner}))

(defn admin-reserve! [parking-zone parking-name date user-name email]
  (show-spinner)
  (POST (str "/admin/reserve/" parking-zone "/" parking-name "/" date) {:params {:user-name user-name
                                                                                    :email email
                                                                                    :context-email (get-in @session [:on-behalf-of :email] (goog.object/get js/user "email"))}
                                                                           :handler default-handler
                                                                           :error-handler error-handler
                                                                           :finally hide-spinner}))
(defn admin-unblock! [parking-zone parking-name date user-name email]
  (show-spinner)
  (POST (str "/admin/unblock/" parking-zone "/" parking-name "/" date) {:params {:user-name user-name
                                                                                    :email email
                                                                                    :context-email (get-in @session [:on-behalf-of :email] (goog.object/get js/user "email"))}
                                                                           :handler default-handler
                                                                           :error-handler error-handler
                                                                           :finally hide-spinner}))

(defn admin-deactivate! [parking-zone parking-name date user-name email]
  (show-spinner)
  (POST (str "/admin/deactivate/" parking-zone "/" parking-name "/" date) {:params {:user-name user-name
                                                                                       :email email
                                                                                       :context-email (get-in @session [:on-behalf-of :email] (goog.object/get js/user "email"))}
                                                                              :handler default-handler
                                                                              :error-handler error-handler
                                                                              :finally hide-spinner}))

(defn reserve! [parking-zone parking-name date type]
  (show-spinner)
  (POST (str "/reserve/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:params (merge (:on-behalf-of @session) {:type type})
                                                                                                              :handler default-handler
                                                                                                              :error-handler error-handler
                                                                                                              :finally hide-spinner}))
(defn confirm [header text on-success]
  (swap! session assoc :show-confirm {:header header
                                      :text text
                                      :fn on-success}))

(defn cancel! [parking-zone parking-name date]
  (confirm "Cancel reservation" "Do you really want to cancel the reservation?"
    (fn []
      (show-spinner)
      (POST (str "/cancel/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:params (:on-behalf-of @session)
                                                                                                                 :handler default-handler
                                                                                                                 :error-handler error-handler
                                                                                                                 :finally hide-spinner}))))

(defn deactivate! [parking-zone parking-name date]
  (confirm "Give up" "Do you really want to give up on your space? You will be given 1 point back and others will be notified that they can reserve the space."
    (fn []
      (show-spinner)
      (POST (str "/deactivate/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:params (:on-behalf-of @session)
                                                                                                                     :handler default-handler
                                                                                                                     :error-handler error-handler
                                                                                                                     :finally hide-spinner}))))

(defn set-out-of-office! [parking-zone parking-name date]
  (confirm "Set out of office" "Are you sure that you want to give up your space for this day? You will be awarded 3 points and anyone can use it that day."
           (fn []
             (show-spinner)
             (POST (str "/outofoffice/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:params (:on-behalf-of @session)
                                                                                                                             :handler default-handler
                                                                                                                             :error-handler error-handler
                                                                                                                             :finally hide-spinner}))))


(defn cancel-out-of-office! [parking-zone parking-name date]
  (confirm "Cancel out of office" "Are you sure to take back your space?"
           (fn []
             (show-spinner)
             (POST (str "/cancel-outofoffice/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:params (:on-behalf-of @session)
                                                                                                                                    :handler default-handler
                                                                                                                                    :error-handler error-handler
                                                                                                                                    :finally hide-spinner}))))


(defonce current-fetch-request (atom nil))

(defn fetch-current-days! [parking-zone parking-name date days]
  (when @current-fetch-request
    (.abort @current-fetch-request))
  (show-spinner)
  (reset! current-fetch-request (GET (str "/days/" days "/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:params (:on-behalf-of @session)
                                                                                                                                                 :handler (fn [data]
                                                                                                                                                            (swap! session assoc-in [:data :days] (:days data))
                                                                                                                                                            (swap! session assoc-in [:data :own-default] (:own-default data))
                                                                                                                                                            (swap! session assoc :show-days-count days)
                                                                                                                                                            (swap! session assoc :current-date date))
                                                                                                                                                 :error-handler error-handler
                                                                                                                                                 :finally hide-spinner})))
(defn fetch-analytics! [parking-zone parking-name date]
  (when @current-fetch-request
    (.abort @current-fetch-request))
  (show-spinner)
  (reset! current-fetch-request (GET (str "/analytics/" parking-zone "/" parking-name "/" (f/unparse-local-date lt/iso-local-date date)) {:handler (fn [data]
                                                                                                                                                     (swap! analytics assoc :data data))
                                                                                                                                          :error-handler error-handler
                                                                                                                                          :finally hide-spinner})))

(defn fetch-score! [parking-zone date]
  (when @current-fetch-request
    (.abort @current-fetch-request))
  (show-spinner)
  (reset! current-fetch-request (GET (str "/score/" parking-zone "/" date "/" (or (:days-look-back @score) 30)) {:handler #(swap! score assoc :data %)
                                                                                                                 :error-handler error-handler
                                                                                                                 :finally hide-spinner})))

(defn create-new! [name email token]
  (show-spinner)
  (GET "/create-new" {:params {:name name
                               :email email
                               :token token}
                      :handler #(set! (.-location js/window) (str "//" name "/#created"))
                      :error-handler error-handler
                      :finally hide-spinner}))

(defn create-login-token! [user-name email token handler]
  (show-spinner)
  (GET "/create-login-token" {:params {:state (js/encodeURIComponent js/sessionState)
                                       :user-name user-name
                                       :email email
                                       :token token}
                              :handler handler
                              :error-handler error-handler
                              :finally hide-spinner}))

(defn- check-name-available! [name avail-atom]
  (GET "/available" {:params {:name name}
                     :handler #(reset! avail-atom (:data %))
                     :error-handler error-handler}))

(def check-name-available
  (debounce check-name-available! 1000))

(defn fetch-timezones! []
  (GET (str "/timezones") {:handler (fn [data]
                                      (swap! settings assoc :timezones data))
                           :error-handler error-handler}))

(defn fetch-settings! []
  (GET "/settings" {:handler (fn [data]
                                (swap! settings assoc :settings (if (seq data) data [])))
                    :error-handler error-handler}))

(defn save-settings! [settings]
  (show-spinner)
  (POST "/settings" {:params {:settings settings}
                     :handler (fn [data]
                                (toast/toast (clj->js {:message "Saved"
                                                        :type "is-success"
                                                        :position "bottom-right"
                                                        :duration 4000
                                                        :dismissible true
                                                        :animate { :in "fadeIn", :out "fadeOut"}})))
                     :error-handler error-handler
                     :finally hide-spinner}))

(defn logout-all-users! []
  (show-spinner)
  (POST "/logout-all-users" {:handler (fn [_]
                                        (toast/toast (clj->js {:message "OK"
                                                               :type "is-success"
                                                               :position "bottom-right"
                                                               :duration 4000
                                                               :dismissible true
                                                               :animate { :in "fadeIn", :out "fadeOut"}})))
                             :error-handler error-handler
                             :finally hide-spinner}))

(defn fetch-types! [parking-zone parking-name callback]
  (GET (str "/types/" parking-zone "/" parking-name) {:handler (fn [data]
                                                                 (swap! session assoc :types data)
                                                                 (callback data))
                                                      :error-handler error-handler}))

(defn fetch-conf! []
  (GET "/conf" {:handler #(reset! conf %)
                :error-handler error-handler}))

(defn show-types [parking-zone parking-name callback]
  (let [dispatch (fn []
                   (if (seq (:types @session))
                     (swap! session assoc :show-types callback)
                     (callback nil)))]
    (if (nil? (:types @session))
      (fetch-types! parking-zone parking-name dispatch)
      (dispatch))))

(defn nav-link [expanded? uri title page]
  [:a.navbar-item
   {:href   uri
    :class (when (= page (:page @session)) "is-active")
    :on-click #(swap! expanded? not)}
   title])

(defn nav-calendar-dropdown [expanded? pages]
  (when js/user
    (let [is-active? (and (= (:page @session) :calendar) (:zone @session) (:parking @session))]
      [:div.navbar-item.has-dropdown.is-hoverable
       [:a.navbar-link {:class (when is-active? "is-active")} (if is-active? (str "Calendar: " (:zone @session) " - " (:parking @session)) "Calendar")]
       [:div.navbar-dropdown
        (doall (map (fn [[zone parking]] [:a.navbar-item
                                          {:key (str zone "-" parking)
                                           :href   (str "#/calendar/" zone "/" parking)
                                           :class (when (and is-active? (= zone (:zone @session)) (= parking (:parking @session))) "is-active")
                                           :on-click #(do
                                                        (swap! session assoc :types nil)
                                                        (swap! session assoc :show-admin false)
                                                        (fetch-current-days! zone parking (:current-date @session) (:show-days-count @session))
                                                        (swap! expanded? not))}
                                          zone " - " parking]) pages))]])))

(defn nav-analytics-dropdown [expanded? pages]
  (when js/user
    (let [is-active? (and (= (:page @session) :analytics) (:zone @session) (:parking @session))]
      [:div.navbar-item.has-dropdown.is-hoverable
       [:a.navbar-link {:class (when is-active? "is-active")} (if is-active? (str "Analytics: " (:zone @session) " - " (:parking @session)) "Analytics")]
       [:div.navbar-dropdown
        (doall (map (fn [[zone parking]] [:a.navbar-item
                                          {:key (str zone "-" parking)
                                           :href   (str "#/analytics/" zone "/" parking)
                                           :class (when (and is-active? (= zone (:zone @session)) (= parking (:parking @session))) "is-active")
                                           :on-click #(do
                                                        (swap! session assoc :types nil)
                                                        (swap! session assoc :show-admin false)
                                                        (fetch-analytics! zone parking (or (:current-date @analytics) (t/first-day-of-the-month- (t/now))))
                                                        (swap! expanded? not))}
                                          zone " - " parking]) pages))]])))

(defn nav-score-dropdown [expanded? zones]
  (when js/user
    (let [is-active? (and (= (:page @session) :score) (:zone @score))]
      [:div.navbar-item.has-dropdown.is-hoverable
       [:a.navbar-link {:class (when is-active? "is-active")} (if is-active? (str "Score: " (:zone @score)) "Score")]
       [:div.navbar-dropdown
        (doall (map (fn [zone] [:a.navbar-item
                                  {:key zone
                                   :href   (str "#/score/" zone)
                                   :class (when (and is-active? (= zone (:zone @score)) "is-active"))
                                   :on-click #(do
                                                (swap! score assoc :data nil)
                                                (fetch-score! zone (f/unparse-local-date lt/iso-local-date (t/today)))
                                                (swap! expanded? not))}
                                  zone]) zones))]])))

(defn navbar []
  (r/with-let [expanded? (r/atom false)]
    [:nav.navbar.is-info>div.container
     [:div.navbar-brand
      [:a.navbar-item.has-background-warning.has-text-black {:href "/"
                                                             :style {:font-weight :bold
                                                                     :padding "0.5rem"}} js/appName]
      (when js/subtenants
        (doall (map (fn [e] [:a.navbar-item {:class (when (= js/window.location.host (val e)) :is-active)
                                             :href (str "//" (val e)) :style {:font-weight :bold
                                                                              :padding "0.5rem"}} (key e)]) (js->clj js/subtenants))))
      (when (and (= :calendar (:page @session)) (or (:is-admin @conf) (contains? (get-in @conf [:is-admin-in (:zone @session)]) (:parking @session))))
         [:a.navbar-item
           [:span.button {:class    (if (:show-admin @session) :is-warning :is-info)
                          :on-click (fn [_]
                                      (swap! session assoc :show-admin (not (:show-admin @session)))
                                      (swap! session assoc :on-behalf-of {})
                                      (swap! session assoc :show-emails false)
                                      (fetch-current-days! (:zone @session) (:parking @session) (:current-date @session) (:show-days-count @session)))}
            [:i.material-icons "supervisor_account"]]])
      [:span.navbar-item.is-hidden-desktop (when (= (:page @session) :calendar)
                                             (str "@" (:zone @session) "-" (:parking @session)))]
      (when js/visitor [:span.navbar-item "visitor"])
      (when (:show-spinner @session)
        [:div.lds-ring [:div] [:div] [:div] [:div]])
      [:span.navbar-burger.burger
       {:data-target :nav-menu
        :on-click #(swap! expanded? not)
        :class (when @expanded? :is-active)}
       [:span][:span][:span]]]
     [:div#nav-menu.navbar-menu
      {:class (when @expanded? :is-active)}
      [:div.navbar-end
       (when (not= :new (:page @session)) [nav-link expanded? "#/" "Home" :home])
       [nav-calendar-dropdown expanded? (:zones @conf)]
       [nav-score-dropdown expanded? (distinct (map first (:zones @conf)))]
       (when-not js/visitor
         [nav-analytics-dropdown expanded? (:zones @conf)])
       (when (goog.object/get js/user "is-admin")
         [nav-link expanded? "#/settings" "Settings" :settings])]]]))


(defn rules-page []
  [:section.section>div.container>div.content
   [:div.container>div.notification.has-background-snow
    [:h2.subtitle "Rules"]
    [:ul
     [:li js/appName " decides who will park their butt every day at " (quot (:bang-seconds-utc @conf) 3600) ":" (gstring/format "%02d" (quot (mod (:bang-seconds-utc @conf) 3600) 60)) " GMT/UTC."]
     [:li "You can apply for space before that time by clicking a date cell in the calendar. The date cell then turns yellow."]
     [:li js/appName " assigns the parking space to a person who got space assigned the least in the last month (i.e. whoever has the smallest number of points). For that person the date cell turns green."
      [:ul
       [:li "Every time you get a space, you get 5 points."]
       [:li "In addition, if there is a “competition”, you get 2 extra points. Competition means that number of interested users is bigger then number of available spaces."]
       [:li "If there are still free spaces for the current day, you get the space immediately after clicking the date cell (and you get the 5 points)."]]]
     [:li "It is not possible to remove old records (whoever clicked and got the space, will remain in the calendar, regardless of whether they used the space or not)."]
     [:li "However, you can remove the currently won (or future) record. In this case, you are given 1 point back and others (who were interested in space that day) are informed by email that there still might be a free space."]
     [:li "If you are planning to use the space just for a half day, tell others in your organization, so that someone else can share the space with you. It will improve your karma. :-)"]]]])

(defn privacy-page []
  [:section.section>div.container>div.content
   [:h2.subtitle "Privacy"]
   [:div.container>div.notification.has-background-snow
    [:ul
     [:li js/appName " stores your email and your name in a local database."]]]])

(defn terms-page []
  [:section.section>div.container>div.content
   [:h2.subtitle "Terms"]
   [:div.container>div.notification.has-background-snow
    [:ul
     [:li "By using " js/appName " you are giving us an option for your soul."]]]])

(defn- disabled-days-on-change [day zone-id]
  (fn [e]
    (let [current-settings (vec (get-in @settings [:settings :zones zone-id :disabled-days] (repeat 7 false)))
          new-settings (assoc current-settings day (not (nth current-settings day)))]
      (swap! settings assoc-in [:settings :zones zone-id :disabled-days] (if (= new-settings (repeat 7 true)) (repeat 7 false) new-settings)))))

(defn settings-page []
  [:div
   [:section.section>div.container>div.content
    [:h1 "Admin actions"]
    [:button.button.is-danger {:on-click logout-all-users!} "Logout all users"]]
   [:section.section.has-background-snow>div.container>div.content
    [:h1 "Settings"]
    [:div.field.is-grouped.is-grouped-centered
     [:p.control
      [:button.button.is-success {:on-click #(save-settings! (:settings @settings))} "Save"]]]
    [:h2.subtitle "App admin"]
    [:input.input {:on-change #(swap! settings assoc-in [:settings :admin] (empty-to-nil (-> % .-target .-value)))
                   :placeholder "Email"
                   :maxLength 255
                   :class [(when (not (is-valid-email (get-in @settings [:settings :admin]))) :is-danger)]
                   :value (get-in @settings [:settings :admin])}]
    [:h2.subtitle "Timezone"]
    [:div.select [:select {:on-change #(swap! settings assoc-in [:settings :timezone] (-> % .-target .-value))
                           :value (get-in @settings [:settings :timezone] "Europe/Berlin")}
                  (map (fn [item]
                         (let [[offset z] item]
                           [:option {:key z :value z} offset " " z])) (:timezones @settings))]]
    [:h2.subtitle "Bang-hour"]
    [:div.select [:select {:on-change #(swap! settings assoc-in [:settings :bang-hour] (-> % .-target .-value))
                           :value (get-in @settings [:settings :bang-hour] 16)}
                  (map (fn [i] [:option {:key i} i]) (range 0 24))]]
    [:div.select [:select {:on-change #(swap! settings assoc-in [:settings :bang-minute] (-> % .-target .-value))
                           :value (get-in @settings [:settings :bang-minute] 0)}
                  (map (fn [i] [:option {:key i} i]) (range 0 60 5))]]
    [:h2.subtitle "Allow visitors"]
    [:label.checkbox [:input {:on-change #(swap! settings assoc-in [:settings :allow-visitors] (not (get-in @settings [:settings :allow-visitors])))
                              :type :checkbox
                              :checked (get-in @settings [:settings :allow-visitors])}] " Check to allow visitors to log in"]
    [:h2.subtitle "Allowed user email suffixes"]
    [:input.input {:on-change #(swap! settings assoc-in [:settings :allowed-email-suffixes] (empty-to-nil (-> % .-target .-value)))
                   :placeholder "@gmail.com @outlook.com"
                   :maxLength 255
                   :value (get-in @settings [:settings :allowed-email-suffixes])}]
    [:h2.subtitle "Allowed user emails"]
    [:input.input {:on-change #(swap! settings assoc-in [:settings :allowed-emails] (empty-to-nil (-> % .-target .-value)))
                   :placeholder "jane.doe@example.com john.doe@example.com"
                   :class [(when (not (is-valid-emails (get-in @settings [:settings :allowed-emails]))) :is-danger)]
                   :maxLength 255
                   :value (get-in @settings [:settings :allowed-emails])}]

    [:h2.subtitle "Areas"]
    [:button.button.is-success {:on-click #(swap! settings assoc-in [:settings :zones] (vec (cons {:zone "new" :name (uuid/str (random-uuid)) :slots [{:name (uuid/str (random-uuid))}]} (get-in @settings [:settings :zones]))))} "Add"]
    (doall (map-indexed (fn [idx zone]
                          [:div {:key idx}
                               [:div.container>div.notification.field
                                [:button.button.is-danger {:on-click (fn [] (swap! settings assoc-in [:settings :zones] (vec (map second (remove #(= idx (first %)) (map-indexed (fn [idx item] [idx item]) (get-in @settings [:settings :zones])))))))} "Remove card"]
                                [:div.field [:label.label "Area"]
                                 [:input.input {:maxLength 32
                                                :on-change #(swap! settings assoc-in [:settings :zones idx :zone] (-> % .-target .-value))
                                                :value (:zone zone)}]]
                                [:div.field [:label.label "Name"]
                                 [:input.input {:maxLength 32
                                                :on-change #(swap! settings assoc-in [:settings :zones idx :name] (-> % .-target .-value))
                                                :value (:name zone)}]]
                                [:div.field [:label.label "Info Link"]
                                 [:input.input {:on-change #(swap! settings assoc-in [:settings :zones idx :map] (empty-to-nil (-> % .-target .-value)))
                                                :value (:map zone)}]]
                                [:div.field [:label.label "Admins"]
                                 [:input.input {:placeholder "Emails"
                                                :class [(when (not (is-valid-emails (get-in @settings [:settings :zones idx :admins]))) :is-danger)]
                                                :on-change #(swap! settings assoc-in [:settings :zones idx :admins] (empty-to-nil (-> % .-target .-value)))
                                                :value (:admins zone)}]]
                                [:div.field [:label.label "MS Teams notifications (Incoming webhook) URL"]
                                 [:input.input {:placeholder "https://outlook.office.com/webhook/......"
                                                :on-change #(swap! settings assoc-in [:settings :zones idx :teams-hook-url] (empty-to-nil (-> % .-target .-value)))
                                                :value (:teams-hook-url zone)}]]
                                [:div.field [:label.label "Slack notifications (Incoming webhook) URL"]
                                 [:input.input {:placeholder "https://hooks.slack.com/services/......"
                                                :on-change #(swap! settings assoc-in [:settings :zones idx :slack-hook-url] (empty-to-nil (-> % .-target .-value)))
                                                :value (:slack-hook-url zone)}]]
                                [:div.field [:label.label "Disabled days"]
                                 (doall (for [[day day-name] [[0 " Monday "] [1 " Tuesday "] [2 " Wednesday "] [3 " Thursday "] [4 " Friday "] [5 " Saturday "] [6 " Sunday "]]]
                                          [:label.checkbox {:key day
                                                            :style {:margin-right "1rem"}} [:input {:on-change (disabled-days-on-change day idx)
                                                                                                    :type :checkbox
                                                                                                    :checked (nth (get-in @settings [:settings :zones idx :disabled-days] (vec (repeat 7 false))) day)}] day-name]))]
                                [:div.field [:label.label "Look back days"]
                                 [:input.input {:placeholder "30"
                                                :maxLength 3
                                                :on-change #(swap! settings assoc-in [:settings :zones idx :days-look-back] (empty-to-nil (-> % .-target .-value)))
                                                :value (:days-look-back zone)}]]
                                [:div.field [:label.label "Slots"]
                                 [:div.columns
                                  [:div.column.is-one-fourth
                                   [:label.label "Name"]]
                                  [:div.column.is-one-fourth
                                   [:label.label "Owner"]]
                                  [:div.column.is-one-fourth
                                   [:label.label "Types (Tags)"]]
                                  [:div.column.is-one-fourth
                                   [:label.label "Actions"]]]
                                 (doall (map-indexed (fn [i slot]
                                                       [:div.columns {:key i}
                                                        [:div.column.is-one-fourth
                                                         [:input.input {:placeholder "Name"
                                                                        :on-change #(swap! settings assoc-in [:settings :zones idx :slots i :name] (-> % .-target .-value))
                                                                        :value (:name slot)
                                                                        :maxLength 32}]]
                                                        [:div.column.is-one-fourth
                                                         [:input.input {:placeholder "Email"
                                                                        :maxLength 255
                                                                        :class [(when (not (is-valid-optional-email (get-in @settings [:settings :zones idx :slots i :owner]))) :is-danger)]
                                                                        :on-change #(swap! settings assoc-in [:settings :zones idx :slots i :owner] (empty-to-nil (-> % .-target .-value)))
                                                                        :value (:owner slot)}]]
                                                        [:div.column.is-one-fourth
                                                         [:input.input {:placeholder "List of tags"
                                                                        :maxLength 32
                                                                        :on-change #(swap! settings assoc-in [:settings :zones idx :slots i :types] (empty-to-nil (-> % .-target .-value)))
                                                                        :value (:types slot)}]]
                                                        [:div.column.is-one-fourth
                                                         [:button.button.is-danger {:on-click (fn [] (swap! settings assoc-in [:settings :zones idx :slots] (vec (map second (remove #(= i (first %)) (map-indexed (fn [ix item] [ix item]) (get-in @settings [:settings :zones idx :slots])))))))} "Delete"] " "
                                                         [:button.button.is-success {:on-click (fn [] (swap! settings assoc-in [:settings :zones idx :slots] (vec (map second (let [pn (partition-by #(<= (inc i) (first %)) (map-indexed (fn [ix item] [ix item]) (get-in @settings [:settings :zones idx :slots])))] (concat (first pn) [[0 {:name (uuid/str (random-uuid))}]] (second pn)))))))} "Add after"]]]) (:slots zone)))]]
                               [:br]]) (get-in @settings [:settings :zones])))]])

(defn change-date [op-fn cnt]
  (let [date (jump-to-day
               (:current-date @session)
               cnt
               op-fn
               #((filter-days (get-in @conf [:disabled-days (:zone @session) (:parking @session)])) (t/day-of-week %)))]

    (swap! session assoc :current-date date)
    (fetch-current-days! (:zone @session) (:parking @session) date (:show-days-count @session))))

(defn change-date-to-today []
  (swap! session assoc :current-date (t/today))
  (fetch-current-days! (:zone @session) (:parking @session) (t/today) (:show-days-count @session)))

(defn change-date-analytics [op-fn cnt]
  (let [date (op-fn (or (:current-date @analytics) (t/first-day-of-the-month- (t/now)))
                    (t/months cnt))]
    (swap! analytics assoc :current-date date)
    (fetch-analytics! (:zone @session) (:parking @session) date)))

(defn change-date-to-today-analytics []
  (swap! analytics assoc :current-date (t/first-day-of-the-month- (t/today)))
  (fetch-analytics! (:zone @session) (:parking @session) (t/first-day-of-the-month- (t/today))))

(defn dow [date]
  (case (t/day-of-week date)
    1 "Monday"
    2 "Tuesday"
    3 "Wednesday"
    4 "Thursday"
    5 "Friday"
    6 "Saturday"
    7 "Sunday"))

(defn input-element
  "An input element which updates its value on change"
  [id name type placeholder maxlength value]
  [:input {:id id
           :name name
           :class :input
           :type type
           :required true
           :value @value
           :placeholder placeholder
           :maxLength maxlength
           :on-change #(reset! value (-> % .-target .-value))}])

(defn textarea-element
  "Text area element which updates its value on change"
  [id name placeholder maxlength value]
  [:textarea {:id id
              :name name
              :class :textarea
              :required true
              :value @value
              :placeholder placeholder
              :maxLength maxlength
              :on-change #(reset! value (-> % .-target .-value))}])

(defn select-types-input
  [types select-atom]
  [:div.select [:select {:id :show-types
                         :name :show-types
                         :on-change #(reset! select-atom (empty-to-nil (-> % .-target .-value)))}
                [:option {:value ""} "Any"]
                (doall (for [type types]
                         [:option {:key type
                                   :value type} type]))]])

(defn select-input
  [spaces select-atom]
  [:div.select [:select {:id :show-select
                         :name :show-select
                         :on-change #(reset! select-atom (empty-to-nil (-> % .-target .-value)))}
                [:option {:value ""} "Automatically"]
                (doall (for [space (:slot-names spaces)]
                         [:option {:key space
                                   :value space} space]))]])


(defn email-input
  [email-atom]
  [:input {:id :email
           :name :email
           :class [:input (when (not (is-valid-email @email-atom)) :is-danger)]
           :type :email
           :required true
           :value @email-atom
           :placeholder "Email"
           :on-change #(reset! email-atom (-> % .-target .-value))}])

(defn user-name-input
  [user-name-atom]
  (input-element "user-name" "user-name" "text" "User Name" 32 user-name-atom))

(defn switch-to-days [days]
  (fn [_]
    (.setItem (.-localStorage js/window) "days" days)
    (swap! session assoc :show-days-count days)
    (fetch-current-days! (:zone @session) (:parking @session) (:current-date @session) days)))

(defn- get-visible-date-offsets [parking-zone parking-name]
  (let [m (atom [])
        idx (atom 0)
        disabled-days (get-in @conf [:disabled-days parking-zone parking-name] (vec (repeat 7 false)))]
    (while (> (:show-days-count @session) (count @m))
      (let [the-date (t/plus (:current-date @session) (t/days @idx))]
        (when-not (nth disabled-days (dec (t/day-of-week the-date)))
          (swap! m conj @idx))
        (swap! idx inc)))
    @m))



(defn calendar [parking-zone parking-name date]
  [:div.columns
   [:div.column {:style {:padding-left "0.25rem" :padding-right "0.25rem"}}
    [:div.level.calendar-menu
     [:div.level-left
      [:div.level-item
       [:div.buttons.has-addons
        [:span.button {:on-click #(change-date t/minus (:show-days-count @session))} "Prev"]
        [:span.button {:on-click #(change-date t/minus 1)} [:i.material-icons "chevron_left"]]
        [:span.button {:on-click #(change-date-to-today)} "Today"]
        [:span.button {:on-click #(change-date t/plus 1)} [:i.material-icons "chevron_right"]]
        [:span.button {:on-click #(change-date t/plus (:show-days-count @session))} "Next"]]]]

     (when (:show-admin @session)
       (r/with-let [active-modal (r/atom false)
                    user-name (r/atom nil)
                    email (r/atom nil)]
                   [:div.level-left
                    [:div.level-item
                     [:div.button {:data-tooltip "Set on behalf of which user will you act"
                                   :class [:tooltip :is-tooltip-black]
                                   :on-click (fn [_]
                                               (if (get-in @session [:on-behalf-of :email])
                                                 (do
                                                   (reset! user-name nil)
                                                   (reset! email nil)
                                                   (swap! session assoc :on-behalf-of {})
                                                   (fetch-current-days! parking-zone parking-name (:current-date @session) (:show-days-count @session)))
                                                 (reset! active-modal true)))} "On behalf of " (get-in @session [:on-behalf-of :user-name])
                      [:i.material-icons (if (get-in @session [:on-behalf-of :email]) "delete" "edit")]]
                     [:div.modal {:class (if @active-modal :is-active)}
                      [:div.modal-background]
                      [:div.modal-card
                       [:header.modal-card-head
                        [:p.modal-card-title "Set on behalf of"]
                        [:button.delete {:aria-label "close"
                                         :on-click #(reset! active-modal false)}]]
                       [:section.modal-card-body
                        [:div.field
                         [:div.control
                          [user-name-input user-name]]]
                        [:div.field
                         [:div.control
                          [email-input email]]]]
                       [:footer.modal-card-foot
                        [:button.button {:class    (if (is-valid-email @email) :is-success :is-danger)
                                         :on-click (fn [_]
                                                     (swap! session assoc :on-behalf-of {:user-name @user-name
                                                                                         :email     @email})
                                                     (fetch-current-days! parking-zone parking-name (:current-date @session) (:show-days-count @session))
                                                     (reset! active-modal false))} "Set"]
                        [:button.button {:on-click #(reset! active-modal false)} "Close"]]]]]]))

     (when (:show-admin @session)
       [:div.level-left
        [:div.level-item
         [:span.button {:data-tooltip "Show users' emails/ids"
                        :class    [:tooltip :is-tooltip-black (when (:show-emails @session) :is-info)]
                        :on-click #(swap! session assoc :show-emails (not (:show-emails @session)))}
          [:i.material-icons "contact_mail"]]]])

     (when (:show-admin @session)
       (r/with-let [select-atom (r/atom nil)]
         [:div.modal {:class (when (:show-select @session) :is-active)}
          [:div.modal-background]
          [:div.modal-card
           [:header.modal-card-head
            [:p.modal-card-title "Select and activate free space"]
            [:button.delete {:on-click (fn [_]
                                         (swap! session assoc :show-select nil)
                                         (reset! select-atom nil))} {:aria-label "close"}]]
           [:section.modal-card-body
            [select-input (:show-select @session) select-atom]]
           [:footer.modal-card-foot
            [:button.button.is-success {:on-click (fn [_]
                                                    (admin-activate! (:zone @session) (get-in @session [:show-select :parking]) (get-in @session [:show-select :date]) (get-in @session [:show-select :user-name]) (get-in @session [:show-select :email]) @select-atom)
                                                    (reset! select-atom nil)
                                                    (swap! session assoc :show-select nil))} "Select"]
            [:button.button {:on-click (fn [_]
                                         (swap! session assoc :show-select nil)
                                         (reset! select-atom nil))} "Close"]]]]))

     (r/with-let [select-type-atom (r/atom nil)]
                 [:div.modal {:class (when (:show-types @session) :is-active)}
                  [:div.modal-background]
                  [:div.modal-card
                   [:header.modal-card-head
                    [:p.modal-card-title "Select preferred space type"]
                    [:button.delete {:on-click (fn [_]
                                                 (swap! session assoc :show-types nil))} {:aria-label "close"}]]
                   [:section.modal-card-body
                    [select-types-input (:types @session) select-type-atom]]
                   [:footer.modal-card-foot
                    [:button.button.is-success {:on-click (fn [_]
                                                            ((:show-types @session) @select-type-atom)
                                                            (swap! session assoc :show-types nil))} "Select"]
                    [:button.button {:on-click (fn [_]
                                                 (swap! session assoc :show-types nil))} "Close"]]]])

     (when (:show-admin @session)
       (r/with-let [event-name (r/atom nil)
                    description (r/atom nil)]
                   [:div.modal {:class (when (:add-event @session) :is-active)}
                    [:div.modal-background]
                    [:div.modal-card
                     [:header.modal-card-head
                      [:p.modal-card-title "Add event"]
                      [:button.delete {:aria-label "close"
                                       :on-click #(swap! session assoc :add-event nil)}]]
                     [:section.modal-card-body
                      [:div.field
                       [:div.control
                        [input-element "event-name" "event-name" "text" "Event Name" 32 event-name]]]
                      [:div.field
                       [:div.control
                        [textarea-element "event-description" "event-description" "Event Description" 255 description]]]]
                     [:footer.modal-card-foot
                      [:button.button {:class    (if (empty? @event-name) :is-danger :is-success)
                                       :on-click (fn [_]
                                                   (admin-add-info! parking-zone parking-name (get-in @session [:add-event :date]) @event-name @description #(fetch-current-days! parking-zone parking-name (:current-date @session) (:show-days-count @session)))
                                                   (reset! event-name nil)
                                                   (reset! description nil)
                                                   (swap! session assoc :add-event nil))} "Add"]
                      [:button.button {:on-click #(swap! session assoc :add-event nil)} "Close"]]]]))

     [:div.modal {:class (when (:show-confirm @session) :is-active)}
      [:div.modal-background]
      [:div.modal-card
       [:header.modal-card-head
        [:p.modal-card-title (get-in @session [:show-confirm :header] "Confirmation")]
        [:button.delete {:on-click #(swap! session assoc :show-confirm nil)} {:aria-label "close"}]]
       [:section.modal-card-body
        (:text (:show-confirm @session))]
       [:footer.modal-card-foot
        [:button.button.is-success {:on-click (fn [_]
                                                ((:fn (:show-confirm @session)))
                                                (swap! session assoc :show-confirm nil))} "Okay"]
        [:button.button {:on-click #(swap! session assoc :show-confirm nil)} "Close"]]]]

     (when-not (empty? (get-in @conf [:map-links parking-zone parking-name]))
       [:div.level-left
        [:div.level-item
         [:div.buttons.has-addons [:a.button {:target :_blank
                                              :href (get-in @conf [:map-links parking-zone parking-name]) } "Show Info"]]]])

     [:div.level-left
      [:div.level-item
       [:div.buttons.has-addons
        [:span.button (if (= 2 (:show-days-count @session))
                        {:class [:is-selected :is-info]}
                        {:on-click (switch-to-days 2)}) "2day"]
        [:span.button (if (= 5 (:show-days-count @session))
                        {:class [:is-selected :is-info]}
                        {:on-click (switch-to-days 5)}) "5day"]
        [:span.button (if (= 7 (:show-days-count @session))
                        {:class [:is-selected :is-info]}
                        {:on-click (switch-to-days 7)}) "7day"]
        [:span.button (if (= 9 (:show-days-count @session))
                        {:class [:is-selected :is-info]}
                        {:on-click (switch-to-days 9)}) "9day"]]]]]


    [:div.columns.is-gapless (when (= 2 (:show-days-count @session)) {:class :is-mobile})
     (doall (for [idx (get-visible-date-offsets parking-zone parking-name)]
              (let [the-date (t/plus (:current-date @session) (t/days idx))
                    formatted-date (f/unparse-local-date (:date f/formatters) the-date)
                    now-status (cond
                                 (and (is-after-bang-hour? (t/minus the-date (t/days 1))) (is-before-bang-hour? the-date)) :now-current
                                 (is-before-bang-hour? the-date) :now-new
                                 :else :now-old)
                    all-the-rows (group-by #(= (:status %) "info") (get-in @session [:data :days formatted-date :data]))
                    infos (get all-the-rows true)
                    rows (get all-the-rows false)]
                [:div.column {:key formatted-date}
                 (let [with-own (or (get-in @session [:data :days formatted-date :own]) (get-in @session [:data :own-default]))
                       with-present (filter #(and (#{"active" "pending" "inactive" "blocked"} (:status %)) (= (if (some? (get-in @session [:on-behalf-of :email])) (get-in @session [:on-behalf-of :email]) (goog.object/get js/user "email")) (:email %))) (get-in @session [:data :days formatted-date :data]))
                       with-active (filter #(= "active" (:status %)) with-present)
                       with-pending (filter #(= "pending" (:status %)) with-present)
                       with-inactive (filter #(= "inactive" (:status %)) with-present)
                       with-blocked (filter #(= "blocked" (:status %)) with-present)
                       css (flatten [(if (= :out with-own)
                                       "outday"
                                       (if (= :yes with-own)
                                         "activeday"
                                         [(when (seq with-active) "activeday")
                                          (when (seq with-pending) "pendingday")
                                          (when (seq with-inactive) "inactiveday")
                                          (when (seq with-blocked) "blockedday")]))
                                     now-status
                                     :tooltip :is-tooltip-black
                                     (if (#{6 7} (t/day-of-week the-date)) "weekend" "workday")])]
                   [:div
                    (doall (for [data infos]
                             [:div.has-text-centered.user-div.info {:class (when-not (empty? (:description data)) [:tooltip :is-tooltip-black :is-tooltip-multiline])
                                                                    :data-tooltip (:description data)
                                                                    :key (:id data)}
                              (when-not (empty? (:description data)) [:i.material-icons.md-18 "info"])
                              " "
                              (:email data)
                              (when (and (not= now-status :now-old) (:show-admin @session)) [:span " " [:button.button.is-black.admin-button {:on-click (fn []
                                                                                                                                                          (admin-delete-info! parking-zone parking-name formatted-date (:email data) #(fetch-current-days! parking-zone parking-name (:current-date @session) (:show-days-count @session))))} "Delete"]])]))
                    (when (and (not= now-status :now-old) (:show-admin @session))
                      [:div.has-text-centered.noselect.info.user-div
                       [:button.button.is-white.admin-button.tooltip.is-tooltip-black {:data-tooltip "Add an event"
                                                                                       :on-click #(swap! session assoc :add-event {:date formatted-date})} "Add"]])
                    [:div.has-text-centered.noselect {:class css
                                                      :data-tooltip (case now-status
                                                                      :now-old "This day is already gone"
                                                                      (if (= :out with-own)
                                                                        "Cancel out of office"
                                                                        (if (= :yes with-own)
                                                                          "Set out of office"
                                                                          (if (seq with-active)
                                                                            "Give up"
                                                                            (if (or (seq with-pending) (seq with-inactive))
                                                                              "Cancel reservation"
                                                                              (if (seq with-blocked)
                                                                                "Blocked, only admin can help"
                                                                                "Make reservation"))))))

                                                      :on-click (fn [_]
                                                                  (when (not= now-status :now-old)
                                                                    (if (= :out with-own)
                                                                      (cancel-out-of-office! parking-zone parking-name the-date)
                                                                      (if (= :yes with-own)
                                                                        (set-out-of-office! parking-zone parking-name the-date)
                                                                        (if (seq with-active)
                                                                          (deactivate! parking-zone parking-name the-date)
                                                                          (if (or (seq with-pending) (seq with-inactive))
                                                                            (cancel! parking-zone parking-name the-date)
                                                                            (when (empty? with-blocked) (show-types parking-zone parking-name #(reserve! parking-zone parking-name the-date %)))))))))}
                     [:span {:style {:white-space :nowrap}} (dow the-date) [:i.material-icons.md-18 (case now-status
                                                                                                      :now-old "check_box_outline_blank"
                                                                                                      (if (= :out with-own)
                                                                                                        "remove_circle_outline"
                                                                                                        (if (= :yes with-own)
                                                                                                          "check_circle_outline"
                                                                                                          (if (seq with-active)
                                                                                                            "check_circle_outline"
                                                                                                            (if (seq with-pending)
                                                                                                              "wb_sunny"
                                                                                                              (if (seq with-inactive)
                                                                                                                "outlined_flag"
                                                                                                                (when (seq with-blocked)
                                                                                                                  "delete_outline")))))))]]
                     [:br] [:span {:style {:white-space :nowrap}} formatted-date]]])
                 (doall (for [data rows]
                          [:div.has-text-centered {:key   (:id data)
                                                   :class (flatten [:user-div
                                                                    (cond
                                                                      (= "active" (:status data)) :has-background-success
                                                                      (= "inactive" (:status data)) :has-background-inactive
                                                                      (= "pending" (:status data)) :has-background-warning
                                                                      (= "out" (:status data)) :has-background-grey-lighter
                                                                      (= "blocked" (:status data)) :has-background-dangerous)])} (when (= "active" (:status data)) (str (:slot_name data) ": ")) (when (and (#{"pending" "inactive"} (:status data)) (:parking_type data)) (str (:parking_type data) ": ")) (str (:user_name data) " ") (when (and (:show-admin @session) (:show-emails @session)) (str (:email data) " "))
                           (when (:on_behalf_of data) [:span {:class [:tooltip :is-tooltip-black]
                                                              :data-tooltip "Done by admin on behalf of the user"}
                                                       [:i.material-icons.md-18 "supervised_user_circle"]])
                           (when (and (not= now-status :now-old) (:show-admin @session) (#{"pending" "inactive"} (:status data)))
                             [:button.button.is-success {:class [:admin-button]
                                                         :on-click (fn [] (confirm "Auto assign space" (str "Do you really want to let " js/appName " auto assign a space?") #(admin-activate! parking-zone parking-name formatted-date (:user_name data) (:email data) nil)))} "Do a favor"])
                           (when (and (not= now-status :now-old) (:show-admin @session) (#{"out"} (:status data)))
                             [:button.button.is-info {:class [:admin-button]
                                                      :on-click (fn [] (confirm "Cancel out of office" "Do you really want to cancel out of office for the user?" #(admin-cancel-out-of-office! parking-zone parking-name formatted-date (:user_name data) (:email data) nil)))} "Cancel OoO"])
                           (when (and (not= now-status :now-old) (:show-admin @session) (#{"pending" "inactive"} (:status data)))
                             [:button.button.is-primary {:class [:admin-button]
                                                         :on-click #(admin-show-select parking-zone parking-name formatted-date (:user_name data) (:email data))} "Assign"])
                           (when (and (not= now-status :now-old) (:show-admin @session) (#{"inactive"} (:status data)))
                             [:button.button.is-warning {:class [:admin-button]
                                                         :on-click #(admin-reserve! parking-zone parking-name formatted-date (:user_name data) (:email data))} "Kick in"])
                           (when (and (not= now-status :now-old) (:show-admin @session) (= "blocked" (:status data)))
                             [:button.button.has-background-inactive {:class [:admin-button]
                                                                      :on-click #(admin-unblock! parking-zone parking-name formatted-date (:user_name data) (:email data))} "Back to game"])
                           (when (and (not= now-status :now-old) (:show-admin @session) (= "active" (:status data)))
                             [:button.button.has-background-dangerous {:class [:admin-button]
                                                                       :on-click (fn [] (confirm "Kick out" "Do you really want to kick the user out of the space?" #(admin-deactivate! parking-zone parking-name formatted-date (:user_name data) (:email data))))} "Kick out"])]))])))]]])


(defn parking [parking-zone parking-name]
  [:div.column-padding
   [calendar parking-zone parking-name (:current-date @session)]])

(defn home-page []
  [:div.column-padding
   [:div.modal {:class (when (:created @session) :is-active)}
    [:div.modal-background
     [:div.modal-card
      [:header.modal-card-head
       [:p.modal-card-title "Just Created"]]
      [:section.modal-card-body "You must activate the app through the link we just sent on your email, otherwise you will not be able to log in."]
      [:footer.modal-card-foot
       [:button.button {:class :is-success
                        :on-click (fn [_]
                                    (swap! session assoc :created nil))} "Okay, I did it"]]]]]

   (r/with-let [email (r/atom "")
                user-name (r/atom "")
                token-sent (r/atom false)
                token (r/atom nil)]
          [:div.modal {:class (when (:show-email-token-login @session) :is-active)}
           [:div.modal-background
                [:div.modal-card
                 [:header.modal-card-head
                  [:p.modal-card-title "Login with email"]
                  [:button.delete {:on-click (fn [_]
                                               (swap! session assoc :show-email-token-login nil))} {:aria-label "close"}]]
                 [:section.modal-card-body
                  [:label.label "User name"]
                  [:input.input {:type "text"
                                 :on-change #(reset! user-name (-> % .-target .-value))
                                 :value @user-name
                                 :placeholder "John Doe"
                                 :class (when (clojure.string/blank? @user-name) :is-danger)}]
                  [:label.label "Email"]
                  [:input.input {:type "text"
                                 :on-change #(reset! email (-> % .-target .-value))
                                 :value @email
                                 :placeholder "john.doe@example.com"
                                 :class (when-not (is-valid-email @email) :is-danger)}]
                  [:p.help "Your email to which the token will be sent"]
                  (when-not (clojure.string/blank? @email)
                            [:div.field
                             [:div.control
                                                   [:> Hcaptcha {:onVerify #(reset! token %)
                                                                 :render "explicit"
                                                                 :sitekey js/hcaptchaSiteKey}]]])]
                 [:footer.modal-card-foot
                  [:button.button {:disabled (not @token)
                                   :class (if (and (not (clojure.string/blank? @user-name)) (is-valid-email @email)) :is-success :is-danger)
                                   :on-click (fn [_]
                                               (create-login-token! @user-name @email @token (fn []
                                                                                               (toast/toast (clj->js {:message "Sent, check your email"
                                                                                                                      :type "is-success"
                                                                                                                      :position "bottom-right"
                                                                                                                      :duration 30000
                                                                                                                      :dismissible true
                                                                                                                      :animate { :in "fadeIn", :out "fadeOut"}}))
                                                                                               (swap! session assoc :show-email-token-login nil))))} "Send token"]
                  [:button.button {:on-click (fn [_]
                                               (swap! session assoc :show-email-token-login nil))} "Close"]]]]])

   [:div.tile.is-ancestor
    [:div.tile.is-vertical.is-8
     [:div.tile
      [:div.tile.is-parent.is-vertical
       (if js/user
         [:article.tile.is-child.notification
          [:p.title "Logged in as"]
          [:p.subtitle (goog.object/get js/user "user-name")]
          [:a.button.is-danger {:on-click #(logout!)} "Logout"]]
         [:article.tile.is-child.notification
          (let [redirect-uri (js/encodeURIComponent (if (clojure.string/ends-with? js/contextUrl (str "." js/multitenantDomain))
                                                      (clojure.string/replace-first (clojure.string/replace-first (if (.startsWith js/contextUrl "http://local.") (clojure.string/replace-first js/contextUrl #":\d+" "") js/contextUrl) #"http://local\." "https://login.") #"https://\w+\." "https://login.")
                                                      js/contextUrl))
                state (js/encodeURIComponent js/sessionState)]
            [:div
             [:p.title "Login "
              [:a.button.is-primary {:on-click #(swap! session assoc :show-email-token-login true)} "Email Token"]]
             [:p
              [:a.button.is-primary {:style (when (empty? js/openidLinkedIn) {:display :none})
                                     :href (str "https://www.linkedin.com/oauth/v2/authorization?response_type=code&client_id=" js/openidLinkedIn "&redirect_uri=" redirect-uri "%2Foauth-callback%2Flinkedin&scope=r_liteprofile%20r_emailaddress&state=" state)} "LinkedIn"] " "
              [:a.button.is-primary {:style (when (empty? js/openidGoogle) {:display :none})
                                     :href (str "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=" js/openidGoogle "&redirect_uri=" redirect-uri "%2Foauth-callback%2Fgoogle&scope=openid%20email%20profile&state=" state)} "Google"] " "
              [:a.button.is-primary {:style (when (empty? js/openidFb) {:display :none})
                                     :href (str "https://www.facebook.com/v4.0/dialog/oauth?response_type=code&client_id=" js/openidFb "&redirect_uri=" redirect-uri "%2Foauth-callback%2Ffacebook&scope=email&state=" state)} "Facebook"] " "
              [:a.button.is-primary {:style (when (empty? js/openidAzure) {:display :none})
                                     :href (str "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?response_type=code&client_id=" js/openidAzure "&redirect_uri=" redirect-uri "%2Foauth-callback%2Fazure&scope=openid%20User.Read&state=" state)} "O365/Azure"]]])])
       [:article.tile.is-child.notification.is-warning
        [:p.title (str js/companyName " users")]
        [:p.subtitle "Can login through any linked service or even just with their email. No passwords required"]]]
      [:div.tile.is-parent
        [:article.tile.is-child.notification.is-primary
         [:p.title "Other users"]
         [:p.subtitle (str "Visitors, partners,... without " js/companyName " account can use any method to log in. As a visitor you can't see any data, and you can't make reservation requests directly, any of your actions will need to be confirmed by administrator")]]]]
     [:div.tile.is-parent
       [:article.tile.is-child.notification.is-danger
        [:p.title "How to use it"]
        [:p.subtitle "Just log in, select a parking or desk area from calendar menu, find your desired date and click on the box with date. This will make a reservation request. A reservation request will turn into reservation by a smart machine learning algorithm the day before the reservation target or can be done anytime by app admin (your office angel). Check the Rules link in footer of this page if in doubt."]]]]
    [:div.tile.is-parent.is-vertical
     [:article.tile.is-child.notification.is-success
      [:p.title "If login does not work"]
      [:p.subtitle "Make sure you clicked on the activation link in the email you received"]]
     [:article.tile.is-child.notification.is-info
      [:p.title "This app"]
      [:p.subtitle "Helps not only with parkings and desk sharing. It can also help you with spreading info to your team"]]]]
   [:footer.footer
    [:div.content.has-text-centered
     [:p
      [:strong js/appName] " by Tomas Lamr and friends. Made with ❤️ in Hradec Králové."]
     [:p [:a {:href "/#/privacy"} "Privacy"] " | " [:a {:href "/#/terms"} "Terms and conditions"] (when js/user " | ") (when js/user [:a {:href "/#/rules"} "Rules"])]]]])

(defn calendar-page []
  (parking (:zone @session) (:parking @session)))

(defn score-page []
  [:div.column-padding
   [:div.columns
    [:div.column
     [:div.level.calendar-menu
      [:div.level-left
       [:div.level-item
        [:span
         [:label.label "Select " (or (:days-look-back @score) 30) " days @ " (:zone @score)]
         [:div.select
          [:select {:id :switch-score-days
                    :name :switch-score-days
                    :on-change (fn [v]
                                 (swap! score assoc :days-look-back (empty-to-nil (-> v .-target .-value)))
                                 (fetch-score! (:zone @score) (f/unparse-local-date lt/iso-local-date (t/today))))}
           [:option {:key "Default" :value 30} "Select days from area"]
           (map (fn [days] [:option {:key (key days) :value (val days)} (str (val days) " (" (key days) ")")]) (get-in @conf [:days-look-back (:zone @score)] {}))]]]]]]
     [:section.calendar-menu>div.container>div.content
      [:table.table
       [:thead
        [:tr
         [:th "Pos"]
         [:th "Name"]
         [:th "Email"]
         [:th "Points"]
         [:th "Records"]
         [:th.has-background-success "Wins"]
         [:th.has-background-inactive "Loses"]
         [:th.has-background-dangerous "GiveUps"]
         [:th.has-background-grey-lighter "Outs"]]]
       [:tbody
        (doall (map-indexed (fn [idx item]
                              [:tr {:key idx}
                               [:th (inc idx)]
                               [:td (:user_name item)]
                               [:td (:email item)]
                               [:td (:points item)]
                               [:td (:count item)]
                               [:td (:actives item)]
                               [:td (:inactives item)]
                               [:td (:blockeds item)]
                               [:td (:outs item)]]) (:data @score)))]]]]]])

(defn show-chart
  [config-atom]
  (let [chart-data {:labels (vec (map :parking_day (:data @config-atom)))
                    :series [{:name :actives
                              :data (vec (map :actives (:data @config-atom)))}
                             {:name :outs
                              :data (vec (map :outs (:data @config-atom)))}
                             {:name :inactives
                              :data (vec (map :inactives (:data @config-atom)))}
                             {:name :blockeds
                              :data (vec (map :blockeds (:data @config-atom)))}
                             {:name :pendings
                              :data (vec (map :pendings (:data @config-atom)))}]}
        options {:fullWidth true
                 :series {:actives {:showArea true}
                          :outs {:showArea true}
                          :inactives {:showArea true}}
                 :axisY {:onlyInteger true}
                 :axisX {:labelInterpolationFnc #(str
                                                   (subs (dow (f/parse-local-date lt/iso-local-date %)) 0 3)
                                                   "\n"
                                                   (subs % 8))}}]
    (js/Chartist.Line. ".ct-chart" (clj->js chart-data) (clj->js options))))

(defn chart-component
  [config-atom]
  (r/create-class
    {:component-did-mount #(show-chart config-atom)
     :component-did-update #(show-chart config-atom)
     :display-name        "chart-component"
     :reagent-render      (fn [config-atom]
                            @config-atom
                            [:div {:class "ct-chart ct-minor-seventh"}])}))

(defn analytics-page []
  [:div.column-padding
   [:div.columns
    [:div.column
     [:div.level.calendar-menu
      [:div.level-left
       [:div.level-item
        [:div.buttons.has-addons
         [:span.button {:on-click #(change-date-analytics t/minus 1)} [:i.material-icons "chevron_left"]]
         [:span.button {:on-click #(change-date-to-today-analytics)} "This month"]
         [:span.button {:on-click #(change-date-analytics t/plus 1)} [:i.material-icons "chevron_right"]]]]]
      [:div.level-right
       [:span "Since " (f/unparse-local-date lt/iso-local-date (:current-date @analytics))]]
      #_[:div.level-right
         [:div.buttons.has-addons
          [:span.button {:on-click #(js/window.open (str "/excel/" (:zone @session) "/" (:parking @session) "/" (f/unparse-local-date lt/iso-local-date (or (:current-date @analytics) (t/first-day-of-the-month- (t/now))))) "_blank")} [:i.material-icons "save_alt"]]]]]
     [:section.calendar-menu>div.container>div.content
      (when (:data @analytics)
        [chart-component analytics])]]]])

(defn new-page []
  (r/with-let [name (r/atom (uuid/str (random-uuid)))
               name-available (r/atom true)
               email (r/atom nil)
               email2 (r/atom nil)
               token (r/atom nil)
               checked (r/atom false)
               show-captcha (r/atom false)
               is-name-invalid #(nil? (re-matches #"[a-z0-9]+[a-z0-9\-]*[a-z0-9]+" %))
               domain-suffix (str "." js/multitenantDomain)]
    [:section.section.has-background-white>div.container>div.content
     [:h1 "Create a new app"]
     [:div.field
      [:label.label "Domain name"]
      [:div.field.has-addons
       [:div.control
        [:input.input {:on-change (fn [e]
                                    (reset! name (-> e .-target .-value))
                                    (check-name-available @name name-available))
                       :value     @name
                       :maxLength 32
                       :size      64
                       :class     (when (or (>= 5 (count @name)) (is-name-invalid @name) (not @name-available)) :is-danger)
                       :type      "text"}]]
       [:div.control
        [:button.button.is-static domain-suffix]]]
      (when (not @name-available) [:p.help.is-danger "The name is not available anymore, please choose another."])
      (when (and (<= 2 (count @name)) (is-name-invalid @name)) [:p.help.is-danger "The name contains forbidden characters, please choose another. Use only a-z, 0-9 or dash."])
      (when (>= 5 (count @name)) [:p.help.is-danger "The name must be at least 6 letters long."])]
     [:div.field
      [:label.label "Email"]
      [:div.control
       [:input.input {:type "text"
                      :on-change #(reset! email (-> % .-target .-value))
                      :value @email
                      :class (when-not (is-valid-email @email) :is-danger)}]]
      [:p.help "This email will be used as a primary contact for your oranization. Holder of this email is automatically primary administrator of the app."]]
     [:div.field
      [:label.label "Email once again"]
      [:div.control
       [:input.input {:type "text"
                      :on-change #(reset! email2 (-> % .-target .-value))
                      :value @email2
                      :class (when (not= @email @email2) :is-danger)}]]
      (when (not= @email @email2) [:p.help.is-danger "Both emails need to be same address."])]
     [:div.field
      [:div.control
       [:label.checkbox
        [:input {:type "checkbox"
                 :on-change (fn []
                              (reset! checked (not @checked))
                              (when @checked (reset! show-captcha true)))
                 :checked @checked}] " I agree to the" [:a {:href "/#/terms"} " terms and conditions"]]]
      [:p.help "Once you'll create an app, we'll send you an activation email where you need to follow an activation link before first use of the app itself."]]
     (when @show-captcha [:div.field
                          [:div.control
                           [:> Hcaptcha {:onVerify #(reset! token %)
                                         :render "explicit"
                                         :sitekey js/hcaptchaSiteKey}]]])
     [:div.field.is-grouped
      [:div.control
       [:button.button.is-link {:disabled (when (or (not @checked) (not (is-valid-email @email)) (not @token) (is-name-invalid @name) (not= @email @email2)) :disabled)
                                :on-click #(create-new! (str @name domain-suffix) @email @token)} "Create"]]]]))

(def pages
  {:home #'home-page
   :calendar #'calendar-page
   :rules #'rules-page
   :score #'score-page
   :privacy #'privacy-page
   :settings #'settings-page
   :analytics #'analytics-page
   :terms #'terms-page
   :new #'new-page})

(defn page []
  [(pages (:page @session))])

;; -------------------------
;; Routes

(def router
  (reitit/router
    [["/" :home]
     ["/calendar/:zone/:parking" :calendar]
     ["/analytics/:zone/:parking" :analytics]
     ["/score/:zone" :score]
     ["/rules" :rules]
     ["/privacy" :privacy]
     ["/settings" :settings]
     ["/terms" :terms]
     ["/new" :new]]))

(defn match-route [uri]
  (->> (or (not-empty (string/replace uri #"^.*#" "")) "/")
       (reitit/match-by-path router)
       :data
       :name))

(defn refetch-current! []
  (if js/user
    (do
      (fetch-conf!)
      (when-not (or (not= :calendar (:page @session))
                    (nil? (:zone @session))
                    (nil? (:parking @session)))
        (fetch-current-days! (:zone @session) (:parking @session) (:current-date @session) (:show-days-count @session)))
      (when (and (= :analytics (:page @session)))
           (some? (:zone @session))
           (some? (:parking @session))
           (fetch-analytics! (:zone @session) (:parking @session) (or (:current-date @analytics) (t/first-day-of-the-month- (t/now)))))
      (when (and (= :score (:page @session))
                 (some? (:zone @score))
                 (some? (:date @score)))
        (fetch-score! (:zone @score) (f/unparse-local-date lt/iso-local-date (t/today)))))))

;; -------------------------
;; History
;; must be called after routes have been defined
(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
      HistoryEventType/NAVIGATE
      (fn [event]
        (swap! session assoc :types nil)
        (swap! session assoc :page (or (if js/user (match-route (.-token event)) (or (#{:home :privacy :new :terms} (match-route (.-token event))) :home)) :home))
        (if js/user
          (do
            (let [[_ _ zone parking] (clojure.string/split (.-token event) "/")]
              (when (#{:calendar :analytics} (:page @session))
                (swap! session assoc :zone (js/decodeURIComponent zone))
                (swap! session assoc :parking (js/decodeURIComponent parking)))
              (when (= :score (:page @session))
                (swap! score assoc :zone (js/decodeURIComponent zone))
                (swap! score assoc :date (f/unparse-local-date (:year-month f/formatters) (t/today)) (js/decodeURIComponent parking)))
              (when (and (= :settings (:page @session)) (goog.object/get js/user "is-admin"))
                (fetch-timezones!)
                (fetch-settings!))))
          (do
           (swap! session assoc :zone nil)
           (swap! session assoc :parking nil)
           (swap! score assoc :zone nil)
           (swap! score assoc :parking nil)
           (when (and (= :home (:page @session)) (= (.-token event) "created"))
             (swap! session assoc :created true))))))
    (.setEnabled true)))

;; -------------------------
;; Initialize app
(defn ^:dev/after-load mount-components []
  (d/render [#'navbar] (.getElementById js/document "navbar"))
  (d/render [#'page] (.getElementById js/document "app")))

(defn init! []
  (ajax/load-interceptors!)
  (hook-browser-navigation!)
  (refetch-current!)
  (mount-components))
