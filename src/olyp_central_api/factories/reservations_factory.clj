(ns olyp-central-api.factories.reservations-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [java.util Date]
           [org.joda.time DateTime DateTimeZone]
           [org.joda.time.format DateTimeFormat]))

(defn validate-type [attr type]
  (v/validate-with-predicate attr #(instance? type (get % attr)) :message (str "Has to be of type " type)))

(defn convert-to-date [json attr]
  (if (contains? json attr)
    (assoc json attr (-> (get json attr) DateTime/parse .toDate))
    json))

(defn enhance-json [json]
  (-> json
      (convert-to-date "from")
      (convert-to-date "to")))

(def validate-booking
  (v/validation-set
   (v/presence-of "from")
   (validate-type "from" Date)
   (v/presence-of "to")
   (validate-type "to" Date)
   (v/validate-with-predicate "from" #(> 0 (compare (% "from") (% "to"))) :message "Can't create a booking that ends before it starts")
   (v/presence-of "reservable_room_id")
   (v/all-keys-in #{"from" "to" "reservable_room_id"})))

(defn get-caused-exceptions-chain [^Exception e]
  (if (nil? e)
    nil
    (cons e (lazy-seq (get-caused-exceptions-chain (.getCause e))))))

(def conflicting-reservation-format (-> (DateTimeFormat/forPattern "dd.MM.yyyy, HH:mm")
                                        (.withZone (DateTimeZone/forID "Europe/Oslo"))))

(defn create-booking [data user datomic-conn]
  (try
    (let [tempid (d/tempid :db.part/user)
          ref-tempid (d/tempid :db.part/user)
          tx-res @(d/transact
                   datomic-conn
                   [[:db/add tempid :room-reservation/public-id (str (d/squuid))]
                    [:set-room-reservation-range tempid [:reservable-room/public-id (data "reservable_room_id")] (data "from") (data "to")]
                    [:db/add tempid :room-reservation/ref ref-tempid]
                    [:db/add ref-tempid :room-booking/public-id (str (d/squuid))]
                    [:db/add ref-tempid :room-booking/user (:db/id user)]])]
      (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid)))
    (catch Exception e
      (if-let [exception-info (->> (get-caused-exceptions-chain e)
                                   (filter #(instance? clojure.lang.ExceptionInfo %))
                                   (first)
                                   ex-data)]
        (when (= (:type exception-info) :set-room-reservation-range)
          (throw (ex-info (str "The reservation crashes with another reservation at " (.print conflicting-reservation-format (DateTime. (:conflicting-reservation-from exception-info)))) {:olyp-validation-error true}))))
      (throw e))))

(defn delete-booking [ent datomic-conn]
  @(d/transact datomic-conn [[:db.fn/retractEntity (:db/id ent)]]))
