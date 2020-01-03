(ns parky.env
  (:require
    [selmer.parser :as parser]
    [clojure.tools.logging :as log]
    [parky.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (parser/cache-off!)
     (log/info "\n-=[parky started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[parky has shut down successfully]=-"))
   :middleware wrap-dev})
