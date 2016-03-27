(ns olyp-central-api.factories.invoices-factory
  (:import [org.joda.time DateTime Minutes]
           [java.math BigDecimal BigInteger]))

(def product-code-rentable-room "2")
(def big-decimal-sixty (BigDecimal. "60"))



(defn get-booking-total-minutes [booking]
  (let [reservation (-> booking :room-reservation/_ref first)]
    (-> (Minutes/minutesBetween (DateTime. (:room-reservation/from reservation))
                                (DateTime. (:room-reservation/to reservation)))
        (.getMinutes))))


(defn round-minutes-down [minutes round-by]
  (- minutes (mod minutes round-by)))

(defn bigdec-divide [^BigDecimal a ^BigDecimal b]
  (try
    (.divide a b)
    (catch ArithmeticException e
      (throw (RuntimeException. (str "Unable to divide " a " with " b), e)))))

(defn get-room-booking-invoice-line [room-bookings room-booking-agreement]
  (let [total-minutes (reduce + (map get-booking-total-minutes room-bookings))
        total-minutes-rounded (round-minutes-down total-minutes 30)
        total-hours (bigdec-divide (BigDecimal. (BigInteger. (str total-minutes-rounded)))
                                   big-decimal-sixty)
        free-hours (BigDecimal. (:customer-room-booking-agreement/free-hours room-booking-agreement 0))
        actual-hours (.max (.subtract total-hours free-hours) BigDecimal/ZERO)
        unit-price (:customer-room-booking-agreement/hourly-price room-booking-agreement)
        tax (:customer-room-booking-agreement/tax room-booking-agreement)
        sum-without-tax (.multiply unit-price actual-hours)
        base-sum-without-tax (.multiply unit-price total-hours)]
    {:quantity actual-hours
     :total-minutes total-minutes
     :total-minutes-rounded total-minutes-rounded
     :total-hours total-hours
     :unit-price unit-price
     :tax tax
     :base-sum-without-tax base-sum-without-tax
     :base-sum-with-tax (.multiply base-sum-without-tax (BigDecimal. (str "1." tax)))
     :sum-without-tax sum-without-tax
     :sum-with-tax (.multiply sum-without-tax (BigDecimal. (str "1." tax)))
     :product-code product-code-rentable-room
     :description (str "Fakturerbar timesleie i "
                       (-> room-booking-agreement :customer-room-booking-agreement/reservable-room :reservable-room/name))}))

