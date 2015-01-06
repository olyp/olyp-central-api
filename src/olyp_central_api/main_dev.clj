(ns olyp-central-api.main-dev
  (:gen-class)
  (:require olyp-central-api.app
            [com.stuartsierra.component :as component]
            [io.rkn.conformity :as conformity]
            [datomic.api :as d]
            [olyp-central-api.factories.user-factory :as user-factory]
            [clojure.tools.logging :as log]))

(defn generate-initial-seed-tx []
  [{:db/id (d/tempid :db.part/user)
    :reservable-room/public-id (str (d/squuid))
    :reservable-room/name "Rom 5"}

   ;; TODO: Only create this data in dev mode
   {:db/id (d/tempid :db.part/user -1)
    :customer/public-id (str (d/squuid))
    :customer/type :customer.type/company
    :customer/brreg-id "123456789"
    :customer/name "Quentin Inc."
    :customer/zip "1410"
    :customer/city "Kolbotn"}

   {:db/id (d/tempid :db.part/user)
    :user/public-id "54a495b3-3a20-4d37-88bf-9a433d66db35"
    :user/email "quentin@test.com"
    :user/name "Quentin Test"
    :user/customer (d/tempid :db.part/user -1)
    :user/bcrypt-password (user-factory/encrypt-password "test")
    :user/auth-token "78888a117972edc201892c53498321fa5bfeb31aec5e3bd722f127b1cb0c6757"}
])

(defn -main [& args]
  (let [app
        (->>
         {:database {:type :datomic-mem}
          :web {:port 3000}}
         (olyp-central-api.app/create-system)
         (component/start))]
    (log/info "Creating dev seed data")
    (conformity/ensure-conforms
     (get-in app [:database :datomic-conn])
     {:olyp/initial-seed-data {:txes [(generate-initial-seed-tx)]}} [:olyp/initial-seed-data])))
