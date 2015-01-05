(ns olyp-central-api.factories.contracts-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v]))

(def contract-base-attrs #{"name" "address" "zip" "city" "contact_person_email" "contact_person_phone"})
(def company-contract-base-attrs (clojure.set/union #{"brreg_id" "contact_person_name"} contract-base-attrs))
(def person-contract-base-attrs contract-base-attrs)

(def contract-base-validators
  [(v/presence-of "name")
   (v/presence-of "zip")
   (v/format-of "zip" :format #"^\d{4}$" :message "must be a four digit number")
   (v/presence-of "city")])

(def company-contract-base-validators
  (concat
   [(v/presence-of "brreg_id")
    (v/format-of "brreg_id" :format #"^\d{9}$" :message "must be a nine digit number")]
   contract-base-validators))

(def person-contract-base-validators
  contract-base-validators)

(def validate-company-contract-on-create
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in company-contract-base-attrs)]
    company-contract-base-validators)))

(def validate-person-contract-on-create
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in person-contract-base-attrs)]
    person-contract-base-validators)))

(def validate-company-contract-on-update
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in (clojure.set/union #{"version"} company-contract-base-attrs))
     (v/presence-of "version")]
    company-contract-base-validators)))

(def validate-person-contract-on-update
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in (clojure.set/union #{"version"} person-contract-base-attrs))
     (v/presence-of "version")]
    person-contract-base-validators)))

(defn create-company-contract [data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:db/add tempid :contract/public-id (str (d/squuid))]
                   [:db/add tempid :contract/type :contract.type/company]
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

(defn create-person-contract [data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:db/add tempid :contract/public-id (str (d/squuid))]
                   [:db/add tempid :contract/type :contract.type/person]
                   [:db/add tempid :contract/name (data "name")]
                   [:db/add tempid :contract/zip (data "zip")]
                   [:db/add tempid :contract/city (data "city")]]
                  (filter
                   (fn [[f e a v]] (not (nil? v)))
                   [[:db/add tempid :contract/address (data "address")]
                    [:db/add tempid :contract/contact-person-email (data "contact_person_email")]
                    [:db/add tempid :contract/contact-person-phone (data "contact_person_phone")]])))]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid))))

(defn update-company-contract [data ent datomic-conn]
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

(defn update-person-contract [data ent datomic-conn]
  (let [contract-id (:db/id ent)
        tx-res @(d/transact
                 datomic-conn
                 [[:optimistic-add (data "version") contract-id
                   {:contract/name (data "name")
                    :contract/zip (data "zip")
                    :contract/city (data "city")
                    :contract/address (data "address")
                    :contract/contact-person-email (data "contact_person_email")
                    :contract/contact-person-phone (data "contact_person_phone")}]])]
    (d/entity (:db-after tx-res) contract-id)))
