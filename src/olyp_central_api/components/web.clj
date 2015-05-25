(ns olyp-central-api.components.web
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server]
            [clojure.tools.logging :as log]
            olyp-central-api.web-handler)
  (:import [java.util.concurrent TimeUnit]))

(defn ring-handler [req]
  {:status 200 :body "yay"})

(defrecord Web [ip port]
  component/Lifecycle

  (start [component]
    (let [handler (olyp-central-api.web-handler/create-handler (-> component :database))
          server (org.httpkit.server/run-server handler {:port port
                                                         :ip (or ip "0.0.0.0")})]
      (log/info (str "Started web server on port " port))
      (assoc component
        :server server)))
  (stop [component]
    (if-let [server (:server component)]
      (do
        (server :timeout (.convert TimeUnit/MILLISECONDS 1 TimeUnit/SECONDS))
        component :server)
      component)))

(defn create-web [{:keys [ip port]}]
  (Web. ip port))
