(ns olyp-central-api.main-prod
  (:gen-class)
  (:require olyp-central-api.app
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [io.rkn.conformity :as conformity]
            [datomic.api :as d]
            [clojure.tools.logging :as log]
            clojure.tools.nrepl.server)
  (:import java.io.PushbackReader))

(defn generate-initial-seed-tx []
  [{:db/id (d/tempid :db.part/user)
    :reservable-room/public-id (str (d/squuid))
    :reservable-room/name "Rom 5"}])

(declare app)

(defn -main [& args]
  (let [config (with-open [r (io/reader (str (first args)))]
                 (read (java.io.PushbackReader. r)))]
    (log/info "Starting app")
    (def app
      (->> config
           (olyp-central-api.app/create-system)
           (component/start)))
    (let [nrepl-port (:nrepl-port config)]
      (log/info (str "Starting nrepl at port " nrepl-port))
      (clojure.tools.nrepl.server/start-server :port nrepl-port))
    (log/info "Creating prod seed data")
    (conformity/ensure-conforms
     (get-in app [:database :datomic-conn])
     {:olyp/initial-seed-data {:txes [(generate-initial-seed-tx)]}} [:olyp/initial-seed-data])))
