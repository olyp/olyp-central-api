(ns olyp-central-api.web-handlers.rentable-rooms-handler
  (:require [datomic.api :as d]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]]))

(defn rentable-room-to-public-value [ent]
  {:id (:rentable-room/public-id ent)
   :name (:rentable-room/name ent)})

(def rentable-rooms-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [e]
           (rentable-room-to-public-value (d/entity db e)))
         (d/q '[:find [?e ...] :where [?e :rentable-room/public-id]] db)))))))
