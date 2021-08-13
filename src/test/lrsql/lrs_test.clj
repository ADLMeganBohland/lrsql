(ns lrsql.lrs-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.stuartsierra.component    :as component]
            [com.yetanalytics.lrs.protocol :as lrsp]
            [lrsql.test-support :as support]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Init Test Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Instrument
(support/instrument-lrsql)

;; New DB config
(use-fixtures :each support/fresh-db-fixture)

(def auth-ident
  {:agent {"objectType" "Agent"
           "account"    {"homePage" "http://example.org"
                         "name"     "12341234-0000-4000-1234-123412341234"}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- remove-props
  "Remove properties added by `prepare-statement`."
  [statement]
  (-> statement
      (dissoc "timestamp")
      (dissoc "stored")
      (dissoc "authority")
      (dissoc "version")))

(defn get-ss
  "Same as `lrsp/-get-statements` except that `remove-props` is applied
   on the results."
  [lrs auth-ident params ltags]
  (if (or (contains? params :statementId)
          (contains? params :voidedStatementId))
    (-> (lrsp/-get-statements lrs auth-ident params ltags)
        (update :statement remove-props))
    (-> (lrsp/-get-statements lrs auth-ident params ltags)
        (update-in [:statement-result :statements]
                   (partial map remove-props)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Need to have a non-zero UUID version, or else xapi-schema gets angry

(def stmt-0
  {"id"     "00000000-0000-4000-8000-000000000000"
   "actor"  {"mbox"       "mailto:sample.foo@example.com"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/answered"
             "display" {"en-US" "answered"}}
   "object" {"id" "http://www.example.com/tincan/activities/multipart"}})

(def stmt-1
  {"id"     "00000000-0000-4000-8000-000000000001"
   "actor"  {"mbox"       "mailto:sample.agent@example.com"
             "name"       "Sample Agent 1"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/answered"
             "display" {"en-US" "answered"}}
   "object" {"id"         "http://www.example.com/tincan/activities/multipart"
             "objectType" "Activity"
             "definition" {"name"        {"en-US" "Multi Part Activity"}
                           "description" {"en-US" "Multi Part Activity Description"}}}})

(def stmt-2
  {"id"     "00000000-0000-4000-8000-000000000002"
   "actor"  {"account"    {"name"     "Sample Agent 2"
                           "homePage" "https://example.org"}
             "name"       "Sample Agent 2"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/voided"
             "display" {"en-US" "Voided"}}
   "object" {"objectType" "StatementRef"
             "id"         "00000000-0000-4000-8000-000000000000"}})

(def stmt-3
  (-> stmt-1
      (assoc "id" "00000000-0000-4000-8000-000000000003")
      (assoc "actor" {"openid"     "https://example.org"
                      "name"       "Sample Agent 3"
                      "objectType" "Agent"})
      (assoc-in ["context" "instructor"] (get stmt-1 "actor"))
      (assoc-in ["object" "id"] "http://www.example.com/tincan/activities/multipart-2")
      (assoc-in ["context" "contextActivities" "other"] [(get stmt-1 "object")])))

(def stmt-4
  {"id"          "00000000-0000-4000-8000-000000000004"
   "actor"       {"mbox"       "mailto:sample.group.4@example.com"
                  "name"       "Sample Group 4"
                  "objectType" "Group"
                  "member"     [{"mbox" "mailto:member1@example.com"
                                 "name" "Group Member 1"}
                                {"mbox" "mailto:member2@example.com"
                                 "name" "Group Member 2"}
                                {"mbox" "mailto:member3@example.com"
                                 "name" "Group Member 4"}]}
   "verb"        {"id"      "http://adlnet.gov/expapi/verbs/attended"
                  "display" {"en-US" "attended"}}
   "object"      {"id"         "http://www.example.com/meetings/occurances/34534"
                  "definition" {"extensions"  {"http://example.com/profiles/meetings/activitydefinitionextensions/room"
                                               {"name" "Kilby"
                                                "id"   "http://example.com/rooms/342"}}
                                "name"        {"en-GB" "example meeting"
                                               "en-US" "example meeting"}
                                "description" {"en-GB" "An example meeting that happened on a specific occasion with certain people present."
                                               "en-US" "An example meeting that happened on a specific occasion with certain people present."}
                                "type"        "http://adlnet.gov/expapi/activities/meeting"
                                "moreInfo"    "http://virtualmeeting.example.com/345256"}
                  "objectType" "Activity"}
   "attachments" [{"usageType"   "http://example.com/attachment-usage/test"
                   "display"     {"en-US" "A test attachment"}
                   "description" {"en-US" "A test attachment (description)"}
                   "contentType" "text/plain"
                   "length"      27
                   "sha2"        "495395e777cd98da653df9615d09c0fd6bb2f8d4788394cd53c56a3bfdcd848a"}]})

(def stmt-4-attach
  {:content     (.getBytes "here is a simple attachment")
   :contentType "text/plain"
   :length      27
   :sha2        "495395e777cd98da653df9615d09c0fd6bb2f8d4788394cd53c56a3bfdcd848a"})

(deftest test-statement-fns
  (let [sys   (support/test-system)
        sys'  (component/start sys)
        lrs   (:lrs sys')
        id-0  (get stmt-0 "id")
        id-1  (get stmt-1 "id")
        id-2  (get stmt-2 "id")
        id-3  (get stmt-3 "id")
        id-4  (get stmt-4 "id")
        ts    "3000-01-01T01:00:00Z" ; Date far into the future
        agt-0 (-> stmt-0 (get "actor"))
        agt-1 (-> stmt-1 (get "actor") (dissoc "name"))
        grp-4 (-> stmt-4 (get "actor") (dissoc "name"))
        mem-4 (-> stmt-4 (get-in ["actor" "member" 0]) (dissoc "name"))
        vrb-1 (get-in stmt-1 ["verb" "id"])
        act-1 (get-in stmt-1 ["object" "id"])
        act-4 (get-in stmt-4 ["object" "id"])]

    (testing "statement insertions"
      (is (= {:statement-ids [id-0]}
             (lrsp/-store-statements lrs auth-ident [stmt-0] [])))
      (is (= {:statement-ids [id-1 id-2 id-3]}
             (lrsp/-store-statements lrs auth-ident [stmt-1 stmt-2 stmt-3] [])))
      (is (= {:statement-ids [id-4]}
             (lrsp/-store-statements lrs auth-ident [stmt-4] [stmt-4-attach]))))

    (testing "statement conflicts"
      (is (-> (lrsp/-store-statements lrs auth-ident [stmt-1 stmt-1] [])
              :error
              some?))
      (let [stmt-1' (assoc-in stmt-1 ["verb" "display" "en-US"] "ANSWERED")]
        (is (-> (lrsp/-store-statements lrs auth-ident [stmt-1'] [])
                :error
                some?)))
      (let [stmt-1'' (assoc-in stmt-1
                               ["actor" "mbox"]
                               "mailto:sample.agent.boo@example.com")]
        (try (lrsp/-store-statements lrs auth-ident [stmt-1''] [])
             (catch clojure.lang.ExceptionInfo e
               (is (= :com.yetanalytics.lrs.protocol/statement-conflict
                      (-> e ex-data :type)))))))

    (testing "statement ID queries"
      (is (= {:statement stmt-0}
             (get-ss lrs auth-ident {:voidedStatementId id-0} #{})))
      (is (= {:statement stmt-0}
             (get-ss lrs
                     auth-ident
                     {:voidedStatementId id-0 :format "canonical"}
                     #{"en-US"})))
      (is (= {:statement
              {"id"     id-1
               "actor"  {"objectType" "Agent"
                         "mbox"       "mailto:sample.agent@example.com"}
               "verb"   {"id" "http://adlnet.gov/expapi/verbs/answered"}
               "object" {"id" "http://www.example.com/tincan/activities/multipart"}}}
             (get-ss lrs auth-ident {:statementId id-1 :format "ids"} #{})))
      (is (= {:statement stmt-2}
             (get-ss lrs auth-ident {:statementId id-2} #{})))
      (is (= {:statement stmt-3}
             (get-ss lrs auth-ident {:statementId id-3} #{}))))

    (testing "statement property queries"
      (is (= {:statement-result {:statements [] :more ""}
              :attachments      []}
             (get-ss lrs auth-ident {:since ts} #{})))
      (is (= {:statement-result {:statements [stmt-4 stmt-3 stmt-2 stmt-1]
                                 :more       ""}
              :attachments      []}
             (get-ss lrs auth-ident {:until ts} #{})))
      (is (= {:statement-result {:statements [stmt-1 stmt-2 stmt-3 stmt-4]
                                 :more       ""}
              :attachments      []}
             (get-ss lrs auth-ident {:until ts :ascending true} #{})))
      (is (= {:statement-result {:statements [stmt-1] :more ""}
              :attachments      []}
             (get-ss lrs auth-ident {:agent agt-1} #{})))
      (is (= {:statement-result {:statements [stmt-3 stmt-1] :more ""}
              :attachments      []}
             (get-ss lrs auth-ident {:agent agt-1 :related_agents true} #{})))
      (is (= {:statement-result {:statements [stmt-4] :more ""}
              :attachments      []}
             (get-ss lrs auth-ident {:agent grp-4} #{})))
      (is (= {:statement-result {:statements [stmt-4] :more ""}
              :attachments      []}
             (get-ss lrs auth-ident {:agent mem-4} #{})))

      ;; XAPI-00162 - stmt-2 shows up because it refers to a statement, stmt-0,
      ;; that meets the query criteria, even though stmt-0 was voided.
      (testing "apply voiding"
        ;; stmt-0 itself cannot be directly queried, since it was voided.
        ;; However, stmt-2 is returned since it was not voided.
        (is (= {:statement-result {:statements [stmt-2] :more ""}
                :attachments      []}
               (get-ss lrs auth-ident {:agent agt-0} #{})))
        (is (= {:statement-result {:statements [stmt-3 stmt-2 stmt-1] :more ""}
                :attachments      []}
               (get-ss lrs auth-ident {:verb vrb-1} #{})))
        (is (= {:statement-result {:statements [stmt-2 stmt-1] :more ""}
                :attachments      []}
               (get-ss lrs auth-ident {:activity act-1} #{})))
        (is (= {:statement-result {:statements [stmt-3 stmt-2 stmt-1] :more ""}
                :attachments      []}
               (get-ss lrs
                       auth-ident
                       {:activity act-1 :related_activities true}
                       #{})))))

    (testing "with both activities and agents"
      (is (= {:statement-result {:statements [stmt-1] :more ""}
              :attachments      []}
             (get-ss lrs auth-ident {:activity act-1 :agent agt-1} #{})))
      (is (= {:statement-result {:statements [stmt-3 stmt-1] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:activity           act-1
                      :agent              agt-1
                      :related_activities true
                      :related_agents     true}
                     #{}))))

    (testing "querying with limits"
      (testing "(descending)"
        (is (= {:statement-result
                {:statements [stmt-4 stmt-3]
                 :more       "/xapi/statements?limit=2&from="}
                :attachments []}
               (-> (get-ss lrs auth-ident {:limit 2} #{})
                   (update-in [:statement-result :more]
                              #(->> % (re-matches #"(.*from=).*") second)))))
        (is (= {:statement-result {:statements [stmt-2 stmt-1] :more ""}
                :attachments      []}
               (let [more (-> (get-ss lrs auth-ident {:limit 2} #{})
                              (get-in [:statement-result :more]))
                     from (->> more (re-matches #".*from=(.*)") second)]
                 (get-ss lrs auth-ident {:limit 2 :from from} #{}))))))
    (testing "(ascending)"
      (is (= {:statement-result
              {:statements [stmt-1 stmt-2]
               :more       "/xapi/statements?limit=2&ascending=true&from="}
              :attachments []}
             (-> (get-ss lrs auth-ident {:limit 2 :ascending true} #{})
                 (update-in [:statement-result :more]
                            #(->> % (re-matches #"(.*from=).*") second)))))
      (is (= {:statement-result {:statements [stmt-3 stmt-4] :more ""}
              :attachments      []}
             (let [more (-> (get-ss lrs auth-ident {:limit 2 :ascending true} #{})
                            (get-in [:statement-result :more]))
                   from (->> more (re-matches #".*from=(.*)") second)]
               (get-ss lrs
                       auth-ident
                       {:limit 2 :ascending true :from from}
                       #{})))))

    (testing "(with actor)"
      (let [params {:limit 1
                    :agent {"name" "Sample Agent 1"
                            "mbox" "mailto:sample.agent@example.com"}
                    :related_agents true}]
        (is (= {:statement-result
                {:statements [stmt-3]
                 :more       (str "/xapi/statements"
                                  "?" "limit=1"
                                  "&" "agent=%7B%22name%22%3A%22Sample+Agent+1%22%2C%22mbox%22%3A%22mailto%3Asample.agent%40example.com%22%7D"
                                  "&" "related_agents=true"
                                  "&" "from=")}
                :attachments []}
               (-> (get-ss lrs auth-ident params #{})
                   (update-in [:statement-result :more]
                              #(->> % (re-matches #"(.*from=).*") second)))))
        (is (= {:statement-result {:statements [stmt-1] :more ""}
                :attachments      []}
               (let [more (-> (get-ss lrs auth-ident params #{})
                              (get-in [:statement-result :more]))
                     from (->> more (re-matches #".*from=(.*)") second)]
                 (get-ss lrs auth-ident (assoc params :from from) #{}))))))

    (testing "querying with attachments"
      (testing "(multiple)"
        (is (= {:statement-result {:statements [stmt-4] :more ""}
                :attachments      [(update stmt-4-attach :content #(String. %))]}
               (-> (get-ss lrs
                           auth-ident
                           {:activity act-4 :attachments true}
                           #{})
                   (update-in [:attachments]
                              vec)
                   (update-in [:attachments 0 :content]
                              #(String. %))))))

      (testing "(single)"
        (is (= {:statement    stmt-4
                :attachments  [(update stmt-4-attach :content #(String. %))]}
               (-> (get-ss lrs
                           auth-ident
                           {:statementId (get stmt-4 "id") :attachments true}
                           #{})
                   (update-in [:attachments]
                              vec)
                   (update-in [:attachments 0 :content]
                              #(String. %)))))))

    (testing "agent query"
      (is (= {:person
              {"objectType" "Person"
               "name" ["Sample Agent 1"]
               "mbox" ["mailto:sample.agent@example.com"]}}
             (lrsp/-get-person lrs auth-ident {:agent agt-1}))))

    (testing "activity query"
      ;; Activity was updated between stmt-0 and stmt-1
      (is (= {:activity
              {"id"         "http://www.example.com/tincan/activities/multipart"
               "objectType" "Activity"
               "definition" {"name"        {"en-US" "Multi Part Activity"}
                             "description" {"en-US" "Multi Part Activity Description"}}}}
             (lrsp/-get-activity lrs auth-ident {:activityId act-1}))))
    
    (component/stop sys')
    (support/unstrument-lrsql)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statement Ref Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def stmt-1'
  {"id"     "00000000-0000-4000-0000-000000000001"
   "actor"  {"mbox"       "mailto:sample.0@example.com"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/answered"
             "display" {"en-US" "answered"}}
   "object" {"id" "http://www.example.com/tincan/activities/multipart"}})

(def stmt-2'
  {"id"     "00000000-0000-4000-0000-000000000002"
   "actor"  {"mbox"       "mailto:sample.1@example.com"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/answered"
             "display" {"en-US" "answered"}}
   "object" {"objectType" "StatementRef"
             "id" "00000000-0000-4000-0000-000000000001"}})

(def stmt-3'
  {"id"     "00000000-0000-4000-0000-000000000003"
   "actor"  {"mbox"       "mailto:sample.2@example.com"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/answered"
             "display" {"en-US" "answered"}}
   "object" {"objectType" "StatementRef"
             "id" "00000000-0000-4000-0000-000000000002"}})

(def stmt-4'
  {"id"     "00000000-0000-4000-0000-000000000004"
   "actor"  {"mbox"       "mailto:sample3@example.com"
             "objectType" "Agent"}
   "verb"   {"id"      "http://adlnet.gov/expapi/verbs/voided"
             "display" {"en-US" "voided"}}
   "object" {"objectType" "StatementRef"
             "id" "00000000-0000-4000-0000-000000000003"}})

(deftest test-statement-ref-fns
  (let [sys  (support/test-system)
        sys' (component/start sys)
        lrs  (:lrs sys')
        ts   "3000-01-01T01:00:00Z"]
    (testing "statement insertions"
      (is (= {:statement-ids ["00000000-0000-4000-0000-000000000001"
                              "00000000-0000-4000-0000-000000000002"
                              "00000000-0000-4000-0000-000000000003"]}
             (lrsp/-store-statements lrs auth-ident [stmt-1' stmt-2' stmt-3'] []))))

    (testing "statement queries"
      (is (= {:statement-result {:statements [stmt-3' stmt-2' stmt-1'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:agent {"mbox" "mailto:sample.0@example.com"
                              "objectType" "Agent"}}
                     #{})))
      (is (= {:statement-result {:statements [stmt-3' stmt-2' stmt-1'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:activity "http://www.example.com/tincan/activities/multipart"}
                     #{})))
      (is (= {:statement-result {:statements [stmt-3' stmt-2' stmt-1'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:verb "http://adlnet.gov/expapi/verbs/answered"}
                     #{})))
      (is (= {:statement-result {:statements [] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:since ts}
                     #{})))
      (is (= {:statement-result {:statements [stmt-1' stmt-2' stmt-3'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:until ts :ascending true}
                     #{})))
      (is (= {:statement-result {:statements [stmt-3']}
              :attachments      []}
             (-> (get-ss lrs
                         auth-ident
                         {:activity "http://www.example.com/tincan/activities/multipart"
                          :limit    1}
                         #{})
                 (update :statement-result dissoc :more)))))

    (testing "don't return voided statement refs"
      (is (= {:statement-ids ["00000000-0000-4000-0000-000000000004"]}
             (lrsp/-store-statements lrs auth-ident [stmt-4'] [])))
      ;; stmt-4' is returned because it targets stmt-3', whose voided status
      ;; does not matter. On the other hand, stmt-3' itself is not returned
      ;; because it was voided.
      (is (= {:statement-result {:statements [stmt-4'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:agent {"mbox" "mailto:sample.2@example.com"}}
                     #{})))
      ;; stmt-2' is returned directly (not as a stmt ref target)
      ;; since it is not voided.
      (is (= {:statement-result {:statements [stmt-4' stmt-2'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:agent {"mbox" "mailto:sample.1@example.com"}}
                     #{})))
      ;; stmt-1' is returned directly (not as a stmt ref target).
      ;; stmt-2' is returned since it refers to stmt-1' and is not voided
      (is (= {:statement-result {:statements [stmt-4' stmt-2' stmt-1'] :more ""}
              :attachments      []}
             (get-ss lrs
                     auth-ident
                     {:agent {"mbox" "mailto:sample.0@example.com"}}
                     #{}))))

    (component/stop sys')))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Document Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def state-id-params
  {:stateId    "some-id"
   :activityId "https://example.org/activity-type"
   :agent      {"mbox" "mailto:example@example.org"}})

(def state-id-params-2
  {:stateId "some-other-id"
   :activityId "https://example.org/activity-type"
   :agent      {"mbox" "mailto:example@example.org"}})

(def state-doc-1
  {:content-length 17
   :content-type   "application/json"
   :contents       (.getBytes "{\"foo\":1,\"bar\":2}")})

(def state-doc-2
  {:content-length 10
   :content-type   "application/json"
   :contents       (.getBytes "{\"foo\":10}")})

(def agent-prof-id-params
  {:profileId "https://example.org/some-profile"
   :agent     {"mbox" "mailto:foo@example.org"
               "name" "Foo Bar"}})

(def agent-prof-doc
  {:content-length 16
   :content-type   "text/plain"
   :contents       (.getBytes "Example Document")})

(def activity-prof-id-params
  {:profileId  "https://example.org/some-profile"
   :activityId "https://example.org/some-activity"})

(def activity-prof-doc
  {:content-length 18
   :content-type  "text/plain"
   :contents      (.getBytes "Example Document 2")})

(defn- get-doc
  "Same as lrsp/-get-documents except automatically formats the result."
  [lrs auth-ident params]
  (-> (lrsp/-get-document lrs auth-ident params)
      (update :document dissoc :updated)
      (update-in [:document :contents] #(String. %))))

(deftest test-document-fns
  (let [sys  (support/test-system)
        sys' (component/start sys)
        lrs  (:lrs sys')]
    (testing "document insertion"
      (support/seq-is
       {}
       [(lrsp/-set-document lrs
                            auth-ident
                            state-id-params
                            state-doc-1
                            true)
        (lrsp/-set-document lrs
                            auth-ident
                            state-id-params
                            state-doc-2
                            true)
        (lrsp/-set-document lrs
                            auth-ident
                            state-id-params-2
                            state-doc-1
                            false)
        (lrsp/-set-document lrs
                            auth-ident
                            state-id-params-2
                            state-doc-2
                            false)
        (lrsp/-set-document lrs
                            auth-ident
                            agent-prof-id-params
                            agent-prof-doc
                            false)
        (lrsp/-set-document lrs
                            auth-ident
                            activity-prof-id-params
                            activity-prof-doc
                            false)]))

    (testing "document query"
      (is (= {:document
              {:contents       "{\"foo\":10,\"bar\":2}"
               :content-length 18
               :content-type   "application/json"
               :id             "some-id"}}
             (get-doc lrs auth-ident state-id-params)))
      (is (= {:document
              {:contents       "{\"foo\":10}"
               :content-length 10
               :content-type   "application/json"
               :id             "some-other-id"}}
             (get-doc lrs auth-ident state-id-params-2)))
      (is (= {:document
              {:contents       "Example Document"
               :content-length 16
               :content-type   "text/plain"
               :id             "https://example.org/some-profile"}}
             (get-doc lrs auth-ident agent-prof-id-params)))
      (is (= {:document
              {:contents       "Example Document 2"
               :content-length 18
               :content-type   "text/plain"
               :id             "https://example.org/some-profile"}}
             (get-doc lrs auth-ident activity-prof-id-params))))
  
    (testing "document ID query"
      (is (= {:document-ids ["some-id" "some-other-id"]}
             (lrsp/-get-document-ids
              lrs
              auth-ident
              (dissoc state-id-params :stateId))))
      (is (= {:document-ids ["https://example.org/some-profile"]}
             (lrsp/-get-document-ids
              lrs
              auth-ident
              (dissoc agent-prof-id-params :profileId))))
      (is (= {:document-ids ["https://example.org/some-profile"]}
             (lrsp/-get-document-ids
              lrs
              auth-ident
              (dissoc activity-prof-id-params :profileId)))))
  
    (testing "document deletion"
      (support/seq-is
       {}
       [(lrsp/-delete-documents lrs
                                auth-ident
                                (dissoc state-id-params :stateId))
        (lrsp/-delete-document lrs
                               auth-ident
                               agent-prof-id-params)
        (lrsp/-delete-document lrs
                               auth-ident
                               activity-prof-id-params)])
      (support/seq-is
       {:document nil}
       [(lrsp/-get-document lrs
                            auth-ident
                            state-id-params)
        (lrsp/-get-document lrs
                            auth-ident
                            agent-prof-id-params)
        (lrsp/-get-document lrs
                            auth-ident
                            activity-prof-id-params)]))
    
    (component/stop sys')))
