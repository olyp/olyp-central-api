(ns olyp-central-api.components.web
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent TimeUnit]))

(defn ring-handler [req]
  {:status 200 :body "yay"})

(defrecord Web [port]
  component/Lifecycle

  (start [component]
    (log/info (str "Starting web server on port " port))
    (assoc component
      :server (org.httpkit.server/run-server ring-handler {:port port})))
  (stop [component]
    (@(:server component) :timeout (.convert TimeUnit/MILLISECONDS 1 TimeUnit/SECONDS))
    (dissoc component :server)))

(defn create-web [{:keys [port]}]
  (Web. port))
