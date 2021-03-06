(ns olyp-central-api.web-handlers.users-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.user-factory :as user-factory]
            [olyp-central-api.queries.authentication-query :as authentication-query]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]])
  (:import [java.net URLDecoder]))

(defn user-ent-to-public-value [ent]
  {:id (str (:user/public-id ent))
   :email (:user/email ent)
   :name (:user/name ent)
   :auth-token (:user/auth-token ent)
   :customer_id (get-in ent [:user/customer :customer/public-id])
   :version (datomic-util/get-most-recent-t ent)})

(def users-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post :get]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator user-factory/validate-user-on-create))

   :post!
   (fn [{{:keys [datomic-conn]} :request :as ctx}]
     (-> (:olyp-json ctx)
         (user-factory/create-user datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string
      (user-ent-to-public-value (:datomic-entity ctx))))

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[u]]
           (user-ent-to-public-value (d/entity db u)))
         (d/q '[:find ?u :where [?u :user/public-id]] db)))))))


(def user-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put :get :delete]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (liberator-util/get-user-entity-from-route-params :datomic-entity)
   :existed? (liberator-util/get-user-entity-from-route-params :datomic-entity)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator user-factory/validate-user-on-update))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json datomic-entity]}]
     (-> (user-factory/update-user olyp-json datomic-entity datomic-conn)
         liberator-util/ctx-for-entity))

   :delete!
   (fn [{{:keys [datomic-conn]} :request :keys [datomic-entity]}]
     (-> (user-factory/delete-user datomic-entity datomic-conn)
         liberator-util/ctx-for-tx-res))

   :handle-ok
   (fn [ctx]
     (-> (:datomic-entity ctx)
         user-ent-to-public-value
         cheshire.core/generate-string))))

(def authenticate-user
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator authentication-query/validate-authentication)
    (fn [{{:keys [datomic-db]} :request :keys [olyp-json]}]
      (if-let [user (d/entity datomic-db [:user/email (olyp-json "email")])]
        (if (authentication-query/valid-password? user (olyp-json "password"))
          {:authenticated-user user}
          [false {:olyp-unprocessable-entity-msg (cheshire.core/generate-string {:password #{"Incorrect password"}})}])
        [false {:olyp-unprocessable-entity-msg (cheshire.core/generate-string {:email #{"No user found with this e-mail"}})}])))

   :handle-created
   (fn [ctx]
     (-> (:authenticated-user ctx)
         user-ent-to-public-value
         cheshire.core/generate-string))))

(def user-by-email-and-auth-token
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :exists?
   (fn [ctx]
     (if-let [user (d/entity (get-in ctx [:request :datomic-db]) [:user/email (URLDecoder/decode (get-in ctx [:request :route-params :user-email]))])]
       (if (= (:user/auth-token user) (get-in ctx [:request :route-params :auth-token]))
         {:authenticated-user user})))

   :handle-ok
   (fn [ctx]
     (-> (:authenticated-user ctx)
         user-ent-to-public-value
         cheshire.core/generate-string))))

(def password-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:put]
   :can-put-to-missing? false
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (liberator-util/get-user-entity-from-route-params :datomic-entity)
   :existed? (liberator-util/get-user-entity-from-route-params :datomic-entity)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator user-factory/validate-password-change))

   :put!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json datomic-entity]}]
     (-> (user-factory/change-password (olyp-json "password") datomic-entity datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (-> (:datomic-entity ctx)
         user-ent-to-public-value
         cheshire.core/generate-string))))
