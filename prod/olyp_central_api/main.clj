(ns olyp-central-api.main
  (:gen-class)
  (:require olyp-central-api.app
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [io.rkn.conformity :as conformity]
            [datomic.api :as d]
            [clojure.tools.logging :as log])
  (:import java.io.PushbackReader))

(defn generate-initial-seed-tx []
  [{:db/id (d/tempid :db.part/user)
    :reservable-room/public-id (d/squuid)
    :reservable-room/name "Rom 5"}])

(defn -main [& args]
  (let [app
        (with-open [r (io/reader (str (first args)))]
          (->> (read (java.io.PushbackReader. r))
               (olyp-central-api.app/create-system)
               (component/start)))]
    (log/info "Creating prod seed data")
    (conformity/ensure-conforms
     (get-in app [:database :datomic-conn])
     {:olyp/initial-seed-data {:txes [(generate-initial-seed-tx)]}} [:olyp/initial-seed-data])))
