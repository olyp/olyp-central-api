(ns olyp-central-api.web-handlers.reservations-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.reservations-factory :as reservations-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [cheshire.core]
            [liberator.core :refer [resource]])
  (:import [org.joda.time DateTime DateTimeZone]
           [org.joda.time.format DateTimeFormat ISODateTimeFormat]))

(defn reservable-room-to-public-value [ent]
  {:id (str (:reservable-room/public-id ent))
   :name (:reservable-room/name ent)})

(defn reservation-ent-to-public-value [ent]
  {:id (str (:room-reservation/public-id ent))
   :from (.print (ISODateTimeFormat/dateTime) (DateTime. (:room-reservation/from ent)))
   :to (.print (ISODateTimeFormat/dateTime) (DateTime. (:room-reservation/to ent)))
   :booking (if-let [booking-ent (:room-reservation/ref ent)]
              {:id (str (:room-booking/public-id booking-ent))
               :is_invoiced (:room-booking/is-invoiced booking-ent)
               :user (if-let [user-ent (:room-booking/user booking-ent)]
                       {:id (str (:user/public-id user-ent))
                        :email (:user/email user-ent)
                        :name (:user/name user-ent)})})
   :reservable_room (reservable-room-to-public-value (:room-reservation/reservable-room ent))})

(def bookings-for-user-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (liberator-util/get-user-entity-from-route-params :olyp-user)

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (fn [ctx] {:olyp-json (reservations-factory/enhance-json (:olyp-json ctx))})
    (liberator-util/make-json-validator reservations-factory/validate-booking))

   :post!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-user]}]
     (-> olyp-json
         (reservations-factory/create-booking olyp-user datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [{:keys [datomic-entity]}]
     (-> datomic-entity
         reservation-ent-to-public-value
         cheshire.core/generate-string))))

(def reservable-rooms-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[e]]
           (reservable-room-to-public-value (d/entity db e)))
         (d/q '[:find ?e :where [?e :reservable-room/public-id]] db)))))))

(def reservable-room-date-format (-> (DateTimeFormat/forPattern "dd.MM.yyyy")
                                   (.withZone (DateTimeZone/forID "Europe/Oslo"))))

(defn get-reservations [db from to reservable-room]
  (if (not= -1 (compare from to))
    (throw (Exception. "Cannot get reservations, from date is not earlier than to date.")))

  (d/q
   '[:find [?room-reservation ...]
     :in $ ?from ?to ?reservable-room-e
     :where
     [?room-reservation :room-reservation/reservable-room ?reservable-room-e]
     [?room-reservation :room-reservation/from ?room-reservation-from]
     [?room-reservation :room-reservation/to ?room-reservation-to]
     ;; http://c2.com/cgi/wiki?TestIfDateRangesOverlap
     [(<= ?room-reservation-from ?room-reservation-to)]
     [(<= ?room-reservation-from ?to)]
     [(<= ?from ?room-reservation-to)]]
   db from to (:db/id reservable-room)))

(def reservations-for-reservable-room-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (fn [ctx]
     (let [date-str (get-in ctx [:request :route-params :date])]
       (try
         {:olyp-reservation-date (.parseDateTime reservable-room-date-format date-str)}
         (catch IllegalArgumentException e
           [false {:olyp-unprocessable-entity-msg (cheshire.core/generate-string {"msg" (str "Invalid date format. Got " date-str ", error was " (.getMessage e))})}]))))

   :exists?
   (fn [ctx]
     (if-let [reservable-room (d/entity
                               (liberator-util/get-datomic-db ctx)
                               [:reservable-room/public-id (get-in ctx [:request :route-params :reservable-room-id])])]
       {:olyp-reservable-room reservable-room}))

   :handle-ok
   (fn [{:keys [olyp-reservation-date olyp-reservable-room] :as ctx}]
     (let [db (liberator-util/get-datomic-db ctx)]
       (map
        #(reservation-ent-to-public-value (d/entity db %))
        (get-reservations db (.toDate olyp-reservation-date) (.toDate (-> olyp-reservation-date (.plusDays 7) (.minusSeconds 1))) olyp-reservable-room))))))

(def booking-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get :delete]
   :processable? liberator-util/processable-json?
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :exists?
   (liberator-util/comp-pos-decision
    (liberator-util/get-user-entity-from-route-params :olyp-user)
    (fn [ctx]
      (let [db (liberator-util/get-datomic-db ctx)]
        (if-let [reservation (d/q
                              '[:find ?reservation .
                                :in $ ?booking-pubid
                                :where
                                [?reservation :room-reservation/ref ?booking]
                                [?booking :room-booking/public-id ?booking-pubid]]
                              (liberator-util/get-datomic-db ctx)
                              (get-in ctx [:request :route-params :booking-id]))]
          {:olyp-reservation (d/entity db reservation)}))))

   :delete!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-reservation]}]
     (-> (reservations-factory/delete-booking olyp-reservation datomic-conn)
         liberator-util/ctx-for-tx-res))

   :handle-ok
   (fn [ctx]
     (-> (:olyp-reservation ctx)
         reservation-ent-to-public-value
         cheshire.core/generate-string))))

(def recently-deleted-bookings-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (->>
        (d/q
         '[:find ?e ?tx
           :where
           [?e :room-reservation/public-id _ ?tx false]]
         (d/history db))
        (map (fn [[eid tx-eid]]
               (reservation-ent-to-public-value
                (d/entity (d/as-of db (dec (d/tx->t tx-eid))) eid))))
        (take 20)
        (cheshire.core/generate-string))))))
