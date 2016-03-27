(ns olyp-central-api.reservations-test
  (:require [clojure.test :refer :all]
            [olyp-central-api.components.datomic-connection :as datomic-connection-component]
            [com.stuartsierra.component :as component]
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

(deftest various-cases-of-overlapping-bookings
  (with-datomic-conn
    datomic-conn
    (let [room (create-reservable-room datomic-conn "Rom 5")
          user-a (create-user datomic-conn "test@test.com" "Quentin Test" "123abc" 500 8)]

      (create-bookings
        room user-a datomic-conn
        (LocalDateTime. 2015 01 14, 16 00) (LocalDateTime. 2015 01 14, 18 00)
        (LocalDateTime. 2015 01 16, 16 00) (LocalDateTime. 2015 01 18, 18 00))

      (is
        (thrown-with-msg?
          Exception #"reservation crashes"
          (create-bookings
            room user-a datomic-conn
            (LocalDateTime. 2015 01 14, 17 30) (LocalDateTime. 2015 01 14, 18 30))))

      (is
        (thrown-with-msg?
          Exception #"reservation crashes"
          (create-bookings
            room user-a datomic-conn
            (LocalDateTime. 2015 01 17, 14 00) (LocalDateTime. 2015 01 17, 16 00)))))))