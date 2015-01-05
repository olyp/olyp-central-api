(ns olyp-central-api.web-handlers.contracts-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.contracts-factory :as contracts-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]]))

(defn contract-ent-to-public-value [ent]
  {:id (str (:contract/public-id ent))
   :brreg_id (:contract/brreg-id ent)
   :name (:contract/name ent)
   :address (:contract/address ent)
   :zip (:contract/zip ent)
   :city (:contract/city ent)
   :contact_person_name (:contract/contact-person-name ent)
   :contact_person_email (:contract/contact-person-email ent)
   :contact_person_phone (:contract/contact-person-phone ent)
   :version (datomic-util/get-most-recent-t ent)})

(def contracts-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post :get]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator contracts-factory/validate-contract-on-create))

   :post!
   (fn [{{:keys [datomic-conn]} :request :as ctx}]
     (-> (:olyp-json ctx)
         (contracts-factory/create-contract datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string
      (contract-ent-to-public-value (:datomic-entity ctx))))

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[u]]
           (contract-ent-to-public-value (d/entity db u)))
         (d/q '[:find ?u :where [?u :contract/public-id]] db)))))))

(defn contract-exists? [ctx]
  (if-let [contract (d/entity
                     (liberator-util/get-datomic-db ctx)
                     [:contract/public-id (get-in ctx [:request :route-params :contract-id])])]
    {:olyp-contract contract}))

(def contract-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put :get]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? contract-exists?
   :existed? contract-exists?

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator contracts-factory/validate-contract-on-update))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-contract]}]
     (-> (contracts-factory/update-contract olyp-json olyp-contract datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (-> (:olyp-contract ctx)
         contract-ent-to-public-value
         cheshire.core/generate-string))))
