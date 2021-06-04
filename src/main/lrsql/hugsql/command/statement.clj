(ns lrsql.hugsql.command.statement
  (:require [lrsql.hugsql.functions :as f]
            [lrsql.hugsql.util :as u]
            [lrsql.hugsql.util.activity :as ua]
            [lrsql.hugsql.util.statement :as us]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Insertions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- insert-statement-input!
  [tx input]
  (let [{stmt-id  :statement-id
         new-stmt :payload} input
        exists (f/query-statement-exists tx {:statement-id stmt-id})]
    (if exists
      (let [old-data (or (f/query-statement {:statement-id stmt-id
                                             :voided?      false})
                         (f/query-statement {:statement-id stmt-id
                                             :voided?      true}))
            old-stmt (-> old-data :payload u/parse-json)]
        (when-not (us/statement-equal? old-stmt new-stmt)
          (throw
           (ex-info "Statement Conflict!"
                    {:type :com.yetanalytics.lrs.protocol/statement-conflict
                     :old-stmt old-stmt
                     :new-stmt new-stmt}))))
      (do
        (f/insert-statement! tx (update input :payload u/write-json))
        (when (:voiding? input)
          (f/void-statement! tx {:statement-id (:statement-ref-id input)}))))))

(defn- insert-actor-input!
  [tx input]
  (if (some->> (select-keys input [:actor-ifi])
               (f/query-actor-exists tx))
    (f/update-actor! tx (update input :payload u/write-json))
    (f/insert-actor! tx (update input :payload u/write-json)))
  nil)

(defn- insert-activity-input!
  [tx input]
  (if-some [old-activ (some->> (select-keys input [:activity-iri])
                               (f/query-activity tx)
                               :payload
                               u/parse-json)]
    (let [new-activ (:payload input)
          activity' (ua/merge-activities old-activ new-activ)
          input'    (assoc input :payload (u/write-json activity'))]
      (f/update-activity! tx input'))
    (f/insert-activity! tx (update input :payload u/write-json)))
  nil)

(defn- insert-attachment-input!
  [tx input]
  (f/insert-attachment! tx input)
  nil)

(defn- insert-stmt-actor-input!
  [tx input]
  (f/insert-statement-to-actor! tx input)
  nil)

(defn- insert-stmt-activity-input!
  [tx input]
  (f/insert-statement-to-activity! tx input)
  nil)

(defn- insert-stmt-stmt-input!
  [tx input]
  (let [input' {:statement-id (:descendant-id input)}
        exists (f/query-statement-exists tx input')]
    (when exists (f/insert-statement-to-statement! tx input)))
  nil)

(defn insert-statement!
  [tx {:keys [statement-input
              actor-inputs
              activity-inputs
              attachment-inputs
              stmt-actor-inputs
              stmt-activity-inputs
              stmt-stmt-inputs]}]
  (insert-statement-input! tx statement-input)
  (doall (map (partial insert-actor-input! tx) actor-inputs))
  (doall (map (partial insert-activity-input! tx) activity-inputs))
  (doall (map (partial insert-stmt-actor-input! tx) stmt-actor-inputs))
  (doall (map (partial insert-stmt-activity-input! tx) stmt-activity-inputs))
  (doall (map (partial insert-stmt-stmt-input! tx) stmt-stmt-inputs))
  (doall (map (partial insert-attachment-input! tx) attachment-inputs))
  ;; Success! Return the statement ID
  (:statement-id statement-input))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- query-res->statement
  [format ltags query-res]
  (-> query-res
      :payload
      u/parse-json
      (us/format-statement format ltags)))

(defn- conform-attachment-res
  [{att-sha      :attachment_sha
    content-type :content_type
    length       :content_length
    contents     :contents}]
  {:sha2        att-sha
   :length      length
   :contentType content-type
   :content     contents})

(defn- query-one-statement
  "Query a single statement from the DB, using the `:statement-id` parameter."
  [tx input ltags]
  (let [{:keys [format attachments?]} input
        query-result (f/query-statement tx input)
        statement   (when query-result
                           (query-res->statement format ltags query-result))
        attachments (when (and statement attachments?)
                      (->> {:statement-id (get statement "id")}
                           (f/query-attachments tx)
                           (mapv conform-attachment-res)))]
    (cond-> {}
      statement   (assoc :statement statement)
      attachments (assoc :attachments attachments))))

(defn- query-many-statements
  "Query potentially multiple statements from the DB."
  [tx input ltags]
  (let [{:keys [format limit attachments? query-params]} input
        input'        (if limit (update input :limit inc) input)
        query-results (f/query-statements tx input')
        ?next-cursor  (when (and limit
                                 (= (inc limit) (count query-results)))
                        (-> query-results last :id u/uuid->str))
        stmt-results  (map (partial query-res->statement format ltags)
                           (if (not-empty ?next-cursor)
                             (butlast query-results)
                             query-results))
        att-results   (if attachments?
                        (->> (doall (map (fn [stmt]
                                           (->> (get stmt "id")
                                                (assoc {} :statement-id)
                                                (f/query-attachments tx)))
                                         stmt-results))
                             (apply concat)
                             (mapv conform-attachment-res))
                        [])]
    {:statement-result
     {:statements (vec stmt-results)
      :more       (if ?next-cursor
                    (us/make-more-url query-params ?next-cursor)
                    "")}
     :attachments att-results}))

(defn query-statements
  "Query statements from the DB. Return a map containing a singleton
   `:statement` if a statement ID is included in the query, or a
   `:statement-result` object otherwise. The map also contains `:attachments`
   to return any associated attachments. The `ltags` argument controls which
   language tag-value pairs are returned when `:format` is `:canonical`.
   Note that the `:more` property of `:statement-result` returned is a
   statement PK, NOT the full URL."
  [tx input ltags]
  (let [{?stmt-id :statement-id} input]
    (if ?stmt-id
      (query-one-statement tx input ltags)
      (query-many-statements tx input ltags))))

(defn query-descendants
  "Query Statement References from the DB. In addition to the immediate
   references given by `:statement-ref-id`, it returns ancestral
   references, i.e. not only the Statement referenced by `:statement-ref-id`,
   but the Statement referenced by that ID, and so on. The return value
   is a seq of the descendant statemnet IDs; these are later added to the
   input map."
  [tx input]
  (when-some [?sref-id (-> input :statement-input :statement-ref-id)]
    (->> {:ancestor-id ?sref-id}
         (f/query-statement-descendants tx)
         (map :descendant_id)
         (concat [?sref-id])
         vec)))
