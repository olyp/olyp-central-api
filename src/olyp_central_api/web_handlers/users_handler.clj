(ns olyp-central-api.web-handlers.users-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.user-factory :as user-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [cheshire.core])
  (:import [java.util UUID]))

(defn user-ent-to-public-value [ent]
  {:id (str (:user/public-id ent))
   :email (:user/email ent)
   :name (:user/name ent)})

(def users-collection-handler
  (liberator-util/datomic-json-collection-resource
   :post! (fn [{{:keys [body datomic-conn]} :request :as ctx}]
            (-> (:olyp-json ctx)
                (user-factory/create-user datomic-conn)
                liberator-util/ctx-for-entity))
   :handle-created (fn [ctx]
                     (cheshire.core/generate-string
                      (user-ent-to-public-value (:datomic-entity ctx))))
   :handle-ok (fn [ctx]
                (let [db (liberator-util/get-datomic-db ctx)]
                  (cheshire.core/generate-string
                   (map
                    (fn [[u]]
                      (user-ent-to-public-value (d/entity db u)))
                    (d/q '[:find ?u :where [?u :user/public-id]] db)))))))

(def user-handler
  (liberator-util/datomic-json-resource
   :put! (fn [{{:keys [body datomic-conn]} :request :keys [olyp-json datomic-entity]}]
           (-> (user-factory/update-user olyp-json datomic-entity datomic-conn)
               liberator-util/ctx-for-entity))
   :delete! (fn [{{:keys [datomic-conn]} :request :keys [datomic-entity]}]
              (-> (user-factory/delete-user datomic-entity datomic-conn)
                  liberator-util/ctx-for-tx-res))
   :exists? (fn [ctx]
              (let [user-id (UUID/fromString (get-in ctx [:request :route-params :user-id]))
                    db (liberator-util/get-datomic-db ctx)]
                (if-let [u-eid (ffirst (d/q '[:find ?u :in $ ?uid :where [?u :user/public-id ?uid]] db user-id))]
                  {:datomic-entity (d/entity db u-eid)})))
   :handle-ok (fn [ctx]
                (-> (:datomic-entity ctx)
                    user-ent-to-public-value
                    cheshire.core/generate-string))))
