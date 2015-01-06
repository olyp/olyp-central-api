(ns olyp-central-api.web-handlers.customers-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.customers-factory :as customers-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]]))

(defn customer-ent-to-public-value [ent]
  {:id (str (:customer/public-id ent))
   :type (case (:customer/type ent)
           :customer.type/company "company"
           :customer.type/person "person")
   :brreg_id (:customer/brreg-id ent)
   :name (:customer/name ent)
   :address (:customer/address ent)
   :zip (:customer/zip ent)
   :city (:customer/city ent)
   :contact_person_name (:customer/contact-person-name ent)
   :contact_person_email (:customer/contact-person-email ent)
   :contact_person_phone (:customer/contact-person-phone ent)
   :version (datomic-util/get-most-recent-t ent)})

(def customers-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[u]]
           (customer-ent-to-public-value (d/entity db u)))
         (d/q '[:find ?u :where [?u :customer/public-id]] db)))))))

(def company-customers-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator customers-factory/validate-company-customer-on-create))

   :post!
   (fn [{{:keys [datomic-conn]} :request :as ctx}]
     (-> (:olyp-json ctx)
         (customers-factory/create-company-customer datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string
      (customer-ent-to-public-value (:datomic-entity ctx))))))

(def person-customers-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator customers-factory/validate-person-customer-on-create))

   :post!
   (fn [{{:keys [datomic-conn]} :request :as ctx}]
     (-> (:olyp-json ctx)
         (customers-factory/create-person-customer datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string
      (customer-ent-to-public-value (:datomic-entity ctx))))))

(defn customer-exists? [type]
  (fn [ctx]
    (let [db (liberator-util/get-datomic-db ctx)]
      (if-let [customer-eid (d/q
                             '[:find ?customer .
                               :in $ ?pubid ?type
                               :where
                               [?customer :customer/public-id ?pubid]
                               [?customer :customer/type ?type]]
                             db
                             (get-in ctx [:request :route-params :customer-id])
                             type)]
        {:olyp-customer (d/entity db customer-eid)}))))

(def company-customer-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put :get]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (customer-exists? :customer.type/company)
   :existed? (customer-exists? :customer.type/company)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator customers-factory/validate-company-customer-on-update))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-customer]}]
     (-> (customers-factory/update-company-customer olyp-json olyp-customer datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (-> (:olyp-customer ctx)
         customer-ent-to-public-value
         cheshire.core/generate-string))))

(def person-customer-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put :get]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (customer-exists? :customer.type/person)
   :existed? (customer-exists? :customer.type/person)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator customers-factory/validate-person-customer-on-update))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-customer]}]
     (-> (customers-factory/update-person-customer olyp-json olyp-customer datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (-> (:olyp-customer ctx)
         customer-ent-to-public-value
         cheshire.core/generate-string))))
