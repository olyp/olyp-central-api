(ns olyp-central-api.liberator-util
  (:require [datomic.api :as d]
            [cheshire.core]
            [liberator.core :refer [resource]])
  (:import [com.fasterxml.jackson.core JsonParseException]))

(defn ctx-after-single-entity-insert [ent]
  {:datomic-entity ent
   :datomic-db-after (d/entity-db ent)})

(defn get-datomic-db
  "Get the Datomic DB from the request as of before the handler was called, or
   directly from the context as of after any change that was made."
  [ctx]
  (or (:datomic-db-after ctx) (-> ctx :request :datomic-db)))

(defn etag-from-datomic [ctx]
  (d/basis-t (get-datomic-db ctx)))

(defn last-modified-from-datomic [ctx]
  (let [db (get-datomic-db ctx)]
    (->> db
         d/basis-t
         d/t->tx
         (d/entity db)
         :db/txInstant)))

(def potentially-unprocessable-methods #{:post :put})

(def default-datomic-json-collection-resource
  {:available-media-types ["application/json"]
   :allowed-methods [:post :get]
   :etag etag-from-datomic
   :last-modified last-modified-from-datomic

   :processable?
   (fn [{{:keys [body request-method]} :request}]
     (if (contains? potentially-unprocessable-methods request-method)
       (try
         {:olyp-json (-> body slurp cheshire.core/parse-string)}
         (catch JsonParseException e
           [false {:olyp-json-parse-exception e}]))
       true))

   :handle-unprocessable-entity
   (fn [{e :olyp-json-parse-exception}]
     (let [location (.getLocation e)
           original-message (.getOriginalMessage e)]
       (cheshire.core/generate-string
        {:error (str "JSON parse error! Location: " location ". Error: " original-message)
         :location {:line (.getLineNr location) :col (.getColumnNr location) :source-ref (str (.getSourceRef location))}
         :original-message original-message})))})

(defn datomic-json-collection-resource [& kvs]
  (resource (merge default-datomic-json-collection-resource (apply hash-map kvs))))
