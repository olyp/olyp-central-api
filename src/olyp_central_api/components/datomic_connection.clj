(ns olyp-central-api.components.datomic-connection
  (:require [com.stuartsierra.component :as component]
            [io.rkn.conformity :as conformity]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(def schema (-> "datomic_schema.edn" io/resource slurp read-string))

(defn migration-adding-is-invoiced-attr []
  [{:db/id #db/id[:db.part/db]
    :db/ident :room-booking/is-invoiced
    :db/valueType :db.type/boolean
    :db/index true
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

(defn migration-setting-is-invoiced-attr [datomic-conn]
  (let [db (d/db datomic-conn)]
    (map
     (fn [booking-eid]
       [:db/add booking-eid :room-booking/is-invoiced false])
     (d/q '[:find [?booking ...] :where [?booking :room-booking/public-id]] db))))

(defrecord Database [connection-uri]
  component/Lifecycle

  (start [component]
    (log/info (str "Connecting to Datomic on " connection-uri))
    (d/create-database connection-uri)
    (let [datomic-conn (d/connect connection-uri)]
      (log/info (str "Ensuring Datomic conforms to schema"))
      (conformity/ensure-conforms datomic-conn schema [:olyp/main-schema])
      (conformity/ensure-conforms datomic-conn {:olyp/adding-is-invoiced-attr {:txes [(migration-adding-is-invoiced-attr)]}} [:olyp/adding-is-invoiced-attr])
      (conformity/ensure-conforms datomic-conn {:olyp/setting-is-invoiced-attr {:txes [(migration-setting-is-invoiced-attr datomic-conn)]}} [:olyp/setting-is-invoiced-attr])
      (assoc component
        :connection-uri connection-uri
        :datomic-conn datomic-conn)))

  (stop [component]
    (.release (:datomic-conn component))
    (dissoc component :datomic-conn :connection-uri)))

(defn create-database [host port db-name]
  (map->Database {:connection-uri (str "datomic:free://" host ":" port "/" db-name)}))

(defn create-in-memory-database []
  (map->Database {:connection-uri (str "datomic:mem://olyp-" (java.util.UUID/randomUUID))}))
