(ns olyp-central-api.migrations
  (:require [datomic.api :as d]))

(defn setting-public-ids-for-agreements [datomic-conn]
  (let [db (d/db datomic-conn)]
    (concat
     (map
      (fn [eid]
        [:db/add eid :customer-room-rental-agreement/public-id (str (d/squuid))])
      (d/q '[:find [?e ...] :where [?e :customer-room-rental-agreement/customer]] db))
     (map
      (fn [eid]
        [:db/add eid :customer-room-booking-agreement/public-id (str (d/squuid))])
      (d/q '[:find [?e ...] :where [?e :customer-room-booking-agreement/customer]] db)))))

(defn setting-public-ids-for-invoices [datomic-conn]
  (let [db (d/db datomic-conn)]
    (map
     (fn [eid]
       [:db/add eid :invoice/public-id (str (d/squuid))])
     (d/q '[:find [?e ...] :where [?e :invoice/invoice-number]] db))))
