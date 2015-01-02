(ns olyp-central-api.web-handler
  (:require bidi.ring
            [olyp-central-api.web-handlers.users-handler :as users-handler]
            [olyp-central-api.web-handlers.bookings-handler :as bookings-handler]
            [datomic.api :as d]))

(defn create-handler [database-comp]
  (let [datomic-conn (:datomic-conn database-comp)
        handler (bidi.ring/make-handler
                 [""
                  {"/" (fn [req] {:status 200 :body "OLYP Central API!"})
                   "/users" users-handler/users-collection-handler
                   "/users/" {[:user-id ""] {"" users-handler/user-handler
                                             "/bookings" bookings-handler/bookings-for-user-collection-handler
                                             "/password" users-handler/password-handler}}
                   "/authenticate" users-handler/authenticate-user
                   "/users_by_email/" {[[#"[^\/]+" :user-email] ""] {"/auth_tokens/" {[:auth-token ""] users-handler/user-by-email-and-auth-token}}}
                   "/bookable_rooms" {"" bookings-handler/bookable-rooms-collection-handler
                                      "/" {[[#"[^\/]+" :bookable-room-id] ""]
                                           {"/bookings/" {[[#"[^\/]+" :date] ""]
                                                         bookings-handler/bookings-for-bookable-room-collection-handler}}}}}])]
    (fn [req]
      (let [db (d/db datomic-conn)]
        (handler (assoc req
                   :datomic-conn datomic-conn
                   :datomic-db db))))))
