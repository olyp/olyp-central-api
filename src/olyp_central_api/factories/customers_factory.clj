(ns olyp-central-api.factories.customers-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [java.math BigDecimal]))

(def customer-base-attrs #{"name" "address" "zip" "city" "contact_person_email" "contact_person_phone" "room_booking_hourly_price" "room_booking_free_hours"})
(def company-customer-base-attrs (clojure.set/union #{"brreg_id" "contact_person_name"} customer-base-attrs))
(def person-customer-base-attrs customer-base-attrs)

(def customer-base-validators
  [(v/presence-of "name")
   (v/presence-of "room_booking_hourly_price")
   (v/format-of "room_booking_hourly_price" :format #"^-?\d+\.\d{5}$" :message "must be a valid monetary value")
   (v/presence-of "room_booking_free_hours" :only-integer true :gte 0)
   (v/presence-of "zip")
   (v/format-of "zip" :format #"^\d{4}$" :message "must be a four digit number")
   (v/presence-of "city")])

(def company-customer-base-validators
  (concat
   [(v/presence-of "brreg_id")
    (v/format-of "brreg_id" :format #"^\d{9}$" :message "must be a nine digit number")]
   customer-base-validators))

(def person-customer-base-validators
  customer-base-validators)

(def validate-company-customer-on-create
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in company-customer-base-attrs)]
    company-customer-base-validators)))

(def validate-person-customer-on-create
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in person-customer-base-attrs)]
    person-customer-base-validators)))

(def validate-company-customer-on-update
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in (clojure.set/union #{"version"} company-customer-base-attrs))
     (v/presence-of "version")]
    company-customer-base-validators)))

(def validate-person-customer-on-update
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in (clojure.set/union #{"version"} person-customer-base-attrs))
     (v/presence-of "version")]
    person-customer-base-validators)))

(defn room-booking-agreement-attrs [data]
  {:customer-room-booking-agreement/hourly-price (BigDecimal. (data "room_booking_hourly_price"))
   :customer-room-booking-agreement/free-hours (data "room_booking_free_hours")})

(defn create-room-booking-agreement-facts [datomic-conn data customer-eid]
  (let [room-booking-agreement-tempid (d/tempid :db.part/user)
        reservable-rooms (d/q '[:find [?e ...] :where [?e :reservable-room/public-id]] (d/db datomic-conn))]

    (if (not= 1 (count reservable-rooms))
      (throw (IllegalStateException. (str "Could not find exactly one reservable room in the database. Found " reservable-rooms))))

    (concat
     [[:db/add room-booking-agreement-tempid :customer-room-booking-agreement/customer customer-eid]
      [:db/add room-booking-agreement-tempid :customer-room-booking-agreement/reservable-room (first reservable-rooms)]
      [:db/add room-booking-agreement-tempid :customer-room-booking-agreement/tax 25]]
     (map
      (fn [[attr val]]
        [:db/add room-booking-agreement-tempid attr val])
      (room-booking-agreement-attrs data)))))

(defn update-room-booking-agreement-facts [data customer]
  (let [room-booking-agreement-eid (-> customer :customer-room-booking-agreement/_customer first)]
    (map
     (fn [[attr val]]
       [:db/add room-booking-agreement-eid attr val])
     (room-booking-agreement-attrs data))))

(defn create-company-customer [data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:db/add tempid :customer/public-id (str (d/squuid))]
                   [:db/add tempid :customer/type :customer.type/company]
                   [:db/add tempid :customer/brreg-id (data "brreg_id")]
                   [:db/add tempid :customer/name (data "name")]
                   [:db/add tempid :customer/zip (data "zip")]
                   [:db/add tempid :customer/city (data "city")]]
                  (create-room-booking-agreement-facts datomic-conn data tempid)
                  (filter
                   (fn [[f e a v]] (not (nil? v)))
                   [[:db/add tempid :customer/address (data "address")]
                    [:db/add tempid :customer/contact-person-name (data "contact_person_name")]
                    [:db/add tempid :customer/contact-person-email (data "contact_person_email")]
                    [:db/add tempid :customer/contact-person-phone (data "contact_person_phone")]])))]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid))))

(defn create-person-customer [data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:db/add tempid :customer/public-id (str (d/squuid))]
                   [:db/add tempid :customer/type :customer.type/person]
                   [:db/add tempid :customer/name (data "name")]
                   [:db/add tempid :customer/zip (data "zip")]
                   [:db/add tempid :customer/city (data "city")]]
                  (create-room-booking-agreement-facts datomic-conn data tempid)
                  (filter
                   (fn [[f e a v]] (not (nil? v)))
                   [[:db/add tempid :customer/address (data "address")]
                    [:db/add tempid :customer/contact-person-email (data "contact_person_email")]
                    [:db/add tempid :customer/contact-person-phone (data "contact_person_phone")]])))]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid))))

(defn update-company-customer [data ent datomic-conn]
  (let [customer-id (:db/id ent)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:optimistic-add (data "version") customer-id
                    {:customer/brreg-id (data "brreg_id")
                     :customer/name (data "name")
                     :customer/zip (data "zip")
                     :customer/city (data "city")
                     :customer/address (data "address")
                     :customer/contact-person-name (data "contact_person_name")
                     :customer/contact-person-email (data "contact_person_email")
                     :customer/contact-person-phone (data "contact_person_phone")}]]
                  (update-room-booking-agreement-facts data ent)))]
    (d/entity (:db-after tx-res) customer-id)))

(defn update-person-customer [data ent datomic-conn]
  (let [customer-id (:db/id ent)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:optimistic-add (data "version") customer-id
                    {:customer/name (data "name")
                     :customer/zip (data "zip")
                     :customer/city (data "city")
                     :customer/address (data "address")
                     :customer/contact-person-email (data "contact_person_email")
                     :customer/contact-person-phone (data "contact_person_phone")}]]
                  (update-room-booking-agreement-facts data ent)))]
    (d/entity (:db-after tx-res) customer-id)))
