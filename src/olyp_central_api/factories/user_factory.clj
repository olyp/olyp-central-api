(ns olyp-central-api.factories.user-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v]
            [crypto.password.bcrypt]
            [crypto.random]))

(def user-base-validators
  [(v/presence-of "email")
   (v/format-of "email" :format #"^.*?@.*?\..*?$" :message "must be an e-mail address")
   (v/presence-of "name")
   (v/presence-of "zip")
   (v/format-of "zip" :format #"^\d\d\d\d$" :message "must be a four digit number")
   (v/presence-of "city")])

(def user-creation-validators
  [(v/presence-of "password")])

(def user-update-validators
  [(v/presence-of "version")])

(def validate-user-on-create
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in #{"email" "name" "zip" "city" "password"})]
    user-base-validators
    user-creation-validators)))

(def validate-user-on-update
  (apply
   v/validation-set
   (concat
    [(v/all-keys-in #{"email" "name" "zip" "city" "version"})]
    user-base-validators
    user-update-validators)))

(def validate-password-change
  (v/validation-set
   (v/presence-of "password")
   (v/all-keys-in #{"password"})))

(defn encrypt-password [password]
  (crypto.password.bcrypt/encrypt password 11))

(defn create-user [data datomic-conn]
  (let [user-tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 [[:db/add user-tempid :user/public-id (d/squuid)]
                  [:db/add user-tempid :user/email (data "email")]
                  [:db/add user-tempid :user/name (data "name")]
                  [:db/add user-tempid :user/zip (data "zip")]
                  [:db/add user-tempid :user/city (data "city")]
                  [:db/add user-tempid :user/bcrypt-password (encrypt-password (data "password"))]
                  [:db/add user-tempid :user/auth-token (crypto.random/hex 32)]])]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) user-tempid))))

(defn update-user [data ent datomic-conn]
  (let [user-id (:db/id ent)
        tx-res @(d/transact
                 datomic-conn
                 [[:optimistic-add (data "version") user-id
                   {:user/email (data "email")
                    :user/name (data "name")
                    :user/zip (data "zip")
                    :user/city (data "city")}]])]
    (d/entity (:db-after tx-res) user-id)))

(defn delete-user [ent datomic-conn]
  @(d/transact datomic-conn [[:db.fn/retractEntity (:db/id ent)]]))

(defn change-password [password ent datomic-conn]
  (let [user-id (:db/id ent)
        tx-res @(d/transact
                 datomic-conn
                 [[:db/add user-id :user/bcrypt-password (encrypt-password password)]])]
    (d/entity (:db-after tx-res) user-id)))
