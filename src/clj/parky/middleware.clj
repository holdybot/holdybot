(ns parky.middleware
  (:require
    [buddy.sign.jwt :as jwt]
    [parky.db.core :as db]
    [parky.env :refer [defaults]]
    [parky.config :refer [env]]
    [parky.layout :refer [*identity* *context-url*]]
    [cheshire.generate :as cheshire]
    [cognitect.transit :as transit]
    [clojure.tools.logging :as log]
    [parky.layout :refer [error-page]]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [parky.middleware.formats :as formats]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [parky.config :refer [env]]
    [ring.middleware.flash :refer [wrap-flash]]
    [immutant.web.middleware :refer [wrap-session]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
    [expiring-map.core :as em])
  (:import))

(def jwt-secret (get env :jwt-secret "d294eb91b2105dfd92ba424aa27c60abc3a9d5e33bbf12fc"))

(def cache (em/expiring-map 60 {:max-size 1000})) ;; TODO: CHECK PERF!
(defn get-jwt-valid-after [tenant_id jwt]
  (let [jwt-valid-after (get cache tenant_id (:jwt_valid_after (db/get-jwt-valid-after-from-tenant-by-id {:id (get-in jwt [:tenant :id])})))]
    (when (and tenant_id (not (get cache tenant_id)))
      (em/assoc! cache tenant_id jwt-valid-after))
    (when (<= 1000 (count cache))
      (log/warn "jwt-valid-after cache filled"))
    jwt-valid-after))
(defn wrap-identity [handler]
  (fn [request]
    (let [id (get-in request [:cookies "identity" :value])
          jwt (when (not (clojure.string/blank? id))
                (jwt/unsign id jwt-secret))]
        (binding [*identity* (if (and
                                   (= (get-in request [:headers "x-forwarded-host"] (get-in request [:headers "host"])) (get-in jwt [:tenant :host]))
                                   (> (or (:created jwt) 0) (get-jwt-valid-after (get-in jwt [:tenant :id]) jwt)))
                               jwt
                               nil)]
          (handler request)))))

(defn wrap-context-url [handler]
  (fn [request]
    (let [from-header (get-in request [:headers "x-forwarded-host"] (get-in request [:headers "host"] "localhost:3000"))]
      (binding [*context-url* (if (or (= "localhost:3000" from-header) (clojure.string/starts-with? from-header "local.")) (str "http://" from-header) (str "https://" from-header))]
        (handler request)))))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t (.getMessage t))
        (error-page {:status 500
                     :title "Something very bad has happened!"
                     :message "We've dispatched a team of highly trained gnomes to take care of the problem."})))))

(defn wrap-csrf [handler]
  (fn [req]
    (if (or (= (:request-method req) :get) (= (get-in req [:headers "x-csrf-token"]) (get-in req [:cookies "x-csrf-token" :value])))
      (handler req)
      (error-page
        {:status 403
         :title "Invalid anti-forgery token"}))))

(defn wrap-formats [handler]
  (let [wrapped (-> handler wrap-params (wrap-format formats/instance))]
    (fn [request]
      ;; disable wrap-formats for websockets
      ;; since they're not compatible with this middleware
      ((if (:websocket? request) handler wrapped) request))))

(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      wrap-context-url
      wrap-identity
      wrap-flash
      (wrap-defaults
        (-> site-defaults
            (assoc-in [:security :anti-forgery] false)
            (dissoc :session)))
      wrap-internal-error))
