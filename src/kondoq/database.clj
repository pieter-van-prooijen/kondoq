(ns kondoq.database
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [honey.sql.helpers :as sql-h]
            [integrant.core :as ig]
            [kondoq.analysis :as analysis]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as jdbc-connection]
            [next.jdbc.sql :as jdbc-sql])
  (:import  com.zaxxer.hikari.HikariDataSource))

(def config
  {:kondoq/db {:dbtype "sqlite"
               :dbname "data/kondoq.sqlite"
               :username "kondoq-user" ; hikari uses username instead of user
               :password "kondoq-password"
               :connectionInitSql (str "PRAGMA foreign_keys = ON;"
                                       "PRAGMA cache_size = 10000;"
                                       "PRAGMA journal_mode = WAL;")}})

;; Using a connection pool keeps the sqlite database file open between calls.
;; Causes problems with open connections not seeing changes made to the
;; database file in other connections (e.g. in delete/create schema)?
(defmethod ig/init-key :kondoq/db [_ config]
  (let [pool (-> (jdbc-connection/->pool HikariDataSource config)
                 (jdbc/with-options jdbc/unqualified-snake-kebab-opts))]
    (.close (jdbc/get-connection pool)) ; open a test connection
    pool))

;; CHECKME: does this rely on next.jdbc internals?
;; invoking .close on a next.jdbc connection doesn't work?
(defmethod ig/halt-key! :kondoq/db [_ pool]
  (let [connectable (:connectable pool)]
    (log/info "closing connection pool" connectable)
    (.close connectable)))

(defn create-schema [db]
  (jdbc/execute! db [(str "CREATE TABLE projects ("
                          " project TEXT PRIMARY KEY CHECK(length(project) <= 127),"
                          " location TEXT NOT NULL CHECK(length(location) <= 255))",)])
  (jdbc/execute! db ["CREATE INDEX projects_location_index ON projects(location)"])

  (jdbc/execute! db [(str "CREATE TABLE namespaces ("
                          " ns TEXT PRIMARY KEY,"
                          " location TEXT,"
                          " project TEXT NOT NULL,"
                          " FOREIGN KEY(project) REFERENCES projects(project) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED )")])
  (jdbc/execute! db ["CREATE INDEX namespaces_project_index ON namespaces(project)"])

  (jdbc/execute! db [(str "CREATE TABLE var_usages ("
                          " symbol TEXT NOT NULL,"
                          " arity INTEGER,"
                          " used_in_ns TEXT NOT NULL,"
                          " line_no INTEGER NOT NULL,"
                          " start_context INTEGER,"
                          " end_context INTEGER,"
                          " FOREIGN KEY(used_in_ns) REFERENCES namespaces(ns) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)")])
  (jdbc/execute! db ["CREATE INDEX var_usages_symbol_index ON var_usages(symbol)"])
  (jdbc/execute! db ["CREATE INDEX var_usages_arity_index ON var_usages(arity)"])
  (jdbc/execute! db ["CREATE INDEX var_usages_ns_index ON var_usages(used_in_ns)"])

  (jdbc/execute! db [(str "CREATE TABLE contexts ("
                          " ns TEXT NOT NULL,"
                          " start_context INTEGER NOT NULL,"
                          " single_line INTEGER NOT NULL,"
                          " source_code TEXT NOT NULL,"
                          " FOREIGN KEY(ns) REFERENCES namespaces(ns) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)")])
  (jdbc/execute! db ["CREATE UNIQUE INDEX contexts_index ON contexts(ns, start_context, single_line)"]))

