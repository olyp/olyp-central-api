(ns olyp-central-api.web-handlers.reservations-handler
  (:require [datomic.api :as d]
            [olyp-central-api.factories.reservations-factory :as reservations-factory]
            [olyp-central-api.liberator-util :as liberator-util]
            [cheshire.core]
            [liberator.core :refer [resource]]
            [olyp-central-api.web-handlers.customers-handler :as customers-handler]
            [olyp-central-api.factories.invoices-factory :as invoices-factory])
  (:import [org.joda.time DateTime DateTimeZone]
           [org.joda.time.format DateTimeFormat ISODateTimeFormat]))

(defn reservable-room-to-public-value [ent]
  {:id (str (:reservable-room/public-id ent))
   :name (:reservable-room/name ent)})

(defn reservation-ent-to-public-value [ent]
  {:id (:room-reservation/public-id ent)
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

(defn reservation-batch-ent-to-public-value [ent]
  {:id (:reservation-batch/public-id ent)
   :month (:reservation-batch/month ent)})

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
         '[:find ?e ?tx-created ?tx-deleted
           :where
           [?e :room-reservation/public-id _ ?tx-created true]
           [?e :room-reservation/public-id _ ?tx-deleted false]]
         (d/history db))
        (map (fn [[eid tx-created-eid tx-deleted-eid]]
               {:reservation (reservation-ent-to-public-value
                              (d/entity (d/as-of db (dec (d/tx->t tx-deleted-eid))) eid))
                :created_at (.print (ISODateTimeFormat/dateTime) (DateTime. (:db/txInstant (d/entity (d/as-of db (d/tx->t tx-created-eid)) tx-created-eid))))
                :deleted_at (.print (ISODateTimeFormat/dateTime) (DateTime. (:db/txInstant (d/entity (d/as-of db (d/tx->t tx-deleted-eid)) tx-deleted-eid))))}))
        (take 20)
        (cheshire.core/generate-string))))))

(def unbatched-bookings-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (->>
        (d/q
         '[:find [?e ...]
           :where
           [?e :room-reservation/public-id]]
         db)
        (map #(d/entity db %))
        (sort-by :room-reservation/public-id)
        (reverse)
        (filter #(not (:room-reservation/reservation-batch %)))
        (map (fn [ent] (reservation-ent-to-public-value ent)))
        (cheshire.core/generate-string))))))

(def hourly-booking-batches-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get :post]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json?
    (liberator-util/make-json-validator reservations-factory/validate-reservation-batch))

   :post!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json]}]
     (-> olyp-json
         (reservations-factory/create-reservation-batch datomic-conn)
         liberator-util/ctx-for-entity))

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (->>
        (d/q
         '[:find [?e ...]
           :where
           [?e :reservation-batch/public-id]]
         db)
        (map #(d/entity db %))
        (sort-by :reservation-batch/month)
        (reverse)
        (map reservation-batch-ent-to-public-value)
        (cheshire.core/generate-string))))

   :handle-created
   (fn [{:keys [datomic-entity]}]
     (-> datomic-entity
         reservation-batch-ent-to-public-value
         cheshire.core/generate-string))))

;; (defn reservation-to-line [reservation]
;;   (let [total-minutes (reduce + (map get-booking-total-minutes room-bookings))
;;         total-minutes-rounded (round-minutes-down total-minutes 30)
;;         total-hours (bigdec-divide (BigDecimal. (BigInteger. (str total-minutes-rounded)))
;;                                    big-decimal-sixty)]
;;     {:reservation (reservation-ent-to-public-value reservation)
;;      :lines get-room-booking-invoice-lines}))

(def batch-hourly-bookings-batch-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:get]
   :processable? liberator-util/processable-json?
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity

   :exists?
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (if-let [batch-eid (d/q
                           '[:find ?e .
                             :in $ ?pubid
                             :where
                             [?e :reservation-batch/public-id ?pubid]]
                           db
                           (get-in ctx [:request :route-params :batch-id]))]
         {:olyp-reservation-batch (d/entity db batch-eid)})))

   :handle-ok
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)
           reservations (->> (d/q
                              '[:find [?e ...]
                                :in $ ?batch-eid
                                :where
                                [?e :room-reservation/reservation-batch ?batch-eid]]
                              db
                              (:db/id (:olyp-reservation-batch ctx)))
                             (map #(d/entity db %)))
           room (->> (d/q '[:find ?e . :where [?e :reservable-room/public-id]] db) (d/entity db))]
       (->  {"reservation_batch" (reservation-batch-ent-to-public-value (:olyp-reservation-batch ctx))
             "customers" (->> reservations
                              (group-by #(-> % :room-reservation/ref :room-booking/user :user/customer))
                              (map (fn [[customer reservations]]
                                     (let [line (invoices-factory/get-room-booking-invoice-line
                                                 (map :room-reservation/ref reservations)
                                                 (->> customer
                                                      :customer-room-booking-agreement/_customer
                                                      (filter #(= (:customer-room-booking-agreement/reservable-room %) room))
                                                      (first)))]
                                       {"customer" (customers-handler/customer-ent-to-public-value customer)
                                        "reservations" (map reservation-ent-to-public-value reservations)
                                        "line" {"total_minutes" (:total-minutes line)
                                                "total_hours" (:total-hours line)
                                                "sum_without_tax" (:base-sum-without-tax line)
                                                "sum_with_tax" (:base-sum-with-tax line)
                                                "tax" (:tax line)}}))))
             "reservations" (->> reservations
                                 (sort-by :room-reservation/public-id)
                                 (reverse)
                                 (map reservation-ent-to-public-value))}
            cheshire.core/generate-string)))))

(def batch-hourly-bookings-collection-handler
  (resource
   :available-media-types ["application/json"]
   :allowed-methods [:post :delete]
   :handle-unprocessable-entity liberator-util/handle-unprocessable-entity
   :can-post-to-missing? false
   :exists?
   (fn [ctx]
     (let [db (liberator-util/get-datomic-db ctx)]
       (if-let [batch-eid (d/q
                           '[:find ?e .
                             :in $ ?pubid
                             :where
                             [?e :reservation-batch/public-id ?pubid]]
                           db
                           (get-in ctx [:request :route-params :batch-id]))]
         {:olyp-reservation-batch (d/entity db batch-eid)})))
   :existed? false

   :processable?
   (liberator-util/comp-pos-decision
    liberator-util/processable-json-without-method-check?
    (liberator-util/make-json-validator reservations-factory/validate-batch-hourly-bookings))

   :post!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-reservation-batch]}]
     (reservations-factory/add-bookings-to-reservation-batch olyp-json olyp-reservation-batch datomic-conn)
     nil)

   :delete!
   (fn [{{:keys [datomic-conn]} :request :keys [olyp-json olyp-reservation-batch] :as ctx}]
     (prn "*****************")
     (prn olyp-json)
     (prn olyp-reservation-batch)
     (prn (keys ctx))
     (reservations-factory/unbatch-bookings olyp-json olyp-reservation-batch datomic-conn)
     nil)

   :handle-created
   (fn [ctx]
     (cheshire.core/generate-string {}))))
