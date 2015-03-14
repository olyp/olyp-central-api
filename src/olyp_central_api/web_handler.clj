(ns olyp-central-api.web-handler
  (:require bidi.ring
            [olyp-central-api.web-handlers.users-handler :as users-handler]
            [olyp-central-api.web-handlers.reservations-handler :as reservations-handler]
            [olyp-central-api.web-handlers.customers-handler :as customers-handler]
            [olyp-central-api.web-handlers.invoice-batches-handler :as invoice-batches-handler]
            [olyp-central-api.web-handlers.rentable-rooms-handler :as rentable-rooms-handler]
            [olyp-central-api.web-handlers.customer-room-rental-agreements-handler :as customer-room-rental-agreements-handler]
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
                                                         reservations-handler/reservations-for-reservable-room-collection-handler}}}}
                   "/customers" customers-handler/customers-collection-handler
                   "/company_customers" customers-handler/company-customers-collection-handler
                   "/person_customers" customers-handler/person-customers-collection-handler
                   "/company_customers/" {[:customer-id ""] {"" customers-handler/company-customer-handler}}
                   "/person_customers/" {[:customer-id ""] {"" customers-handler/person-customer-handler}}
                   "/invoice_batches" {"" invoice-batches-handler/invoice-batch-collection-handler
                                       "/" {[:batch-id ""] {"" invoice-batches-handler/invoice-batch-handler}}}
                   "/rentable_rooms" rentable-rooms-handler/rentable-rooms-handler
                   "/customer_room_rental_agreements" customer-room-rental-agreements-handler/customer-room-rental-agreements-handler
                   "/recently_deleted_bookings" reservations-handler/recently-deleted-bookings-handler}])]
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
