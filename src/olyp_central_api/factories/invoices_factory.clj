(ns olyp-central-api.factories.invoices-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [org.joda.time DateTime DateTimeZone Minutes]
           [org.joda.time.format ISODateTimeFormat]
           [java.math BigDecimal BigInteger]))

(def product-code-rental-agreement "1")
(def product-code-rentable-room "2")
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

(defn find-rental-agreements-for-customer [db customer]
  (map
   #(d/entity db %)
   (d/q '[:find [?e ...]
          :in $ ?customer
          :where
          [?e :customer-room-rental-agreement/customer ?customer]]
        db
        (:db/id customer))))

(defn get-booking-total-minutes [booking]
  (let [reservation (-> booking :room-reservation/_ref first)]
    (-> (Minutes/minutesBetween (DateTime. (:room-reservation/from reservation))
                                (DateTime. (:room-reservation/to reservation)))
        (.getMinutes))))

(defn get-rental-agreement-invoice-lines [rental-agreement]
  (let [quantity BigDecimal/ONE
        unit-price (:customer-room-rental-agreement/monthly-price rental-agreement)
        tax (:customer-room-rental-agreement/tax rental-agreement)
        sum-without-tax (.multiply unit-price quantity)]
    [{:quantity quantity
      :unit-price unit-price
      :tax tax
      :sum-without-tax sum-without-tax
      :sum-with-tax (.multiply sum-without-tax (BigDecimal. (str "1." tax)))
      :product-code product-code-rental-agreement
      :description (str "Månedlig leie av "
                        (-> rental-agreement :customer-room-rental-agreement/rentable-room :rentable-room/name))}]))

(defn round-minutes-down [minutes round-by]
  (- minutes (mod minutes round-by)))

(defn get-room-booking-invoice-lines [room-bookings room-booking-agreement]
  (let [total-minutes (reduce + (map get-booking-total-minutes room-bookings))
        total-minutes-rounded (round-minutes-down total-minutes 30)
        total-hours (.divide (BigDecimal. (BigInteger. (str total-minutes-rounded)))
                             big-decimal-sixty)
        free-hours (BigDecimal. (:customer-room-booking-agreement/free-hours room-booking-agreement 0))
        actual-hours (.max (.subtract total-hours free-hours) BigDecimal/ZERO)
        unit-price (:customer-room-booking-agreement/hourly-price room-booking-agreement)
        tax (:customer-room-booking-agreement/tax room-booking-agreement)
        sum-without-tax (.multiply unit-price actual-hours)]
    (if (not= 0 (.compareTo actual-hours BigDecimal/ZERO))
      [{:quantity actual-hours
        :total-minutes total-minutes
        :total-minutes-rounded total-minutes-rounded
        :unit-price unit-price
        :tax tax
        :sum-without-tax sum-without-tax
        :sum-with-tax (.multiply sum-without-tax (BigDecimal. (str "1." tax)))
        :product-code product-code-rentable-room
        :description (str "Fakturerbar timesleie i "
                          (-> room-booking-agreement :customer-room-booking-agreement/reservable-room :reservable-room/name))}])))

(defn get-customer-invoice-lines [bookings rental-agreements customer]
  (concat
   (mapcat get-rental-agreement-invoice-lines rental-agreements)
   (->> bookings
        (group-by #(-> % :room-reservation/_ref first :room-reservation/reservable-room))
        (mapcat (fn [[room room-bookings]]
                  (get-room-booking-invoice-lines
                   room-bookings
                   (->> customer
                        :customer-room-booking-agreement/_customer
                        (filter #(= (:customer-room-booking-agreement/reservable-room %) room))
                        (first))))))))

(defn get-customer-invoice [db {:keys [bookings rental-agreements customer]}]
  (let [lines (get-customer-invoice-lines bookings rental-agreements customer)
        sum-without-tax (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b)) (map :sum-without-tax lines))
        sum-with-tax (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b)) (map :sum-with-tax lines))]
    {:customer customer
     :bookings bookings
     :sum-without-tax sum-without-tax
     :sum-with-tax sum-with-tax
     :total-tax (.subtract sum-with-tax sum-without-tax)
     :lines lines}))

