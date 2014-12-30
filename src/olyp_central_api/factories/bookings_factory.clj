(ns olyp-central-api.factories.bookings-factory
  (:require [datomic.api :as d]
            [validateur.validation :as v])
  (:import [java.util UUID Date]))

(defn validate-type [attr type]
  (v/validate-with-predicate attr #(instance? Date (get % attr)) :message (str "Has to be of type " type)))

(def validate-booking
  (v/validation-set
   (v/presence-of "from")
   (validate-type "from" Date)
   (v/presence-of "to")
   (validate-type "to" Date)
   (v/validate-with-predicate "from" #(>= (% "from") (% "to")) :message "Can't create a booking that ends before it starts")
   (v/presence-of "bookable_room_id")
   (validate-type "bookable_room_id" UUID)
   (v/presence-of "user_id")
   (validate-type "user_id" UUID)
   (v/all-keys-in #{"from" "to" "bookable_room_id" "user_id"})))

(defn create-booking [data datomic-conn]
  (let [tempid (d/tempid :db.part/user)
        tx-res @(d/transact
                 datomic-conn
                 [[:db/add tempid :room-booking/from (data "from")]
                  [:db/add tempid :room-booking/to (data "to")]
                  [:db/add tempid :room-booking/user [:user/public-id (data "user_id")]]
                  [:db/add tempid :room-booking/bookable-room [:bookable-room/public-id (data "bookable_rom_id")]]])]
    (d/entity (:db-after tx-res) (d/resolve-tempid (:db-after tx-res) (:tempids tx-res) tempid))))
