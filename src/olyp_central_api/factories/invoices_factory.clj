(ns olyp-central-api.factories.invoices-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [org.joda.time DateTime DateTimeZone Minutes]
           [java.math BigDecimal BigInteger]))

(def product-code-rentable-room 1)

(defn find-bookings-for-customer [db end-of-month customer]
  (map
   #(d/entity db %)
   (d/q '[:find [?e ...]
          :in $ ?end-of-month ?customer
          :where
          [?e :room-booking/is-invoiced false]
          [?e :room-booking/user ?user]
          [?user :user/customer ?customer]
          [?reservation :room-reservation/ref ?e]
          [?reservation :room-reservation/from ?reservation-from]
          [(< ?reservation-from ?end-of-month)]]
        db
        end-of-month
        (:db/id customer))))

(defn get-customer-invoice-base-data [db customer end-of-month]
  {:bookings (find-bookings-for-customer db end-of-month customer)
   :rentals []})

(defn get-booking-total-minutes [booking]
  (let [reservation (-> booking :room-reservation/_ref first)]
    (-> (Minutes/minutesBetween (DateTime. (:room-reservation/from reservation))
                                (DateTime. (:room-reservation/to reservation)))
        (.getMinutes))))

(defn get-booking-lines [bookings]
  (mapcat
   (fn [[user user-bookings]]
     (map
      (fn [[room room-bookings]]
        (let [total-minutes (reduce + (map get-booking-total-minutes bookings))
              total-hours (.divide (BigDecimal. (BigInteger. (str total-minutes)))
                                   (BigDecimal. "60"))]
          {:quantity total-hours
           :unit-price (BigDecimal. "375.00000")
           :tax 25
           :product-code product-code-rentable-room
           :description (str (:user/name user) ", " (:reservable-room/name room) ": " total-hours)}))
      (group-by #(-> % :room-reservation/_ref first :room-reservation/reservable-room) user-bookings)))
   (group-by :room-booking/user bookings)))

(defn get-customer-invoice [db {:keys [bookings rentals]}]
  {:lines (get-booking-lines bookings)})

(defn prepare-invoices-for-month [year month datomic-conn]
  (let [db (d/db datomic-conn)
        end-of-month (-> (DateTime. year month 1 0 0 (DateTimeZone/forID "Europe/Oslo"))
                         (.plusMonths 1)
                         (.toDate))
        customers (map #(d/entity db %) (d/q '[:find [?e ...] :where [?e :customer/public-id]] db))]
    (zipmap (map :customer/public-id customers)
            (->> customers
                 (map #(get-customer-invoice-base-data db % end-of-month))
                 (remove #(and (empty? (:bookings %)) (empty? (:rentals %))))
                 (map #(get-customer-invoice db %))))))
