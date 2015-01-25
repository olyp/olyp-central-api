(ns olyp-central-api.factories.invoices-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [org.joda.time DateTime DateTimeZone Minutes]
           [java.math BigDecimal BigInteger]))

(def product-code-rentable-room "1")
(def big-decimal-minus-one (BigDecimal. "-1"))
(def big-decimal-sixty (BigDecimal. "60"))

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
  {:customer customer
   :bookings (find-bookings-for-customer db end-of-month customer)
   :rentals []})

(defn get-booking-total-minutes [booking]
  (let [reservation (-> booking :room-reservation/_ref first)]
    (-> (Minutes/minutesBetween (DateTime. (:room-reservation/from reservation))
                                (DateTime. (:room-reservation/to reservation)))
        (.getMinutes))))

(defn get-free-hours-line [line-hours room-booking-agreement customer]
  {:quantity line-hours
   :unit-price (.multiply (:customer-room-booking-agreement/hourly-price room-booking-agreement) big-decimal-minus-one)
   :tax (:customer/room-booking-tax customer)
   :product-code product-code-rentable-room
   :description (str "Monthly free hours, " (-> room-booking-agreement :customer-room-booking-agreement/reservable-room :reservable-room/name) ": " line-hours)})

(defn get-free-hours-lines [room-booking-agreement customer booking-lines]
  (let [free-hours (BigDecimal. (:customer-room-booking-agreement/free-hours room-booking-agreement 0))]
    (if (= 0 (.compareTo free-hours BigDecimal/ZERO))
      []
      (let [actual-hours (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b)) (map :quantity booking-lines))
            line-hours (if (= 1 (.compareTo actual-hours free-hours))
                         free-hours
                         actual-hours)]
        (if (= 0 (.compareTo line-hours BigDecimal/ZERO))
          []
          [(get-free-hours-line line-hours room-booking-agreement customer)])))))

(defn get-room-invoice-lines [room room-booking-agreement room-bookings]
  (map
   (fn [[user user-bookings]]
     (let [total-minutes (reduce + (map get-booking-total-minutes user-bookings))
           total-hours (.divide (BigDecimal. (BigInteger. (str total-minutes)))
                                big-decimal-sixty)]
       {:quantity total-hours
        :unit-price (:customer-room-booking-agreement/hourly-price room-booking-agreement)
        :tax (-> user :user/customer :customer/room-booking-tax)
        :product-code product-code-rentable-room
        :description (str (:user/name user) ", " (:reservable-room/name room) ": " total-hours)}))
   (group-by :room-booking/user room-bookings)))

(defn get-customer-invoice [db {:keys [bookings rentals customer]}]
  {:customer customer
   :lines
   (mapcat
    (fn [[room room-bookings]]
      (let [room-booking-agreement (->> customer
                                        :customer-room-booking-agreement/_customer
                                        (filter #(= (:customer-room-booking-agreement/reservable-room %) room))
                                        (first))
            lines (get-room-invoice-lines room room-booking-agreement room-bookings)]
        (concat lines (get-free-hours-lines room-booking-agreement customer lines))))
    (group-by #(-> % :room-reservation/_ref first :room-reservation/reservable-room) bookings))})

(defn prepare-invoices-for-month [year month db]
  (let [end-of-month (-> (DateTime. year month 1 0 0 (DateTimeZone/forID "Europe/Oslo"))
                         (.plusMonths 1)
                         (.toDate))
        customers (map #(d/entity db %) (d/q '[:find [?e ...] :where [?e :customer/public-id]] db))]
    (->> customers
         (map #(get-customer-invoice-base-data db % end-of-month))
         (remove #(and (empty? (:bookings %)) (empty? (:rentals %))))
         (map #(get-customer-invoice db %)))))

(defn create-invoice-batch-for-month [year month datomic-conn]
  (let [db (d/db datomic-conn)
        invoices-data (map
                       (fn [invoice]
                         {:invoice-tempid (d/tempid :db.part/user)
                          :invoice-data invoice})
                       (prepare-invoices-for-month year month db))
        batch-tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 (concat
                  [[:db/add batch-tempid :invoice-batch/public-id (str (d/squuid))]
                   [:db/add batch-tempid :invoice-batch/month (str year "-" month)]
                   [:db/add batch-tempid :invoice-batch/finalized false]
                   [:auto-increment-bigint {:invoice/invoice-number (map :invoice-tempid invoices-data)}]]
                  (mapcat
                   (fn [{:keys [invoice-tempid invoice-data]}]
                     (let [invoice-key (str year "-" month "-" (-> invoice-data :customer :customer/public-id))]
                       (concat
                        [[:db/add batch-tempid :invoice-batch/invoices invoice-tempid]
                         [:db/add invoice-tempid :invoice/key invoice-key]
                         [:db/add invoice-tempid :invoice/month (str year "-" month)]
                         [:db/add invoice-tempid :invoice/customer (-> invoice-data :customer :db/id)]]
                        (apply
                         concat
                         (map-indexed
                          (fn [idx line]
                            (let [line-tempid (d/tempid :db.part/user)]
                              [[:db/add line-tempid :invoice-line/invoice-key invoice-key]
                               [:db/add line-tempid :invoice-line/sort-order idx]
                               [:db/add line-tempid :invoice-line/quantity (:quantity line)]
                               [:db/add line-tempid :invoice-line/unit-price (:unit-price line)]
                               [:db/add line-tempid :invoice-line/tax (:tax line)]
                               [:db/add line-tempid :invoice-line/product-code (:product-code line)]
                               [:db/add line-tempid :invoice-line/description (:description line)]]))
                          (:lines invoice-data))))))
                   invoices-data)))]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) batch-tempid))))
