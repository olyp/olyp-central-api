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

(defn create-user [datomic-conn email name password booking-tax hourly-price free-hours]
  (let [customer (customers-factory/create-person-customer
                  {"name" "Quentin Test"
                   "zip" "2080"
                   "city" "Eidsvoll"
                   "room_booking_tax" booking-tax
                   "room_booking_hourly_price" hourly-price
                   "room_booking_free_hours" free-hours}
                  datomic-conn)]
    (user-factory/create-user
     {"customer_id" (:customer/public-id customer)
      "email" email
      "name" name
      "password" password}
     datomic-conn)))

(deftest preparing-invoice-for-month
  (with-datomic-conn datomic-conn
    (let [reservable-room (create-reservable-room datomic-conn)
          user-quentin (create-user datomic-conn
                                    "quentin@test.com" "Quentin Test" "test"
                                    25 "375.00000" 4)
          user-pavlov (create-user datomic-conn
                                   "pavlov@test.com" "I.P. Pavlova" "test"
                                   0 "350.00000" 0)
          user-edvard (create-user datomic-conn
                                   "edvard@test.com" "Edvard Grieg Stokke" "test"
                                   25 "375.00000" 0)]

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

      (let [invoices (invoices-factory/prepare-invoices-for-month 2015 1 (d/db datomic-conn))]
        (is (contains? invoices (-> user-quentin :user/customer :customer/public-id)))
        (is (contains? invoices (-> user-pavlov :user/customer :customer/public-id)))
        (is (not (contains? invoices (-> user-edvard :user/customer :customer/public-id))))

        (let [quentin-invoice (get invoices (-> user-quentin :user/customer :customer/public-id))]
          (is (= (count (:lines quentin-invoice)) 2))
          (is (= (-> quentin-invoice :lines (nth 0) :unit-price) (BigDecimal. "375.00000")))
          (is (= (-> quentin-invoice :lines (nth 0) :quantity) (BigDecimal. "7")))
          (is (= (-> quentin-invoice :lines (nth 0) :tax) 25))
          (is (= (-> quentin-invoice :lines (nth 1) :unit-price) (BigDecimal. "-375.00000")))
          (is (= (-> quentin-invoice :lines (nth 1) :quantity) (BigDecimal. "4")))
          (is (= (-> quentin-invoice :lines (nth 1) :tax) 25))
          (prn quentin-invoice))

        (let [pavlov-invoice (get invoices (-> user-pavlov :user/customer :customer/public-id))]
          (is (= (count (:lines pavlov-invoice)) 1))
          (is (= (-> pavlov-invoice :lines (nth 0) :unit-price) (BigDecimal. "350.00000")))
          (is (= (-> pavlov-invoice :lines (nth 0) :quantity) (BigDecimal. "10.5")))
          (is (= (-> pavlov-invoice :lines (nth 0) :tax) 0))
          (prn pavlov-invoice))))))
