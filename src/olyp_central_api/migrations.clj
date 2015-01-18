(ns olyp-central-api.migrations
  (:require [datomic.api :as d]))

(defn setting-is-invoiced-attr [datomic-conn]
  (let [db (d/db datomic-conn)]
    (concat
     (map
      (fn [booking-eid]
        [:db/add booking-eid :room-booking/is-invoiced false])
      (d/q '[:find [?booking ...] :where [?booking :room-booking/public-id]] db))
     (map
      (fn [customer-eid]
        [:db/add customer-eid :customer/booking-tax 25])
      (d/q '[:find [?customer ...] :where [?customer :customer/public-id]] db)))))
