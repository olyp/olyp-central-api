(ns olyp-central-api.components.datomic-connection
  (:require [com.stuartsierra.component :as component]
            [io.rkn.conformity :as conformity]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [clojure.tools.logging :as log]
            [olyp-central-api.migrations :as migrations])
  (:import [java.util UUID]))

(def schema (-> "datomic_schema.edn" io/resource slurp read-string))

(defrecord Database [connection-uri]
  component/Lifecycle

  (start [component]
    (log/info (str "Connecting to Datomic on " connection-uri))
    (d/create-database connection-uri)
    (let [datomic-conn (d/connect connection-uri)]
      (log/info (str "Ensuring Datomic conforms to schema"))
      (conformity/ensure-conforms datomic-conn schema [:olyp/main-schema])
      (log/info (str "Running migrations"))
      (conformity/ensure-conforms datomic-conn schema [:olyp/invoicing-attrs])
      (conformity/ensure-conforms datomic-conn {:olyp/setting-is-invoiced-attr {:txes [(migrations/setting-is-invoiced-attr datomic-conn)]}} [:olyp/setting-is-invoiced-attr])
      (conformity/ensure-conforms datomic-conn schema [:olyp/invoicing-attrs-2])
      (conformity/ensure-conforms datomic-conn schema [:olyp/invoicing-attrs-3])
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
