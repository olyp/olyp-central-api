(ns olyp-central-api.factories.user-factory
  (:require [datomic.api :as d]))

(defn create-user [data datomic-conn]
  (let [user-tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 [[:db/add user-tempid :user/email "august@augustl.com"]
                  [:db/add user-tempid :user/name "August Lilleaas"]
                  [:db/add user-tempid :user/zip "1410"]
                  [:db/add user-tempid :user/city "Kolbotm"]])]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) user-tempid))))
