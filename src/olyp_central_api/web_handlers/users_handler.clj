(ns olyp-central-api.web-handlers.users-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.user-factory :as user-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [cheshire.core]))

(defn user-ent-to-public-value [ent]
  {:id (str (:user/public-id ent))
   :email (:user/email ent)
   :name (:user/name ent)})

(def users-collection-handler
  (liberator-util/datomic-json-collection-resource
   :post! (fn [{{:keys [body datomic-conn]} :request :as ctx}]
            (-> (:olyp-json ctx)
                (user-factory/create-user datomic-conn)
                liberator-util/ctx-after-single-entity-insert))
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
