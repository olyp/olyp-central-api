(ns olyp-central-api.components.datomic-connection
  (:require [com.stuartsierra.component :as component]
            [io.rkn.conformity :as conformity]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [clojure.tools.logging :as log]
            [olyp-central-api.factories.user-factory :as user-factory])
  (:import [java.util UUID]))

(def schema (-> "datomic_schema.edn" io/resource slurp read-string))
(defn generate-initial-seed-tx []
  [{:db/id (d/tempid :db.part/user)
    :reservable-room/public-id (d/squuid)
    :reservable-room/name "Rom 5"}

   ;; TODO: Only create this user in dev mode
   {:db/id (d/tempid :db.part/user)
    :user/public-id (UUID/fromString "54a495b3-3a20-4d37-88bf-9a433d66db35")
    :user/email "quentin@test.com"
    :user/name "Quentin Test"
    :user/zip "1410"
    :user/city "Kolbotn"
    :user/bcrypt-password (user-factory/encrypt-password "test")
    :user/auth-token "78888a117972edc201892c53498321fa5bfeb31aec5e3bd722f127b1cb0c6757"}
])

(defrecord Database [connection-uri]
  component/Lifecycle

  (start [component]
    (log/info (str "Connecting to Datomic on " connection-uri))
    (d/create-database connection-uri)
    (let [datomic-conn (d/connect connection-uri)]
      (log/info (str "Ensuring Datomic conforms to schema"))
      (conformity/ensure-conforms datomic-conn schema [:olyp/main-schema])
      (conformity/ensure-conforms datomic-conn {:olyp/initial-seed-data {:txes [(generate-initial-seed-tx)]}} [:olyp/initial-seed-data])
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
