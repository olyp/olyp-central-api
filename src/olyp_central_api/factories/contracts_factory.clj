(ns olyp-central-api.factories.contracts-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v]))

(def contract-base-attrs #{"brreg_id" "name" "address" "zip" "city" "contact_person_name" "contact_person_email" "contact_person_phone"})

(def contract-base-validators
  [(v/presence-of "brreg_id")
   (v/presence-of "name")
   (v/presence-of "zip")
   (v/format-of "zip" :format #"^\d\d\d\d$" :message "must be a four digit number")
   (v/presence-of "city")])

(def validate-contract-on-create
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in contract-base-attrs)]
    contract-base-validators)))

(def validate-contract-on-update
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in (clojure.set/union #{"version"} contract-base-attrs))
     (v/presence-of "version")]
    contract-base-validators)))

(defn create-contract [data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:db/add tempid :contract/public-id (str (d/squuid))]
                   [:db/add tempid :contract/brreg-id (data "brreg_id")]
                   [:db/add tempid :contract/name (data "name")]
                   [:db/add tempid :contract/zip (data "zip")]
                   [:db/add tempid :contract/city (data "city")]]
                  (filter
                   (fn [[f e a v]] (not (nil? v)))
                   [[:db/add tempid :contract/address (data "address")]
                    [:db/add tempid :contract/contact-person-name (data "contact_person_name")]
                    [:db/add tempid :contract/contact-person-email (data "contact_person_email")]
                    [:db/add tempid :contract/contact-person-phone (data "contact_person_phone")]])))]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid))))

(defn update-contract [data ent datomic-conn]
  (let [contract-id (:db/id ent)
        tx-res @(d/transact
                 datomic-conn
                 [[:optimistic-add (data "version") contract-id
                   {:contract/brreg-id (data "brreg_id")
                    :contract/name (data "name")
                    :contract/zip (data "zip")
                    :contract/city (data "city")
                    :contract/address (data "address")
                    :contract/contact-person-name (data "contact_person_name")
                    :contract/contact-person-email (data "contact_person_email")
                    :contract/contact-person-phone (data "contact_person_phone")}]])]
    (d/entity (:db-after tx-res) contract-id)))
