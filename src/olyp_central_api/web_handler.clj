(ns olyp-central-api.web-handler
  (:require bidi.ring
            [olyp-central-api.web-handlers.users-handler :as users-handler]
            [olyp-central-api.web-handlers.reservations-handler :as reservations-handler]
            [datomic.api :as d]))

(defn create-handler [database-comp]
  (let [datomic-conn (:datomic-conn database-comp)
        handler (bidi.ring/make-handler
                 [""
                  {"/" (fn [req] {:status 200 :body "OLYP Central API!"})
                   "/users" users-handler/users-collection-handler
                   "/users/" {[:user-id ""] {"" users-handler/user-handler
                                             "/bookings" reservations-handler/bookings-for-user-collection-handler
                                             "/bookings/" {[[#"[^\/]+" :booking-id] ""] reservations-handler/booking-handler}
                                             "/password" users-handler/password-handler}}
                   "/authenticate" users-handler/authenticate-user
                   "/users_by_email/" {[[#"[^\/]+" :user-email] ""] {"/auth_tokens/" {[:auth-token ""] users-handler/user-by-email-and-auth-token}}}
                   "/reservable_rooms" {"" reservations-handler/reservable-rooms-collection-handler
                                      "/" {[[#"[^\/]+" :reservable-room-id] ""]
                                           {"/reservations/" {[[#"[^\/]+" :date] ""]
                                                         reservations-handler/reservations-for-reservable-room-collection-handler}}}}}])]
    (fn [req]
      (try
        (let [db (d/db datomic-conn)]
          (handler (assoc req
                     :datomic-conn datomic-conn
                     :datomic-db db)))
        (catch clojure.lang.ExceptionInfo e
          (if (-> e ex-data :olyp-validation-error)
            {:status 422
             :headers {"Content-Type" "application/json"}
             :body (cheshire.core/generate-string {:_msg #{(.getMessage e)}})}
            (throw e)))))))
