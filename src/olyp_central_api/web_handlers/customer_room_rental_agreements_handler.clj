(ns olyp-central-api.web-handlers.customer-room-rental-agreements-handler
  (:require [datomic.api :as d]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]]))

(defn customer-room-rental-agreement-to-public-value [ent]
  {:id (:customer-room-rental-agreement/public-id ent)
   :customer_id (-> ent :customer-room-rental-agreement/customer :customer/public-id)
   :rentable_room_id (-> ent :customer-room-rental-agreement/rentable-room :rentable-room/public-id)
   :monthly_price (.toString (:customer-room-rental-agreement/monthly-price ent))
   :tax (:customer-room-rental-agreement/tax ent)})

(def customer-room-rental-agreements-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [e]
           (customer-room-rental-agreement-to-public-value (d/entity db e)))
         (d/q '[:find [?e ...] :where [?e :customer-room-rental-agreement/public-id]] db)))))))
