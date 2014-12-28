(ns olyp-central-api.components.web
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server]
            [clojure.tools.logging :as log]
            olyp-central-api.web-handler)
  (:import [java.util.concurrent TimeUnit]))

(defn ring-handler [req]
  {:status 200 :body "yay"})

(defrecord Web [port]
  component/Lifecycle

  (start [component]
    (let [handler (olyp-central-api.web-handler/create-handler (-> component :database))
          server (org.httpkit.server/run-server handler {:port port})]
      (log/info (str "Started web server on port " port))
      (assoc component
        :server server)))
  (stop [component]
    (@(:server component) :timeout (.convert TimeUnit/MILLISECONDS 1 TimeUnit/SECONDS))
    (dissoc component :server)))

(defn create-web [{:keys [port]}]
  (Web. port))
