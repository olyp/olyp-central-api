(ns olyp-central-api.web-handlers.contracts-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.contracts-factory :as contracts-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]]))

(defn contract-ent-to-public-value [ent]
  {:id (str (:contract/public-id ent))
   :type (case (:contract/type ent)
           :contract.type/company "company"
           :contract.type/person "person")
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
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[u]]
           (contract-ent-to-public-value (d/entity db u)))
         (d/q '[:find ?u :where [?u :contract/public-id]] db)))))))

(def company-contracts-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator contracts-factory/validate-company-contract-on-create))

   :post!
   (fn [{{:keys [datomic-conn]} :request :as ctx}]
     (-> (:olyp-json ctx)
         (contracts-factory/create-company-contract datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string
      (contract-ent-to-public-value (:datomic-entity ctx))))))

(def person-contracts-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator contracts-factory/validate-person-contract-on-create))

   :post!
   (fn [{{:keys [datomic-conn]} :request :as ctx}]
     (-> (:olyp-json ctx)
         (contracts-factory/create-person-contract datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string
      (contract-ent-to-public-value (:datomic-entity ctx))))))

(defn contract-exists? [type]
  (fn [ctx]
    (let [db (liberator-util/get-datomic-db ctx)]
      (if-let [contract-eid (d/q
                             '[:find ?contract .
                               :in $ ?pubid ?type
                               :where
                               [?contract :contract/public-id ?pubid]
                               [?contract :contract/type ?type]]
                             db
                             (get-in ctx [:request :route-params :contract-id])
                             type)]
        {:olyp-contract (d/entity db contract-eid)}))))

(def company-contract-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put :get]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (contract-exists? :contract.type/company)
   :existed? (contract-exists? :contract.type/company)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator contracts-factory/validate-company-contract-on-update))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-contract]}]
     (-> (contracts-factory/update-company-contract olyp-json olyp-contract datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (-> (:olyp-contract ctx)
         contract-ent-to-public-value
         cheshire.core/generate-string))))

(def person-contract-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put :get]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (contract-exists? :contract.type/person)
   :existed? (contract-exists? :contract.type/person)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator contracts-factory/validate-person-contract-on-update))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-contract]}]
     (-> (contracts-factory/update-person-contract olyp-json olyp-contract datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (-> (:olyp-contract ctx)
         contract-ent-to-public-value
         cheshire.core/generate-string))))
