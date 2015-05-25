(ns olyp-central-api.main-dev
  (:gen-class)
  (:require olyp-central-api.dev-app
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]))

(defn -main [& args]
  (->>
   {:database {:type :datomic-mem}
    :web {:port 3000}}
   (olyp-central-api.dev-app/create-system)
   (component/start)))