(defn delete-schema [db]
  (jdbc/execute! db ["DROP TABLE IF EXISTS contexts"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS var_usages"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS namespaces"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS projects"]))

(defn schema-exists [db]
  (let [[{:keys [cnt]}]
        (jdbc/execute! db ["SELECT count(*) as cnt FROM sqlite_master WHERE type='table' AND name='var_usages'"])]
    (= cnt 1)))

(defn insert-project [db project location]
  (let [sql (-> (sql-h/insert-into :projects)
                ;; values does sql escaping
                (sql-h/values [{:project project
                                :location location}])
                (sql/format {:pretty true}))]
    (jdbc/execute! db sql)))

;; foreign key constraints should delete all usages/namespaces as well
(defn delete-project [db project]
  (let [sql (sql/format {:delete-from :projects
                         :where [:= :project :?project]}
                        {:params {:project project}})
        _ (jdbc/execute! db sql)]))

(defn delete-project-by-location [db location]
  (let [sql (sql/format {:delete-from :projects
                         :where [:= :location :?location]}
                        {:params {:location location}})
        _ (jdbc/execute! db sql)]))

;; inclusive range, 1- based (as is clj-kondo)
(defn extract-lines [lines from to]
  (->> lines
       (drop (dec from))
       (take (inc (- to from)))
       (string/join "\n")))

(defn insert-path [db project path location]
  (let [[namespace usages source-lines] (analysis/analyze path)]
    (when (and namespace (seq usages))
      (let [sql (-> (sql-h/insert-into :namespaces)
                    (sql-h/values [{:ns (str namespace)
                                    :location location
                                    :project project}])
                    (sql/format {:pretty true}))
            _ (jdbc/execute! db sql)
            sql (-> (sql-h/insert-into :var-usages)
                    (sql-h/values (map (fn [o]
                                         (-> o
                                             (update :symbol str)
                                             (update :used-in-ns str)))
                                       usages))
                    (sql/format {:pretty true}))
            _ (jdbc/execute! db sql)]

        ;; build the contexts for a usage (both single and multi-line)
        (doseq [{:keys [used-in-ns line-no start-context end-context]} usages]
          (let [sql (-> (sql-h/insert-into :contexts)
                        (sql-h/values [{:ns (str used-in-ns)
                                        :start-context start-context
                                        :single-line false
                                        :source_code (extract-lines source-lines start-context end-context)}])
                        (sql-h/upsert (-> (sql-h/on-conflict)
                                          (sql-h/do-nothing)))
                        (sql/format {:pretty true}))
                _ (jdbc/execute! db sql)
                sql (-> (sql-h/insert-into :contexts)
                        (sql-h/values [{:ns (str used-in-ns)
                                        :start-context line-no
                                        :single-line true
                                        :source_code (extract-lines source-lines line-no line-no)}])
                        (sql-h/upsert (-> (sql-h/on-conflict)
                                          (sql-h/do-nothing)))
                        (sql/format {:pretty true}))
                _ (jdbc/execute! db sql)]))))))

(defn- symbol-arity-where-clause [arity]
  (if (= -1 arity)
    [:and [:= :u.symbol :?symbol]]
    [:and
     [:= :u.symbol :?symbol]
     [:= :u.arity arity]]))

(defn search-usages [db fq-symbol-name arity page page-size]
  (let [sql (sql/format {:select-distinct [[:u.symbol :symbol]
                                           [:u.arity :arity]
                                           [:u.used-in-ns :used-in-ns]
                                           [:u.line-no :line-no]
                                           [:sc.source-code :line]
                                           [:u.start-context :start-context]
                                           [:u.end-context :end-context]
                                           [:mc.source-code :context]]
                         :from [[:var-usages :u]]
                         :where (symbol-arity-where-clause arity)
                         :order-by [[:n.project :asc] [:u.used-in-ns :asc] [:u.line-no :asc]]
                         :inner-join [[:namespaces :n]
                                      [:= :u.used-in-ns :n.ns]
                                      [:contexts :sc] ; single-line context
                                      [:and
                                       [:= :u.used-in-ns :sc.ns]
                                       [:= true :sc.single-line]
                                       [:= :u.line-no :sc.start-context]]
                                      [:contexts :mc] ; multi-line context
                                      [:and
                                       [:= :u.used-in-ns :mc.ns]
                                       [:= false :mc.single-line]
                                       [:= :u.start-context :mc.start-context]]]
                         :offset (* page page-size)
                         :limit page-size}
                        {:params {:symbol fq-symbol-name
                                  :arity arity}
                         :pretty true})]
    (jdbc-sql/query db sql)))

(defn search-usages-count [db fq-symbol-name arity]
  (let [sql (sql/format {:select [[[:count :*] :count]]
                         :from [[:var-usages :u]]
                         :where (symbol-arity-where-clause arity)}
                        {:params {:symbol fq-symbol-name
                                  :arity arity}
                         :pretty true})]
    (->> sql
         (jdbc-sql/query db)
         first
         :count)))

(defn search-namespaces [db fq-symbol-name arity]
  (let [sql (sql/format
             {:select-distinct [[:n.ns :ns]
                                [:n.project :project]
                                [:n.location :location]]
              :from [[:namespaces :n]]
              :where (symbol-arity-where-clause arity)
              :inner-join [[:var-usages :u] [:= :u.used-in-ns :n.ns]]}
             {:params {:symbol fq-symbol-name
                       :arity arity}
              :pretty true})]
    (jdbc-sql/query db sql)))

(defn search-projects [db]
(let [sql (sql/format
           {:select-distinct [[:p.project :project]
                              [:p.location :location]
                              [[:over [[:count :*]
                                       {:partition-by [:n.project]}
                                       :ns-count]]]]
            :from [[:projects :p]]
            :inner-join [[:namespaces :n] [:= :p.project :n.project]]
            :order-by [:project]}
           {:pretty true})]
  (jdbc-sql/query db sql))
  )

;;See https://cljdoc.org/d/com.github.seancorfield/honeysql/2.2.891/doc/getting-started/sql-clause-reference#window-partition-by-and-over for the windowing functions
(defn search-symbol-counts [db q limit]
  (let [sql (sql/format
             {:select-distinct [:symbol
                                :arity
                                [[:over [[:count :*]
                                         {:partition-by [:symbol :arity]}
                                         :count]]]
                                [[:over [[:count :*]
                                         {:partition-by [:symbol]}
                                         :all-arities-count]]]]
              :from [:var-usages]
              :where [:like :symbol :?q]
              :order-by [[:all-arities-count :desc] [:count :desc][:arity :asc]]
              :limit :?limit}
             {:params {:q q
                       :limit limit}
              :pretty true})]
    (->> (jdbc-sql/query db sql)
         (partition-by :symbol)
         ;; insert a pseudo entry for all arities in the list
         (mapcat (fn [symbols]
                   (if (> (count symbols) 1)
                     (let [{:keys [symbol all-arities-count]} (first symbols)]
                       (into [{:symbol symbol
                               :arity -1
                               :count all-arities-count
                               :all-arities-count
                               all-arities-count}]
                             symbols))
                     symbols))))))

(defn fetch-projects-namespaces-usages [db fq-symbol-name arity page page-size]
  (let [usages (->> (search-usages db fq-symbol-name arity page page-size)
                    (remove #(nil? (:symbol %))) ; result with nils caused by filter clauses?
                    (map (fn [o] (-> o
                                     (update :symbol symbol)
                                     (update :used-in-ns symbol)))))
        usages-count (search-usages-count db fq-symbol-name arity)
        ;; TODO: replace with ony the namespaces of the current page
        namespaces (->> (search-namespaces db fq-symbol-name arity)
                        (map (fn [ns] (-> ns
                                          (update :ns symbol)))))
        projects (search-projects db)]
    {:projects projects
     :namespaces namespaces
     :usages usages
     :usages-count usages-count
     :page page
     :page-size page-size}))

(comment

  (defn- db [] (:kondoq/db integrant.repl.state/system))

  (db)
  (search-symbol-counts (db) "%defn%" 10)
  (search-usages  (db) "cljs.core/inc" -1 0 10)
  (search-usages-count  (db) "cljs.core/defn" -1)
  (time (search-namespaces (db) "clojure.core/defn" "-1"))

  
  --sql
  (schema-exists (db))

  (jdbc/execute! (db) ["pragma cache_size;"])
  (delete-schema (db))
  (create-schema (db))
  )
