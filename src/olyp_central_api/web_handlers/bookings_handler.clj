(ns olyp-central-api.web-handlers.bookings-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.bookings-factory :as bookings-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [cheshire.core]
            [liberator.core :refer [resource]])
  (:import [java.util UUID]
           [org.joda.time DateTime DateTimeZone]
           [org.joda.time.format DateTimeFormat ISODateTimeFormat]))

(defn bookable-room-to-public-value [ent]
  {:id (str (:bookable-room/public-id ent))
   :name (:bookable-room/name ent)})

(defn booking-ent-to-public-value [ent]
  {:from (.print (ISODateTimeFormat/dateTime) (DateTime. (:room-booking/from ent)))
   :to (.print (ISODateTimeFormat/dateTime) (DateTime. (:room-booking/to ent)))
   :user {:id (str (:user/public-id ent))
          :email (:user/email ent)
          :name (:user/name ent)}
   :bookable-room (bookable-room-to-public-value (:room-booking/bookable-room ent))})

(def bookings-for-user-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post]
   :processable? (liberator-util/comp-pos-decision
                  liberator-util/processable-json?
                  (fn [ctx] {:olyp-json (bookings-factory/enhance-json (:olyp-json ctx))})
                  (liberator-util/make-json-validator bookings-factory/validate-booking))
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :exists? (liberator-util/get-user-entity-from-route-params :olyp-user)

   :post!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-user]}]
     (-> olyp-json
         (bookings-factory/create-booking olyp-user datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-created
   (fn [{:keys [datomic-entity]}]
     (-> datomic-entity
         booking-ent-to-public-value
         cheshire.core/generate-string))))

(def bookable-rooms-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (cheshire.core/generate-string
        (map
         (fn [[e]]
           (bookable-room-to-public-value (d/entity db e)))
         (d/q '[:find ?e :where [?e :bookable-room/public-id]] db)))))))

(def bookable-room-date-format (-> (DateTimeFormat/forPattern "dd.MM.yyyy")
                                   (.withZone (DateTimeZone/forID "Europe/Oslo"))))

(def bookings-for-bookable-room-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :processable? (fn [ctx]
                   (let [date-str (get-in ctx [:request :route-params :date])]
                     (try
                       {:olyp-booking-date (.parseDateTime bookable-room-date-format date-str)}
                       (catch IllegalArgumentException e
                         [false {:olyp-unprocessable-entity-msg (cheshire.core/generate-string {"msg" (str "Invalid date format. Got " date-str ", error was " (.getMessage e))})}]))))
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :exists?
   (fn [ctx]
     (if-let [bookable-room (d/entity
                             (liberator-util/get-datomic-db ctx)
                             [:bookable-room/public-id (UUID/fromString (get-in ctx [:request :route-params :bookable-room-id]))])]
       {:olyp-bookable-room bookable-room}))

   :handle-ok
   (fn [{:keys [olyp-booking-date olyp-bookable-room] :as ctx}]
     (let [db (liberator-util/get-datomic-db ctx)
           bookings (set
                     (concat
                      (d/q
                       '[:find [?room-booking ...]
                         :in $ ?from ?bookable-room-e
                         :where
                         [?room-booking :room-booking/bookable-room ?bookable-room-e]
                         [?room-booking :room-booking/from ?room-booking-from]
                         [(< ?from ?room-booking-from)]]
                       db
                       (.toDate olyp-booking-date)
                       (:db/id olyp-bookable-room))
                      (d/q
                       '[:find [?room-booking ...]
                         :in $ ?to ?bookable-room-e
                         :where
                         [?room-booking :room-booking/bookable-room ?bookable-room-e]
                         [?room-booking :room-booking/to ?room-booking-to]
                         [(> ?to ?room-booking-to)]]
                       db
                       (.toDate (.plusDays olyp-booking-date 7))
                       (:db/id olyp-bookable-room))))]
       (map #(booking-ent-to-public-value (d/entity db %)) bookings)))))
