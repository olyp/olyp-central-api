(defproject olyp-central-api "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.datomic/datomic-free "0.9.5067" :exclusions [org.slf4j/slf4j-nop]]
                 [com.stuartsierra/component "0.2.2"]
                 [http-kit "2.1.16"]
                 [crypto-password "0.1.3"]
                 [io.rkn/conformity "0.3.3"]
                 [bidi "1.12.0"]
                 [liberator "0.12.2"]
                 [cheshire "5.3.1"]
                 [com.novemberain/validateur "2.4.2"]
                 [crypto-random "1.2.0"]
                 [joda-time/joda-time "2.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [ch.qos.logback/logback-classic "1.1.2"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[reloaded.repl "0.1.0"]]
                   :main olyp-central-api.main-dev}
             :uberjar {:source-paths ["prod"]
                       :main olyp-central-api.main-prod
                       :aot [olyp-central-api.main-prod]
                       :dependencies [[org.clojure/tools.nrepl "0.2.7"]]}})
