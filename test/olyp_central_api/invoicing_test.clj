(ns olyp-central-api.invoicing-test
  (:require [clojure.test :refer :all]
            [olyp-central-api.components.datomic-connection :as datomic-connection-component]
            [com.stuartsierra.component :as component]
            [olyp-central-api.factories.invoices-factory :as invoices-factory]
            [olyp-central-api.factories.customers-factory :as customers-factory]
            [olyp-central-api.factories.user-factory :as user-factory]
            [olyp-central-api.factories.reservations-factory :as reservations-factory]
            [datomic.api :as d])
  (:import [org.joda.time LocalDateTime]))

(defmacro with-datomic-conn [arg & body]
  `(let [datomic-conn-component# (-> (datomic-connection-component/create-in-memory-database)
                                    (component/start))
         ~arg (:datomic-conn datomic-conn-component#)]
     (try
       (do ~@body)
       (finally
         (component/stop datomic-conn-component#)))))

(defn create-reservable-room [datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        {:keys [db-after tempids]} @(d/transact
                                     datomic-conn
                                     [{:db/id tempid
                                       :reservable-room/public-id (str (d/squuid))
                                       :reservable-room/name "Rom 5"}])]
    (d/entity db-after (d/resolve-tempid db-after tempids tempid))))

(defn create-bookings [reservable-room user datomic-conn & dates]
  (doseq [[^LocalDateTime from ^LocalDateTime to] (partition 2 dates)]
    (reservations-factory/create-booking
     {"reservable_room_id" (:reservable-room/public-id reservable-room)
      "from" (.toDate from)
      "to" (.toDate to)}
     user
     datomic-conn)))

(defn create-user [datomic-conn email name password]
  (let [customer (customers-factory/create-person-customer
                  {"name" "Quentin Test"
                   "zip" "2080"
                   "city" "Eidsvoll"}
                  datomic-conn)]
    (user-factory/create-user
     {"customer_id" (:customer/public-id customer)
      "email" email
      "name" name
      "password" password}
     datomic-conn)))

(deftest creates-invoice-for-month
  (with-datomic-conn datomic-conn
    (let [user-quentin (create-user datomic-conn "quentin@test.com" "Quentin Test" "test")
          user-pavlov (create-user datomic-conn "pavlov@test.com" "I.P. Pavlova" "test")
          user-edvard (create-user datomic-conn "edvard@test.com" "Edvard Grieg Stokke" "test")
          reservable-room (create-reservable-room datomic-conn)]

      (create-bookings
       reservable-room user-quentin datomic-conn
       (LocalDateTime. 2015 01, 14, 16, 00) (LocalDateTime. 2015 01, 14, 18, 00)
       (LocalDateTime. 2015 01, 31, 23, 00) (LocalDateTime. 2015 02, 01, 04, 00)
       (LocalDateTime. 2015 02, 10, 14, 00) (LocalDateTime. 2015 02, 10, 17, 00))

      (create-bookings
       reservable-room user-pavlov datomic-conn
       (LocalDateTime. 2014 12, 31, 9, 00) (LocalDateTime. 2014 12, 31, 15, 00)
       (LocalDateTime. 2015 01, 31, 18, 00) (LocalDateTime. 2015 01, 31, 22, 30))

      (create-bookings
       reservable-room user-edvard datomic-conn
       (LocalDateTime. 2015 02, 01, 9, 00) (LocalDateTime. 2015 02, 01, 11, 00))

      (let [invoices (invoices-factory/prepare-invoices-for-month 2015 1 datomic-conn)]
        (prn invoices)))))