(defn prepare-invoices-for-month [year month db]
  (let [end-of-month (-> (DateTime. year month 1 0 0 (DateTimeZone/forID "Europe/Oslo"))
                         (.plusMonths 1)
                         (.toDate))
        customers (map #(d/entity db %) (d/q '[:find [?e ...] :where [?e :customer/public-id]] db))]
    (->> customers
         (map (fn [customer]
                {:customer customer
                 :bookings (find-bookings-for-customer db end-of-month customer)
                 :rental-agreements (find-rental-agreements-for-customer db customer)}))
         (remove #(and (empty? (:bookings %)) (empty? (:rental-agreements %))))
         (map #(get-customer-invoice db %)))))

(defn get-invoices-data [year month db]
  (map
   (fn [invoice]
     {:invoice-tempid (d/tempid :db.part/user)
      :invoice-data invoice})
   (prepare-invoices-for-month year month db)))

(defn facts-for-invoice-batch [batch-tempid year month]
  [[:db/add batch-tempid :invoice-batch/public-id (str (d/squuid))]
   [:db/add batch-tempid :invoice-batch/month (str year "-" month)]
   [:db/add batch-tempid :invoice-batch/finalized false]])

(defn facts-for-invoice-data [now year month batch-tempid invoice-tempid invoice-data]
  (let [invoice-key (str year "-" month "-" (-> invoice-data :customer :customer/public-id))]
    (concat
     [[:db/add batch-tempid :invoice-batch/invoices invoice-tempid]
      [:db/add invoice-tempid :invoice/key invoice-key]
      [:db/add invoice-tempid :invoice/month (str year "-" month)]
      [:db/add invoice-tempid :invoice/invoice-date (.print (ISODateTimeFormat/date) now)]
      [:db/add invoice-tempid :invoice/due-date (.print (ISODateTimeFormat/date) (.plusDays now 14))]
      [:db/add invoice-tempid :invoice/sum-without-tax (:sum-without-tax invoice-data)]
      [:db/add invoice-tempid :invoice/total-tax (:total-tax invoice-data)]
      [:db/add invoice-tempid :invoice/sum-with-tax (:sum-with-tax invoice-data)]
      [:db/add invoice-tempid :invoice/customer (-> invoice-data :customer :db/id)]]
     (map
      (fn [booking]
        [:db/add invoice-tempid :invoice/bookings (:db/id booking)])
      (:bookings invoice-data))
     (apply
      concat
      (map-indexed
       (fn [idx line]
         (let [line-tempid (d/tempid :db.part/user)]
           [[:db/add line-tempid :invoice-line/public-id (str (d/squuid))]
            [:db/add line-tempid :invoice-line/invoice-key invoice-key]
            [:db/add line-tempid :invoice-line/sort-order idx]
            [:db/add line-tempid :invoice-line/quantity (:quantity line)]
            [:db/add line-tempid :invoice-line/unit-price (:unit-price line)]
            [:db/add line-tempid :invoice-line/sum-without-tax (:sum-without-tax line)]
            [:db/add line-tempid :invoice-line/sum-with-tax (:sum-with-tax line)]
            [:db/add line-tempid :invoice-line/tax (:tax line)]
            [:db/add line-tempid :invoice-line/product-code (:product-code line)]
            [:db/add line-tempid :invoice-line/description (:description line)]]))
       (:lines invoice-data))))))

(defn facts-for-create-initial-invoice-batch-for-month [first-invoice-number year month batch-tempid db]
  (let [invoices-data (get-invoices-data year month db)
        now (DateTime. (DateTimeZone/forID "Europe/Oslo"))]
    (concat
     (facts-for-invoice-batch batch-tempid year month)
     (apply
      concat
      (->> invoices-data
           (map-indexed
            (fn [idx {:keys [invoice-tempid invoice-data]}]
              (concat
               [[:db/add invoice-tempid :invoice/invoice-number (BigInteger/valueOf (+ first-invoice-number idx))]]
               (facts-for-invoice-data now year month batch-tempid invoice-tempid invoice-data)))))))))

(defn facts-for-create-invoice-batch-for-month [year month batch-tempid db]
  (let [invoices-data (get-invoices-data year month db)
        now (DateTime. (DateTimeZone/forID "Europe/Oslo"))]
    (concat
     (facts-for-invoice-batch batch-tempid year month)
     [[:auto-increment-bigint {:invoice/invoice-number (map :invoice-tempid invoices-data)}]]
     (->> invoices-data
          (mapcat (fn [{:keys [invoice-tempid invoice-data]}]
                    (facts-for-invoice-data now year month batch-tempid invoice-tempid invoice-data)))))))

(defn create-invoice-batch-for-month [year month datomic-conn]
  (let [db (d/db datomic-conn)
        batch-tempid (d/tempid :db.part/user)
        tx-res @(d/transact datomic-conn
                            (facts-for-create-invoice-batch-for-month year month batch-tempid db))]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) batch-tempid))))
