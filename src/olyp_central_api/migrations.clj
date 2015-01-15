(ns olyp-central-api.migrations
  (:require [datomic.api :as d]))

(defn adding-is-invoiced-attr []
  [{:db/id #db/id[:db.part/db]
    :db/ident :room-booking/is-invoiced
    :db/valueType :db.type/boolean
    :db/index true
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

(defn setting-is-invoiced-attr [datomic-conn]
  (let [db (d/db datomic-conn)]
    (map
     (fn [booking-eid]
       [:db/add booking-eid :room-booking/is-invoiced false])
     (d/q '[:find [?booking ...] :where [?booking :room-booking/public-id]] db))))
