(ns olyp-central-api.app
  (:require [com.stuartsierra.component :as component]
            [olyp-central-api.components.datomic-connection :as datomic-connection]
            [olyp-central-api.components.web :as web]))

(defmulti create-database :type)
(defmethod create-database :datomic-mem [database]
  (datomic-connection/create-in-memory-database))
(defmethod create-database :datomic [{:keys [host port db-name]}]
  (datomic-connection/create-database host port db-name))

(defn create-system [{:keys [database web]}]
  (component/system-map
   :database (create-database database)
   :web (component/using
         (web/create-web web)
         [:database])))
