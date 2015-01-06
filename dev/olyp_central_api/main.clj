(ns olyp-central-api.main
  (:gen-class)
  (:require olyp-central-api.app
            [com.stuartsierra.component :as component]))

(defn -main [& args]
  (let [app
        (->>
         {:database {:type :datomic-mem}
          :web {:port 3000}}
         (olyp-central-api.app/create-system)
         (component/start)
         (def app))]))
