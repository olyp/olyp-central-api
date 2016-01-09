(ns olyp-central-api.dev-app
  (:require [com.stuartsierra.component :as component]
            [olyp-central-api.app :as app]
            [datomic.api :as d]
            [olyp-central-api.factories.user-factory :as user-factory]
            [io.rkn.conformity :as conformity]))

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

(defrecord DatomicSeedComponent []
  component/Lifecycle

  (start [component]
    (conformity/ensure-conforms
      (get-in component [:dev-app :app :database :datomic-conn])
      {:olyp/initial-seed-data {:txes [(generate-initial-seed-tx)]}} [:olyp/initial-seed-data])
    component)

  (stop [component]
    component))

(defrecord DevAppComponent [opts]
  component/Lifecycle

  (start [component]
    (if (contains? component :app)
      component
      (assoc component :app (-> opts app/create-system component/start))))

  (stop [component]
    (if-let [app (:app component)]
      (do
        (component/stop app)
        (dissoc component :app))
      component)))

(defn create-system [opts]
  (component/system-map
   :datomic-seed (component/using
                  (DatomicSeedComponent.)
                  [:dev-app])
   :dev-app (DevAppComponent. opts)))

