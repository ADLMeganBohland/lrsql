(ns lrsql.hugsql.util.statement
  (:require [config.core :refer [env]]
            [ring.util.codec :refer [form-encode]]
            [lrsql.hugsql.util :as u]
            [com.yetanalytics.lrs.xapi.statements :as ss]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Preparation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; If a Statement lacks a version, the version MUST be set to 1.0.0
;; TODO: Change for version 2.0.0
(def xapi-version "1.0.0")

;; TODO: more specific authority
(def lrsql-authority {"name" "LRSQL"
                      "objectType" "Agent"
                      "account" {"homePage" "http://localhost:8080"
                                 "name"     "LRSQL"}})

(defn prepare-statement
  "Prepare `statement` for LRS storage by coll-ifying context activities
   and setting missing id, timestamp, authority, version, and stored
   properties."
  [statement]
  (let [{?id        "id"
         ?timestamp "timestamp"
         ?authority "authority"
         ?version   "version"}
        statement
        {squuid      :squuid
         squuid-ts   :timestamp
         squuid-base :base-uuid}
        (u/generate-squuid*)
        assoc-to-stmt (fn [stmt k v] ; Assoc while also changing the meta
                        (-> stmt
                            (assoc k v)
                            (vary-meta update-in
                                       [:assigned-vals]
                                       conj
                                       (keyword k))))
        squuid-ts-str (u/time->str squuid-ts)]
    (cond-> statement
      true
      ss/fix-statement-context-activities
      true
      (vary-meta assoc :primary-key squuid)
      true
      (assoc-to-stmt "stored" squuid-ts-str)
      (not ?id)
      (assoc-to-stmt "id" (u/uuid->str squuid-base))
      (not ?timestamp)
      (assoc-to-stmt "timestamp" squuid-ts-str)
      (not ?authority)
      (assoc-to-stmt "authority" lrsql-authority)
      (not ?version)
      (assoc-to-stmt "version" xapi-version))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Equality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Case sensitivity of string values
(defn- dissoc-statement-properties*
  [stmt substmt?]
  (let [{{stmt-act-type "objectType" :or {stmt-act-type "Agent"}}                                                                          "actor"
         {stmt-obj-type "objectType" :or {stmt-obj-type "Activity"}}                                                 "object"
         {{?cat-acts "category"
           ?grp-acts "grouping"
           ?prt-acts "parent"
           ?oth-acts "other"} "contextActivities"
          ?stmt-inst "instructor"
          ?stmt-team "team"} "context"}
        stmt
        {:keys [assigned-vals]}
        (meta stmt)
        dissoc-activity-def
        (fn [activity] (dissoc activity "definition"))]
    (cond-> stmt
      ;; Dissoc any properties generated by lrsql
      (not substmt?) ; `stored` is always added
      (dissoc "stored")
      (contains? assigned-vals :id)
      (dissoc "id")
      (contains? assigned-vals :timestamp)
      (dissoc "timestamp")
      (contains? assigned-vals :authority)
      (dissoc "authority")
      (contains? assigned-vals :version)
      (dissoc "version")
      ;; Normalize timestamp (if it wasn't added)
      (not (contains? assigned-vals :timestamp))
      (update "timestamp" u/time->millis)
      ;; Verb displays are technically not part of the stmt
      true
      (update "verb" dissoc "display")
      ;; Activity definitions are technically not part of the stmt
      (= "Activity" stmt-obj-type)
      (update "object"
              dissoc-activity-def)
      ?cat-acts ; Also unorder context activity arrays
      (update-in ["context" "contextActivities" "category"]
                 (comp set (partial map dissoc-activity-def)))
      ?grp-acts
      (update-in ["context" "contextActivities" "grouping"]
                 (comp set (partial map dissoc-activity-def)))
      ?prt-acts
      (update-in ["context" "contextActivities" "parent"]
                 (comp set (partial map dissoc-activity-def)))
      ?oth-acts
      (update-in ["context" "contextActivities" "other"]
                 (comp set (partial map dissoc-activity-def)))
      ;; Group member arrays must be unordered
      ;; Note: Ignore authority unless OAuth is enabled
      (= "Group" stmt-act-type)
      (update-in  ["actor" "member"]
                  set)
      (= "Group" stmt-obj-type)
      (update-in ["object" "member"]
                 set)
      (and ?stmt-inst (contains? ?stmt-inst "member"))
      (update-in ["context" "instructor" "membrs"]
                 set)
      (and ?stmt-team (contains? ?stmt-inst "member"))
      (update-in ["context" "team" "members"]
                 set)
      ;; Repeat the above in any Substatements
      (and (not substmt?)
           (= "SubStatement" stmt-obj-type))
      (update "object" (dissoc-statement-properties* stmt true)))))

(defn dissoc-statement-properties
  "Dissociate any Statement properties in `stmt` that are an exception to
   Statement Immutability."
  [stmt]
  (dissoc-statement-properties* stmt false))

(defn statement-equal?
  "Compare two Statements based on their immutable properties."
  [stmt1 stmt2]
  (= (dissoc-statement-properties stmt1)
     (dissoc-statement-properties stmt2)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Formatting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-statement
  "Given `statement`, format it according to the value of `format`:
   - :exact      No change to the Statement
   - :ids        Return only the IDs in each Statement object
   - :canonical  Return a \"canonical\" version of lang maps based on `ltags`."
  [statement format ltags]
  (case format
    :exact
    statement
    :ids
    (ss/format-statement-ids statement)
    :canonical
    (ss/format-canonical statement ltags)
    ;; else
    (throw (ex-info "Unknown format type"
                    {:type   ::unknown-format-type
                     :format format}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Get more permanent solution for host and port defaults
(defn- xapi-path-prefix
  []
  (let [{host :db-host
         port :db-port
         :or {host "localhost"
              port 8080}}
        env]
    (str "http://" host ":" port)))

(defn make-more-url
  "Forms the `more` URL value from `query-params` and the Statement ID
   `next-cursor` which points to the first Statement of the next page."
  [query-params next-cursor]
  (str (xapi-path-prefix)
       "/xapi/statements?"
       (form-encode (assoc query-params :from next-cursor))))

(defn ensure-default-max-limit
  "Given `?limit`, apply the maximum possible limit (if it is zero
   or exceeds that limit) or the default limit (if it is `nil`).
   The maximum and default limits are set in as environment vars."
  [?limit]
  ;; TODO: env defaults out of code.. Aero?
  ;; TODO: reevaluate defaults
  (let [limit-max     (:stmt-get-max env 100)
        limit-default (:stmt-get-default env 100)]
    (cond
      ;; Ensure limit is =< max
      (pos-int? ?limit)
      (min ?limit limit-max)
      ;; If zero, spec says use max
      (and ?limit (zero? ?limit))
      limit-max
      ;; Otherwise, apply default
      :else
      limit-default)))
