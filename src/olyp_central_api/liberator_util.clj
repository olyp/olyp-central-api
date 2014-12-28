(ns olyp-central-api.liberator-util
  (:require [datomic.api :as d]
            [cheshire.core]
            [liberator.core :refer [resource]])
  (:import [com.fasterxml.jackson.core JsonParseException]))

(defn ctx-for-entity [ent]
  {:datomic-entity ent
   :datomic-db-after (d/entity-db ent)})

(defn ctx-for-tx-res [tx-res]
  {:datomic-db-after (:db-after tx-res)})

(defn get-datomic-db
  "Get the Datomic DB from the request as of before the handler was called, or
   directly from the context as of after any change that was made."
  [ctx]
  (or (:datomic-db-after ctx) (-> ctx :request :datomic-db)))

(defn etag-from-datomic [ctx]
  (d/basis-t (get-datomic-db ctx)))

(defn last-modified-from-datomic-db-inner [db]
  (->> db
       d/basis-t
       d/t->tx
       (d/entity db)
       :db/txInstant))

(defn last-modified-from-datomic-db [ctx]
  (let [db (get-datomic-db ctx)]
    (last-modified-from-datomic-db-inner db)))

(defn last-modified-from-datomic-entity [ctx]
  (let [entity (:datomic-entity ctx)]
    (->> entity
         (d/entity-db)
         (last-modified-from-datomic-db-inner))))

(defn comp-decision [^Boolean truthyness fns]
  (fn [initial-ctx]
    (reduce
     (fn [ctx decision-fn]
       (let [decision (decision-fn ctx)
             result (if (vector? decision) (first decision) decision)
             context-update (if (vector? decision) (second decision) decision)]
         (if (= truthyness (boolean result))
           (liberator.core/update-context ctx context-update)
           (reduced decision))))
     initial-ctx
     fns)))

(defn comp-pos-decision [& fns]
  (comp-decision true fns))

(defn comp-neg-decision [& fns]
  (comp-decision false fns))

(defn make-json-validator [validator]
  (fn [ctx]
    (if-let [json (:olyp-json ctx)]
      (let [err (validator json)]
        (if (empty? err)
          true
          [false {:olyp-unprocessable-entity-msg (cheshire.core/generate-string err)}]))
      true)))

(def potentially-unprocessable-methods #{:post :put})

(defn get-unprocessable-entity-msg [^JsonParseException e]
  (let [location (.getLocation e)
        original-message (.getOriginalMessage e)]
    (cheshire.core/generate-string
     {:error (str "JSON parse error! Location: " location ". Error: " original-message)
      :location {:line (.getLineNr location) :col (.getColumnNr location) :source-ref (str (.getSourceRef location))}
      :original-message original-message})))

(defn processable-json? [{{:keys [body request-method]} :request}]
  (if (contains? potentially-unprocessable-methods request-method)
    (try
      {:olyp-json (-> body slurp cheshire.core/parse-string)}
      (catch JsonParseException e
        [false {:olyp-unprocessable-entity-msg (get-unprocessable-entity-msg e)}]))
    true))

(defn handle-unprocessable-entity [ctx]
  (:olyp-unprocessable-entity-msg ctx))
