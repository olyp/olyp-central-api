(ns olyp-central-api.web-handlers.invoice-batches-handler
  (:require [datomic.api :as d]
            [olyp-central-api.liberator-util :as liberator-util]
            [olyp-central-api.datomic-util :as datomic-util]
            [cheshire.core]
            [liberator.core :refer [resource]]
            [olyp-central-api.web-handlers.customers-handler :as customers-handler]))

(defn batch-ent-to-public-value [batch]
  {:month (:invoice-batch/month batch)
   :invoices
   (map
    (fn [invoice]
      {:customer (customers-handler/customer-ent-to-public-value (:invoice/customer invoice))
       :invoice_number (:invoice/invoice-number invoice)
       :lines
       (map
        (fn [line]
          {:quantity (.toString (:invoice-line/quantity line))
           :unit_price (.toString (:invoice-line/unit-price line))
           :tax (:invoice-line/tax line)
           :product_code (:invoice-line/product-code line)
           :description (:invoice-line/description line)})
        (->> (d/q '[:find [?e ...] :in $ ?key :where [?e :invoice-line/invoice-key ?key]] (d/entity-db batch) (:invoice/key invoice))
             (map #(d/entity (d/entity-db batch) %))
             (sort-by :invoice-line/sort-order)))})
    (:invoice-batch/invoices batch))})

(defn batch-exists? [ctx]
  (let [db (liberator-util/get-datomic-db ctx)]
    (if-let [batch (d/entity db [:invoice-batch/public-id (get-in ctx [:request :route-params :batch-id])])]
      {:olyp-batch batch})))

(def invoice-batch-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :exists? batch-exists?
   :existed? batch-exists?

   :handle-ok
   (fn [ctx]
     (-> (:olyp-batch ctx)
         batch-ent-to-public-value
         cheshire.core/generate-string))))
