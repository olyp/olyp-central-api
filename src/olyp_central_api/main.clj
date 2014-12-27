(ns olyp-central-api.main
  (:gen-class)
  (:require olyp-central-api.app
            [com.stuartsierra.component :as component]))

(declare app)

(defn -main [& args]
  (->>
   {:database {:type :datomic-mem}
    :web {:port 3000}}
   (olyp-central-api.app/create-system)
   (component/start)
   (def app)))


