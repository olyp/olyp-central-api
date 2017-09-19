(ns olyp-central-api.main-mongodb-migration
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [datomic.api :as d]
            [olyp-central-api.factories.invoices-factory :as invoices-factory])
  (:import [java.util Date]
           [java.math BigDecimal BigInteger]))

(defn get-room-booking-agreements [customer]
  (let [agreement (first (:customer-room-booking-agreement/_customer customer))]
    [{"type" "hourlyRental"
      "roomId" (-> agreement :customer-room-booking-agreement/reservable-room :reservable-room/public-id)
      "hourlyPrice" (.toString (:customer-room-booking-agreement/hourly-price agreement))
      "freeHours" (:customer-room-booking-agreement/free-hours agreement)
      "tax" true}]))

(defn get-attr-tx-inst [id attr db]
  (d/q '[:find ?tx-inst .
         :in $ ?e ?a
         :where
         [?e ?a _ ?tx]
         [?tx :db/txInstant ?tx-inst]] db id attr))

(def big-decimal-negative-one (BigDecimal. "-1"))

(defn get-single-room-booking-line [room-booking room-booking-agreement reservable-room]
  (let [total-minutes (invoices-factory/get-booking-total-minutes room-booking)
        total-minutes-rounded (invoices-factory/round-minutes-down total-minutes 30)
        total-hours (invoices-factory/bigdec-divide
                      (BigDecimal. (BigInteger. (str total-minutes-rounded)))
                      invoices-factory/big-decimal-sixty)
        unit-price (:customer-room-booking-agreement/hourly-price room-booking-agreement)
        sum-without-tax (.multiply unit-price total-hours)
        tax (:customer-room-booking-agreement/tax room-booking-agreement)
        tax-factor (BigDecimal. (str "1." tax))]
      {"lineInfo" {"type" "roomBooking"
                   "roomId" (:reservable-room/public-id reservable-room)
                   "totalMinutes" total-minutes
                   "totalMinutesRounded" total-minutes-rounded}
       "note" (str "Booking, " (:reservable-room/name reservable-room) ", " total-hours " timer")
       "tax" tax
       "sumWithoutTax" (.toString sum-without-tax)
       "sumWithTax" (.toString (.multiply sum-without-tax tax-factor))}))

