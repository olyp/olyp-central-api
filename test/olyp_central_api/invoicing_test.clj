(ns olyp-central-api.invoicing-test
  (:require [clojure.test :refer :all]
            [olyp-central-api.components.datomic-connection :as datomic-connection-component]
            [com.stuartsierra.component :as component]
            [olyp-central-api.factories.invoices-factory :as invoices-factory]
            [olyp-central-api.factories.customers-factory :as customers-factory]
            [olyp-central-api.factories.user-factory :as user-factory]
            [olyp-central-api.factories.reservations-factory :as reservations-factory]
            [olyp-central-api.factories.room-rentals-factory :as room-rentals-factory]
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

(defn create-reservable-room [datomic-conn name]
  (let [tempid (d/tempid :db.part/user)
        {:keys [db-after tempids]} @(d/transact
                                     datomic-conn
                                     [{:db/id tempid
                                       :reservable-room/public-id (str (d/squuid))
                                       :reservable-room/name name}])]
    (d/entity db-after (d/resolve-tempid db-after tempids tempid))))

(defn create-bookings [reservable-room user datomic-conn & dates]
  (doseq [[^LocalDateTime from ^LocalDateTime to] (partition 2 dates)]
    (reservations-factory/create-booking
     {"reservable_room_id" (:reservable-room/public-id reservable-room)
      "from" (.toDate from)
      "to" (.toDate to)}
     user
     datomic-conn)))

(defn create-user [datomic-conn email name password hourly-price free-hours]
  (let [customer (customers-factory/create-person-customer
                  {"name" "Quentin Test"
                   "zip" "2080"
                   "city" "Eidsvoll"
                   "room_booking_hourly_price" hourly-price
                   "room_booking_free_hours" free-hours}
                  datomic-conn)]
    (user-factory/create-user
     {"customer_id" (:customer/public-id customer)
      "email" email
      "name" name
      "password" password}
     datomic-conn)))

(defn create-room-rental [rentable-room user datomic-conn monthly-price tax]
  (room-rentals-factory/assign-room
   (:user/customer user)
   {"monthly_price" monthly-price
    "tax" tax
    "rentable_room" (:rentable-room/public-id rentable-room)}
   datomic-conn))

(defn create-rentable-room [datomic-conn room-name]
  (let [tempid (d/tempid :db.part/user)
        {:keys [db-after tempids]} @(d/transact
                                     datomic-conn
                                     [[:db/add tempid :rentable-room/public-id (str (d/squuid))]
                                      [:db/add tempid :rentable-room/name room-name]])]
    (d/entity db-after (d/resolve-tempid db-after tempids tempid))))

(deftest preparing-invoice-for-month
  (with-datomic-conn datomic-conn
    (let [reservable-room (create-reservable-room datomic-conn "Rom 5")
          rentable-room-1 (create-rentable-room datomic-conn "Rom 1")
          rentable-room-2 (create-rentable-room datomic-conn "Rom 2")
          rentable-room-3 (create-rentable-room datomic-conn "Rom 3")
          user-quentin (create-user datomic-conn
                                    "quentin@test.com" "Quentin Test" "test"
                                    "375.00000" 4)
          user-pavlov (create-user datomic-conn
                                   "pavlov@test.com" "I.P. Pavlova" "test"
                                   "350.00000" 0)
          user-edvard (create-user datomic-conn
                                   "edvard@test.com" "Edvard Grieg Stokke" "test"
                                   "375.00000" 0)]

      (create-bookings
       reservable-room user-quentin datomic-conn
       (LocalDateTime. 2015 01, 14, 16, 00) (LocalDateTime. 2015 01, 14, 18, 00)
       (LocalDateTime. 2015 01, 31, 23, 00) (LocalDateTime. 2015 02, 01, 04, 00)
       (LocalDateTime. 2015 02, 10, 14, 00) (LocalDateTime. 2015 02, 10, 17, 00))

      (create-room-rental rentable-room-1 user-quentin datomic-conn "7800.00000" 25)
      (create-room-rental rentable-room-2 user-quentin datomic-conn "12800.00000" 0)

      (create-bookings
       reservable-room user-pavlov datomic-conn
       (LocalDateTime. 2014 12, 31, 9, 00) (LocalDateTime. 2014 12, 31, 15, 00)
       (LocalDateTime. 2015 01, 31, 18, 00) (LocalDateTime. 2015 01, 31, 22, 30))

      (create-room-rental rentable-room-3 user-pavlov datomic-conn "11400.00000" 25)

      (create-bookings
       reservable-room user-edvard datomic-conn
       (LocalDateTime. 2015 02, 01, 9, 00) (LocalDateTime. 2015 02, 01, 11, 00))

      (let [invoice-list (invoices-factory/prepare-invoices-for-month 2015 1 (d/db datomic-conn))
            invoices (zipmap (map #(-> % :customer :customer/public-id) invoice-list)
                             invoice-list)]
        (is (contains? invoices (-> user-quentin :user/customer :customer/public-id)))
        (is (contains? invoices (-> user-pavlov :user/customer :customer/public-id)))
        (is (not (contains? invoices (-> user-edvard :user/customer :customer/public-id))))

        (let [quentin-invoice (get invoices (-> user-quentin :user/customer :customer/public-id))]
          (is (= (count (:lines quentin-invoice)) 3))
          (is (= (-> quentin-invoice :lines (nth 0) :unit-price) (BigDecimal. "375.00000")))
          (is (= (-> quentin-invoice :lines (nth 0) :quantity) (BigDecimal. "3")))
          (is (= (-> quentin-invoice :lines (nth 0) :tax) 25))
          (is (= (-> quentin-invoice :lines (nth 1) :unit-price) (BigDecimal. "7800.00000")))
          (is (= (-> quentin-invoice :lines (nth 1) :quantity) (BigDecimal. "1")))
          (is (= (-> quentin-invoice :lines (nth 1) :tax) 25))
          (is (= (-> quentin-invoice :lines (nth 2) :unit-price) (BigDecimal. "12800.00000")))
          (is (= (-> quentin-invoice :lines (nth 2) :quantity) (BigDecimal. "1")))
          (is (= (-> quentin-invoice :lines (nth 2) :tax) 0)))

        (let [pavlov-invoice (get invoices (-> user-pavlov :user/customer :customer/public-id))]
          (is (= (count (:lines pavlov-invoice)) 2))
          (is (= (-> pavlov-invoice :lines (nth 0) :unit-price) (BigDecimal. "350.00000")))
          (is (= (-> pavlov-invoice :lines (nth 0) :quantity) (BigDecimal. "10.5")))
          (is (= (-> pavlov-invoice :lines (nth 0) :tax) 25))
          (is (= (-> pavlov-invoice :lines (nth 1) :unit-price) (BigDecimal. "11400.00000")))
          (is (= (-> pavlov-invoice :lines (nth 1) :quantity) (BigDecimal. "1")))
          (is (= (-> pavlov-invoice :lines (nth 1) :tax) 25)))))))

(deftest creating-invoices-for-month
  (with-datomic-conn datomic-conn
    (let [reservable-room (create-reservable-room datomic-conn "Rom 5")
          rentable-room-1 (create-rentable-room datomic-conn "Rom 1")
          user-quentin (create-user datomic-conn
                                    "quentin@test.com" "Quentin Test" "test"
                                    "375.00000" 4)]
      (create-bookings
       reservable-room user-quentin datomic-conn
       (LocalDateTime. 2015 01, 14, 16, 00) (LocalDateTime. 2015 01, 14, 18, 00)
       (LocalDateTime. 2015 01, 31, 23, 00) (LocalDateTime. 2015 02, 01, 04, 00))

      (create-room-rental rentable-room-1 user-quentin datomic-conn "7800.00000" 0)

      (is (= 0 (count (d/q '[:find [?e ...] :where [?e :invoice-batch/finalized false]] (d/db datomic-conn)))))

      (invoices-factory/create-invoice-batch-for-month 2015 1 datomic-conn)

      (is (= 1 (count (d/q '[:find [?e ...] :where [?e :invoice-batch/finalized false]] (d/db datomic-conn)))))
      (let [db (d/db datomic-conn)
            batch (d/entity db (d/q '[:find ?e . :where [?e :invoice-batch/finalized false]] db))]
        (is (= false (:invoice-batch/finalized batch)))
        (is (= "2015-1" (:invoice-batch/month batch)))
        (is (= 1 (count (:invoice-batch/invoices batch))))
        (let [invoice (first (:invoice-batch/invoices batch))]
          (is (= "2015-1" (:invoice/month invoice)))
          (is (= 2 (count (:invoice/bookings invoice))))
          (let [invoice-lines (->>
                               (d/q '[:find [?e ...] :in $ ?key :where [?e :invoice-line/invoice-key ?key]]
                                    db
                                    (:invoice/key invoice))
                               (map #(d/entity db %))
                               (sort-by :invoice-line/sort-order))]
            (is (= 2 (count invoice-lines)))
            (is (= (BigDecimal. "375.00000") (-> invoice-lines (nth 0) :invoice-line/unit-price)))
            (is (= (BigDecimal. "3") (-> invoice-lines (nth 0) :invoice-line/quantity)))
            (is (= 25 (-> invoice-lines (nth 0) :invoice-line/tax)))
            (is (= (BigDecimal. "7800.0000") (-> invoice-lines (nth 1) :invoice-line/unit-price)))
            (is (= (BigDecimal. "1") (-> invoice-lines (nth 1) :invoice-line/quantity)))
            (is (= 0 (-> invoice-lines (nth 1) :invoice-line/tax)))))))))

(deftest not-creating-line-when-not-exceeding-free-hours
  (with-datomic-conn datomic-conn
    (let [reservable-room (create-reservable-room datomic-conn "Rom 5")
          rentable-room-1 (create-rentable-room datomic-conn "Rom 1")
          user-quentin (create-user datomic-conn
                                    "quentin@test.com" "Quentin Test" "test"
                                    "375.00000" 8)]

      (create-bookings
       reservable-room user-quentin datomic-conn
       (LocalDateTime. 2015 01, 14, 16, 00) (LocalDateTime. 2015 01, 14, 18, 00))

      (create-room-rental rentable-room-1 user-quentin datomic-conn "7800.00000" 25)

      (let [invoice-list (invoices-factory/prepare-invoices-for-month 2015 1 (d/db datomic-conn))
            invoices (zipmap (map #(-> % :customer :customer/public-id) invoice-list)
                             invoice-list)]
        (is (contains? invoices (-> user-quentin :user/customer :customer/public-id)))

        (let [quentin-invoice (get invoices (-> user-quentin :user/customer :customer/public-id))]
          (is (= (count (:lines quentin-invoice)) 1))
          (is (= (-> quentin-invoice :lines (nth 0) :unit-price) (BigDecimal. "7800.00000")))
          (is (= (-> quentin-invoice :lines (nth 0) :quantity) (BigDecimal. "1")))
          (is (= (-> quentin-invoice :lines (nth 0) :tax) 25)))))))
