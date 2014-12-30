(ns olyp-central-api.web-handlers.bookings-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.bookings-factory :as bookings-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [cheshire.core]
            [liberator.core :refer [resource]])
  (:import [java.util UUID]))

(defn bookable-room-to-public-value [ent]
  {:id (str (:bookable-room/public-id ent))
   :name (:bookable-room/name ent)})

(defn booking-ent-to-public-value [ent]
  {:from ""
   :to ""
   :user {:id (str (:user/public-id ent))
          :email (:user/email ent)
          :name (:user/name ent)}
   :bookable-room (bookable-room-to-public-value (:room-booking/bookable-room ent))})

(def bookings-collection-resource
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :processable? (liberator-util/comp-pos-decision
                  liberator-util/processable-json?
                  (fn [ctx] {:olyp-json (bookings-factory/enhance-json (:olyp-json ctx))})
                  (liberator-util/make-json-validator bookings-factory/validate-booking))
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (liberator-util/get-user-entity-from-route-params :olyp-user)

   :post!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-user]}]
     (-> olyp-json
         (bookings-factory/create-booking olyp-user datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [{:keys [datomic-entity]}]
     (-> datomic-entity
         booking-ent-to-public-value
         cheshire.core/generate-string))))

(def bookable-rooms-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[e]]
           (bookable-room-to-public-value (d/entity db e)))
         (d/q '[:find ?e :where [?e :bookable-room/public-id]] db)))))))
