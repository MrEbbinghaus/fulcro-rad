(ns ^:always-reload com.fulcrologic.rad.attributes
  #?(:cljs (:require-macros com.fulcrologic.rad.attributes))
  (:require
    [com.wsscode.pathom.core :as p]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails.core :refer [>defn => >def >fdef ?]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.rad.ids :refer [new-uuid]])
  #?(:clj
     (:import (clojure.lang IFn)
              (javax.crypto.spec PBEKeySpec)
              (javax.crypto SecretKeyFactory)
              (java.util Base64))))

(>def ::qualified-key qualified-keyword?)
(>def ::type keyword?)
(>def ::target qualified-keyword?)
(>def ::attribute (s/keys :req [::type ::qualified-key]
                    :opt [::target]))
(>def ::attributes (s/every ::attribute))

(declare map->Attribute)

(>defn new-attribute
  "Create a new attribute, which is represented as an Attribute record.

  NOTE: attributes are usable as functions which act like their qualified keyword. This allows code-navigable
  use of attributes throughout the system...e.g (account/id props) is like (::account/id props), but will
  be understood by an IDE's jump-to feature when you want to analyze what account/id is.  Use `defattr` to
  populate this into a symbol.

  Type can be one of :string, :int, :uuid, etc. (more types are added over time,
  so see main documentation and your database adapter for more information).

  The remaining argument is an open map of additional things that any subsystem can
  use to describe facets of this attribute that are important to your system.

  If `:ref` is used as the type then the ultimate ID of the target entity should be listed in `m`
  under the ::target key.
  "
  [kw type m]
  [qualified-keyword? keyword? map? => ::attribute]
  (do
    (when (and (= :ref type) (not (contains? m ::target)))
      (log/warn "Reference attribute" kw "does not list a target ID. Resolver generation will not be accurate."))
    (map->Attribute
      (-> m
        (assoc ::type type)
        (assoc ::qualified-key kw)))))

#?(:clj
   (defrecord Attribute []
     IFn
     (invoke [this m] (get m (::qualified-key this))))
   :cljs
   (defrecord Attribute []
     IFn
     (-invoke [this m] (get m (::qualified-key this)))))

#?(:clj
   (defmacro defattr
     "Define a new attribute into a sym. Equivalent to (def sym (new-attribute k type m))."
     [sym k type m]
     `(def ~sym (new-attribute ~k ~type ~m))))

(>defn to-many?
  "Returns true if the attribute with the given key is a to-many."
  [attr]
  [::attribute => boolean?]
  (= :many (::cardinality attr)))

(>defn to-int [str]
  [string? => int?]
  (if (nil? str)
    0
    (try
      #?(:clj  (Long/parseLong str)
         :cljs (js/parseInt str))
      (catch #?(:clj Exception :cljs :default) e
        0))))

(>defn attributes->eql
  "Returns an EQL query for all of the attributes that are available for the given database-id"
  [attrs]
  [::attributes => vector?]
  (reduce
    (fn [outs {::keys [qualified-key type target]}]
      (if (and target (#{:ref} type))
        (conj outs {qualified-key [target]})
        (conj outs qualified-key)))
    []
    attrs))

#?(:clj
   (defn ^String gen-salt []
     (let [sr   (java.security.SecureRandom/getInstance "SHA1PRNG")
           salt (byte-array 16)]
       (.nextBytes sr salt)
       (String. salt))))

#?(:clj
   (defn ^String encrypt
     "Returns a cryptographycally-secure hashed password based on the given a plain-text password,
      a random salt string (see `gen-salt`), and a number of iterations.  You should save the hashed result, salt, and
      iterations in your database. Checking a password is then taking the password the user supplied, passing it through
      this function with the original salt and iterations, and seeing if the hashed result is the same as the original.
     "
     [^String password ^String salt ^Long iterations]
     (let [keyLength           512
           password-characters (.toCharArray password)
           salt-bytes          (.getBytes salt "UTF-8")
           skf                 (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA512")
           spec                (new PBEKeySpec password-characters salt-bytes iterations keyLength)
           key                 (.generateSecret skf spec)
           res                 (.getEncoded key)
           hashed-pw           (.encodeToString (Base64/getEncoder) res)]
       hashed-pw)))

(>defn attribute?
  [v]
  [any? => boolean?]
  (instance? Attribute v))

(>defn eql-query
  "Convert a query that uses attributes (records) as keys into the proper EQL query. I.e. (eql-query [account/id]) => [::account/id]
   Honors metadata and join nesting."
  [attr-query]
  [vector? => vector?]
  (walk/prewalk
    (fn [ele]
      (if (attribute? ele)
        (::qualified-key ele)
        ele)) attr-query))

(defn valid-value?
  "Checks if the value looks to be a valid value based on the ::attr/required? and ::attr/valid? options of the
  given attribute."
  [{::keys [required? valid?] :as attribute} value]
  (let [non-empty-value? (and
                           (not (nil? value))
                           (or
                             (not (string? value))
                             (not= 0 (count (str/trim value)))))]
    (if valid?
      (valid? value)
      (or (not required?) non-empty-value?))))

(defn make-attribute-validator
  "Creates a function that can be used as a form validator for any form that contains the given `attributes`.  If the
  form asks for validation on an attribute that isn't listed or has no `::attr/valid?` function then it will consider
  that attribute valid."
  [attributes]
  (let [attribute-map (into {}
                        (map (fn [{::keys [qualified-key] :as a}]
                               [qualified-key a])
                          attributes))]
    (fs/make-validator
      (fn [form k]
        (valid-value? (get attribute-map k) (get form k))))))

(defn pathom-plugin [all-attributes]
  (let [attribute-map (into {}
                        (map (fn [{::keys [qualified-key] :as attr}] [qualified-key attr]))
                        all-attributes)]
    (p/env-wrap-plugin
      (fn [env]
        (assoc env ::key->attribute attribute-map)))))
