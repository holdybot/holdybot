(ns parky.email
  (:require [clojure.tools.logging :as log]
            [postal.core :as postal]))

(defn send-email [email subject body]
  (log/info "Send email" email subject
    (future (let [result (postal/send-message {:host "smtp.example.com"
                                               :user "alice@example.com"
                                               :pass "password123"
                                               :port 587}
                                              {:from "Holdybot-noreply <postmaster@example.com"
                                               :to (clojure.string/trim email)
                                               :subject subject
                                               :body body})]
              (log/info (:error result) (:message result))))))