(defn get-hourly-booking-lines [reservations room-booking-agreement]
  (let [reservable-room (:customer-room-booking-agreement/reservable-room room-booking-agreement)
        room-bookings (map :room-reservation/ref reservations)
        total-minutes (reduce + (map invoices-factory/get-booking-total-minutes room-bookings))
        total-minutes-rounded (invoices-factory/round-minutes-down total-minutes 30)
        total-hours (invoices-factory/bigdec-divide (BigDecimal. (BigInteger. (str total-minutes-rounded)))
                                                    invoices-factory/big-decimal-sixty)
        free-hours (BigDecimal. (:customer-room-booking-agreement/free-hours room-booking-agreement 0))
        num-discounted-hours (.min free-hours total-hours)
        unit-price (:customer-room-booking-agreement/hourly-price room-booking-agreement)
        tax (:customer-room-booking-agreement/tax room-booking-agreement)
        sum-without-tax (.multiply unit-price total-hours)
        tax-factor (BigDecimal. (str "1." tax))
        room-bookings-lines (map #(get-single-room-booking-line % room-booking-agreement reservable-room) room-bookings)]
    (if (= (.compareTo num-discounted-hours BigDecimal/ZERO) 0)
      room-bookings-lines
      (let [free-hours-discount-without-tax (.multiply (.multiply unit-price num-discounted-hours) big-decimal-negative-one)]
        (conj room-bookings-lines
              {"lineInfo" {"type" "roomBookingRebate"
                           "roomId" (:reservable-room/public-id reservable-room)
                           "discountedHours" (.longValue (.doubleValue num-discounted-hours))}
               "note" (str "Gratis timer, " (:reservable-room/name reservable-room))
               "sumWithoutTax" (.toString free-hours-discount-without-tax)
               "sumWithTax" (.toString (.multiply free-hours-discount-without-tax tax-factor))})))))

(defn -main [& args]
  (let [mg-conn (mg/connect {:host (nth args 0) :port (Long/parseLong (nth args 1) 10)})
        mg-db (mg/get-db mg-conn (nth args 2))
        datomic-conn (d/connect (nth args 3))
        datomic-db (d/db datomic-conn)]

    (doseq [coll ["users" "rooms" "customers" "reservations" "invoices"]]
      (mc/remove mg-db coll))

    (doseq [user (->> (d/q '[:find [?e ...] :where [?e :user/public-id]] datomic-db)
                      (map #(d/entity datomic-db %)))]
      (let [[first-name & last-names] (clojure.string/split (:user/name user) #" ")]
        (mc/insert
          mg-db "users"
          {"_id" (:user/public-id user)
           "createdAt" (Date.)
           "services" {"password" {"bcrypt" (:user/bcrypt-password user)}}
           "emails" [{"address" (:user/email user) "verified" false}]
           "profile" {"firstName" first-name "lastName" (clojure.string/join " " last-names)}
           "customers" [{"id" (-> user :user/customer :customer/public-id)}]
           "roles" {"booking" ["user"]}})))

    (doseq [room (->> (d/q '[:find [?e ...] :where [?e :reservable-room/public-id]] datomic-db)
                      (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "rooms"
          {"_id" (:reservable-room/public-id room)
           "name" (:reservable-room/name room)})))

    (doseq [customer (->> (d/q '[:find [?e ...] :where [?e :customer/public-id] [?e :customer/type :customer.type/company]] datomic-db)
                          (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "customers"
          {"_id" (:customer/public-id customer)
           "type" "company"
           "roomBookingAgreements" (get-room-booking-agreements customer)
           "brregId" (:customer/brreg-id customer)
           "name" (:customer/name customer)
           "address" (:customer/address customer)
           "zip" (:customer/zip customer)
           "city" (:customer/city customer)
           "contactPerson" {"name" (:customer/contact-person-name customer)
                            "email" (:customer/contact-person-email customer)
                            "phone" (:customer/contact-person-phone customer)}})))

    (doseq [customer (->> (d/q '[:find [?e ...] :where [?e :customer/public-id] [?e :customer/type :customer.type/person]] datomic-db)
                          (map #(d/entity datomic-db %)))]
      (let []
        (mc/insert
          mg-db "customers"
          {"_id" (:customer/public-id customer)
           "type" "person"
           "roomBookingAgreements" (get-room-booking-agreements customer)
           "name" (:customer/name customer)
           "address" (:customer/address customer)
           "zip" (:customer/zip customer)
           "city" (:customer/city customer)
           "email" (:customer/contact-person-email customer)
           "phone" (:customer/contact-person-phone customer)})))

    (doseq [reservation (->> (d/q '[:find [?e ...] :where [?e :room-reservation/public-id]] datomic-db)
                         (map #(d/entity datomic-db %)))]
      (let [customer (-> reservation :room-reservation/ref :room-booking/user :user/customer)
            reservable-room (:room-reservation/reservable-room reservation)
            room-booking-agreement-eid (d/q
                                         '[:find ?e .
                                           :in $ ?customer ?reservable-room
                                           :where
                                           [?e :customer-room-booking-agreement/customer ?customer]
                                           [?e :customer-room-booking-agreement/reservable-room ?reservable-room]]
                                         datomic-db
                                         (:db/id customer)
                                         (:db/id reservable-room))
            room-booking-agreement (d/entity datomic-db room-booking-agreement-eid)]
        (mc/insert
          mg-db "reservations"
          {"_id" (:room-reservation/public-id reservation)
           "type" "booking"
           "booking" {"userId" (-> reservation :room-reservation/ref :room-booking/user :user/public-id)
                      "customerId" (:customer/public-id customer)
                      "hourlyPrice" (.toString (:customer-room-booking-agreement/hourly-price room-booking-agreement))
                      "tax" (:customer-room-booking-agreement/tax room-booking-agreement)
                      "isInvoiced" (not (nil? (:room-reservation/reservation-batch reservation)))}
           "comment" (:room-reservation/comment reservation)
           "from" (:room-reservation/from reservation)
           "to" (:room-reservation/to reservation)
           "roomId" (:reservable-room/public-id reservable-room)
           "createdAt" (get-attr-tx-inst (:db/id reservation) :room-reservation/public-id datomic-db)})))


    (doseq [reservation-batch (->> (d/q '[:find [?e ...] :where [?e :reservation-batch/public-id]] datomic-db)
                                   (map #(d/entity datomic-db %)))]
      (doseq [
              [customer reservations]
              (group-by #(-> % :room-reservation/ref :room-booking/user :user/customer)
                        (->> (d/q '[:find [?e ...]
                                    :in $ ?batch-eid
                                    :where
                                    [?e :room-reservation/reservation-batch ?batch-eid]]
                                  datomic-db
                                  (:db/id reservation-batch))
                             (map #(d/entity datomic-db %))))]
        (let [;; getting the first one is good enough - we only book for a single room currently
              room-booking-agreement (->> customer
                                          :customer-room-booking-agreement/_customer
                                          (first))
              hourly-booking-lines (get-hourly-booking-lines reservations room-booking-agreement)]

          (mc/insert
            mg-db "invoices"
            {"createdAt" (get-attr-tx-inst (:db/id reservation-batch) :reservation-batch/public-id datomic-db)
             "customerId" (:customer/public-id customer)
             "hourlyBookingLines" [{"roomId" (-> room-booking-agreement :customer-room-booking-agreement/reservable-room :reservable-room/public-id)
                                    "roomBookingAgreementId" (:customer-room-booking-agreement/public-id room-booking-agreement)
                                    "hourlyPrice" (.toString (:customer-room-booking-agreement/hourly-price room-booking-agreement))
                                    "tax" (:customer-room-booking-agreement/tax room-booking-agreement)
                                    "freeHours" (:customer-room-booking-agreement/free-hours room-booking-agreement)
                                    "lines" hourly-booking-lines}]
             "sumWithTax" (.toString (reduce #(.add %1 %2) (map #(BigDecimal. (get % "sumWithTax")) hourly-booking-lines)))
             "sumWithoutTax" (.toString (reduce #(.add %1 %2) (map #(BigDecimal. (get % "sumWithoutTax")) hourly-booking-lines)))}))))

    (prn "Done!")))