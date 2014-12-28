(ns olyp-central-api.web-handlers.users-handler
  (:require [liberator.core :refer [resource]]
            [datomic.api :as d]
            [olyp-central-api.factories.user-factory :as user-factory]
            [cheshire.core]))

(defn get-liberator-ctx-after-single-entity-insert [ent]
  {::entity ent
   ::datomic-db-after (d/entity-db ent)})

(defn get-datomic-db-from-liberator-ctx
  "Get the Datomic DB from the request as of before the handler was called, or
   directly from the context as of after any change that was made."
  [ctx]
  (or (::datomic-db-after ctx) (-> ctx :request :datomic-db)))

(def users-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post :get]
   :etag (fn [ctx] (d/basis-t (get-datomic-db-from-liberator-ctx ctx)))
   :last-modified (fn [ctx]
                    (let [db (get-datomic-db-from-liberator-ctx ctx)]
                      (->> db
                           d/basis-t
                           d/t->tx
                           (d/entity db)
                           :db/txInstant)))
   :post! (fn [{{:keys [body datomic-conn datomic-db]} :request}]
            (-> (slurp body)
                (user-factory/create-user datomic-conn)
                get-liberator-ctx-after-single-entity-insert))
   :post-redirect? (fn [ctx] {:location "/users/1"})
   :handle-ok (fn [ctx]
                (let [db (get-datomic-db-from-liberator-ctx ctx)]
                  (cheshire.core/generate-string
                   (map
                    (fn [[u]]
                      (let [ent (d/entity db u)]
                        {:email (:user/email ent) :name (:user/name ent)}))
                    (d/q '[:find ?u :where [?u :user/email]] db)))))))
