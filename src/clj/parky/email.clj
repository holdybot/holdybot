(ns parky.email
  (:require [clojure.tools.logging :as log]
            [parky.config :refer [env]]
            [postal.core :as postal]))

(defn send-email [email subject body]
  (log/info "Send email" email subject
    (future (let [result (postal/send-message (get-in env [:smtp :transport])
                                              {:from (get-in env [:smtp :from])
                                               :to (clojure.string/trim email)
                                               :subject subject
                                               :body body})]
              (log/info (:error result) (:message result))))))