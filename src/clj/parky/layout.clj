(ns parky.layout
  (:require
    [selmer.parser :as parser]
    [selmer.filters :as filters]
    [markdown.core :refer [md-to-html-string]]
    [parky.config :refer [env]]
    [ring.util.http-response :refer [content-type ok]]
    [ring.util.anti-forgery :refer [anti-forgery-field]]))

(declare ^:dynamic *identity*)
(declare ^:dynamic *context-url*)

(parser/set-resource-path!  (clojure.java.io/resource "html"))
(parser/add-tag! :csrf-field (fn [_ _] (anti-forgery-field)))
(filters/add-filter! :markdown (fn [content] [:safe (md-to-html-string content)]))

(defmacro build-timestamp []
  (.getTime (java.util.Date.)))

(defn render
  "renders the HTML template located relative to resources/html"
  [request template csrf-token session-state & [params]]
  (content-type
    (ok
      (parser/render-file
        template
        (assoc params
          :page template
          :csrf-token csrf-token
          :user (:user *identity*)
          :visitor (or (:visitor *identity*) false) ;; that was true before, can be changed once auth done properly
          :context-url *context-url*
          :session-state session-state
          :subtenants (get env :subtenants)
          :app-name (get env :app-name "Holdy")
          :company-name (get env :company-name "Company")
          :multitenant-domain (get env :multitenant-domain)
          :hcaptcha-site-key (get-in env [:hcaptcha :sitekey])
          :openid-fb (get-in env [:open-id-connect :facebook :api-key])
          :openid-google (get-in env [:open-id-connect :google :api-key])
          :openid-linkedin (get-in env [:open-id-connect :linkedin :api-key])
          :openid-azure (get-in env [:open-id-connect :azure :api-key])
          :timestamp (build-timestamp))))
    "text/html; charset=utf-8"))

(defn error-page
  "error-details should be a map containing the following keys:
   :status - error status
   :title - error title (optional)
   :message - detailed error message (optional)

   returns a response map with the error page as the body
   and the status specified by the status key"
  [error-details]
  {:status  (:status error-details)
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (parser/render-file "error.html" error-details)})
