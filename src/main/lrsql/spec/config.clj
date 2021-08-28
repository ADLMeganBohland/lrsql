(ns lrsql.spec.config
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [lrsql.spec.util :as u]))

(s/def ::db-type #{"h2" "h2:mem" "sqlite" "postgres" "postgresql"})
(s/def ::db-name string?)
(s/def ::db-host string?)
(s/def ::db-port nat-int?)

(def db-prop-regex
  "Regex for JDBC URL query params."
  (let [basic-char "[\\w\\.\\-~,:]" ; URL basics + commonly-used in JDBC params
        per-encode "(?:%[0-9A-fa-f]{2})"
        sing-quote "(?:'.*')"
        doub-quote "(?:\".*\")"
        kstr "([\\w]+)"
        vstr (str "((?:" basic-char "|" per-encode ")+"
                  "|" sing-quote
                  "|" doub-quote ")")
        kv   (str kstr "=" vstr)
        fst  (str "(?:" kv ")")
        rst  (str "(?:(?:&|;)" kv ")*")]
    (re-pattern (str "(?:" fst rst ")?"))))

(s/def ::db-properties (s/and string? (partial re-matches db-prop-regex)))
(s/def ::db-jdbc-url ::xs/iri)

(s/def ::db-user string?)
(s/def ::db-password string?)

(s/def ::database
  (s/and (s/conformer u/remove-nil-vals)
         (s/conformer u/remove-neg-vals)
         (s/or :no-jdbc-url
               (s/keys :req-un [::db-type
                                ::db-name]
                       :opt-un [::db-properties
                                ::db-host
                                ::db-port
                                ::db-user
                                ::db-password])
               :jdbc-url
               (s/keys :req-un [::db-jdbc-url]
                       :opt-un [::db-user
                                ::db-password]))))

(s/def ::pool-init-size nat-int?)
(s/def ::pool-min-size nat-int?)
(s/def ::pool-inc nat-int?)
(s/def ::pool-max-size nat-int?)
(s/def ::pool-max-stmts nat-int?)

(s/def ::connection
  (s/and (s/conformer u/remove-nil-vals)
         (s/conformer u/remove-neg-vals)
         (s/keys :req-un [::database]
                 :opt-un [::pool-init-size
                          ::pool-min-size
                          ::pool-inc
                          ::pool-max-size
                          ::pool-max-stmts])
         (fn [{:keys [pool-min-size pool-max-size]
               :or {pool-min-size 3 ; c3p0 defaults
                    pool-max-size 15}}]
           (<= pool-min-size pool-max-size))))

(s/def ::api-key-default string?)
(s/def ::api-secret-default string?)

(s/def ::stmt-get-default pos-int?)
(s/def ::stmt-get-max pos-int?)

(s/def ::authority-template string?)
(s/def ::authority-url ::xs/irl)

(s/def ::lrs
  (s/and (s/conformer u/remove-nil-vals)
         (s/conformer u/remove-neg-vals)
         (s/keys :req-un [::stmt-get-default
                          ::stmt-get-max
                          ::stmt-url-prefix
                          ::authority-template
                          ::authority-url]
                 :opt-un [::api-key-default
                          ::api-secret-default])))

(s/def ::enable-http boolean?)
(s/def ::enable-http2 boolean?)

(s/def ::http-host string?)
(s/def ::http-port nat-int?)
(s/def ::ssl-port nat-int?)

(s/def ::jwt-exp-time pos-int?)
(s/def ::jwt-exp-leeway nat-int?)

(s/def ::key-file string?) ; TODO: correct file extension/path?
(s/def ::key-alias string?)
(s/def ::key-password string?)

(s/def ::key-pkey-file string?)
(s/def ::key-cert-chain string?)
(s/def ::key-enable-selfie boolean?)

(s/def ::webserver
  (s/keys :req-un [::http-host
                   ::http-port
                   ::ssl-port
                   ::enable-http
                   ::enable-http2
                   ::url-prefix
                   ::key-alias
                   ::key-password
                   ::key-enable-selfie
                   ::jwt-exp-time
                   ::jwt-exp-leeway]
          :opt-un [::key-file
                   ::key-pkey-file
                   ::key-cert-chain]))

(def config-spec
  (s/keys :req-un [::connection
                   ::lrs
                   ::webserver]))
