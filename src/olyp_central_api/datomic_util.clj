(ns olyp-central-api.datomic-util
  (:require [datomic.api :as d]))

(defn get-most-recent-t [ent]
  (let [db (d/entity-db ent)]
    (d/tx->t (ffirst (d/q '[:find (max ?tx) :in $ ?eid :where [?eid _ _ ?tx]]  db (:db/id ent))))))
