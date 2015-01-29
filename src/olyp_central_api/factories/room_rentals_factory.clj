(ns olyp-central-api.factories.room-rentals-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [java.math BigDecimal]))

(def room-rental-base-validator
  [(v/presence-of "monthly_price")
   (v/format-of "monthly_price" :format #"^-?\d+\.\d{5}$" :message "must be a valid monetary value")
   (v/presence-of "tax")
   (v/numericality-of "tax" :only-integer true :gte 0 :lte 100)])

(defn assign-room [user data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 [[:db/add tempid :customer-room-rental-agreement/customer (-> user :user/customer :db/id)]
                  [:db/add tempid :customer-room-rental-agreement/rentable-room [:rentable-room/public-id (data "rentable_room")]]
                  [:db/add tempid :customer-room-rental-agreement/monthly-price (BigDecimal. (data "monthly_price"))]
                  [:db/add tempid :customer-room-rental-agreement/tax (data "tax")]])]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid))))
