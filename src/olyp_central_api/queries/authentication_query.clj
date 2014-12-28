(ns olyp-central-api.queries.authentication-query
  (:require [validateur.validation :as v]
            [crypto.password.bcrypt]))

(def validate-authentication
  (v/validation-set
   (v/presence-of "email")
   (v/presence-of "password")
   (v/all-keys-in #{"email" "password"})))

(defn valid-password? [user password]
  (crypto.password.bcrypt/check password (:user/bcrypt-password user)))
