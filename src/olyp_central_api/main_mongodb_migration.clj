(ns olyp-central-api.main-mongodb-migration
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [datomic.api :as d])
  (:import (java.util Date)))

(defn get-room-booking-agreements [customer]
  (let [agreement (:customer-room-booking-agreement/_customer customer)]
    [{"type" "hourlyRental"
      "roomId" (-> agreement :customer-room-booking-agreement/reservable-room :reservable-room/public-id)
      "hourlyPrice" (:customer-room-booking-agreement/hourly-price agreement)
      "freeHours" (:customer-room-booking-agreement/free-hours agreement)
      "tax" true}]))

(defn -main [& args]
  (let [mg-conn (mg/connect {:host (nth args 0) :port (Long/parseLong (nth args 1) 10)})
        mg-db (mg/get-db mg-conn (nth args 2))
        datomic-conn (d/connect (nth args 3))
        datomic-db (d/db datomic-conn)]

    (doseq [coll ["users" "rooms" "customers" "reservations"]]
      (mc/remove mg-db coll))

    (doseq [user (->> (d/q '[:find [?e ...] :where [?e :user/public-id]] datomic-db)
                      (map #(d/entity datomic-db %)))]
      (let [[first-name & last-names] (clojure.string/split (:user/name user) #" ")]
        (mc/insert
          mg-db "users"
          {"_id" (:user/public-id user)
           "createdAt" (Date.)
           "services" {"password" {"bcrypt" (:user/bcrypt-password user)}}
           "emails" [{"address" (:user/email user) "verified" false}]
           "profile" {"firstName" first-name "lastName" (clojure.string/join " " last-names)}
           "customers" [{"id" (-> user :user/customer :customer/public-id)}]
           "roles" {"booking" ["user"]}})))

    (doseq [room (->> (d/q '[:find [?e ...] :where [?e :reservable-room/public-id]] datomic-db)
                      (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "rooms"
          {"_id" (:reservable-room/public-id room)
           "name" (:reservable-room/name room)})))

    (doseq [customer (->> (d/q '[:find [?e ...] :where [?e :customer/public-id] [?e :customer/type :customer.type/company]] datomic-db)
                          (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "customers"
          {"_id" (:customer/public-id customer)
           "type" "company"
           "roomBookingAgreements" (get-room-booking-agreements customer)
           "brregId" (:customer/brreg-id customer)
           "name" (:customer/name customer)
           "address" (:customer/address customer)
           "zip" (:customer/zip customer)
           "city" (:customer/city customer)
           "contactPerson" {"name" (:customer/contact-person-name customer)
                            "email" (:customer/contact-person-email customer)
                            "phone" (:customer/contact-person-phone customer)}})))

    (doseq [customer (->> (d/q '[:find [?e ...] :where [?e :customer/public-id] [?e :customer/type :customer.type/person]] datomic-db)
                          (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "customers"
          {"_id" (:customer/public-id customer)
           "type" "person"
           "roomBookingAgreements" (get-room-booking-agreements customer)
           "name" (:customer/name customer)
           "address" (:customer/address customer)
           "zip" (:customer/zip customer)
           "city" (:customer/city customer)
           "email" (:customer/contact-person-email customer)
           "phone" (:customer/contact-person-phone customer)})))

    (doseq [reservation (->> (d/q '[:find [?e ...] :where [?e :room-reservation/public-id]] datomic-db)
                         (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "reservations"
          {"_id" (:room-reservation/public-id reservation)
           "type" "booking"
           "booking" {"userId" (-> reservation :room-reservation/ref :room-booking/user :user/public-id)
                      "customerId" (-> reservation :room-reservation/ref :room-booking/user :user/customer :customer/public-id)}
           "comment" (:room-reservation/comment reservation)
           "from" (:room-reservation/from reservation)
           "to" (:room-reservation/to reservation)
           "roomId" (-> reservation :room-reservation/reservable-room :reservable-room/public-id)})))

    (prn "Done!")))