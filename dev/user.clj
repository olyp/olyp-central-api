(ns user
  (:require [reloaded.repl :refer [system init start stop go reset]]
            [olyp-central-api.dev-app :as dev-app]))

(reloaded.repl/set-init! #(dev-app/create-system (merge
                                                   {:database {:type :datomic :host "localhost" :port 4334 :db-name "olyp-central-api"}
                                                    :web {:port 3000}}
                                                   (-> "config.edn" slurp clojure.edn/read-string))))
