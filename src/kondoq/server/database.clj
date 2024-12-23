(ns kondoq.server.database
  "Database connections, schemas, actions and queries."
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [honey.sql :as sql]
   [honey.sql.helpers :as sql-h]
   [integrant.core :as ig]
   [kondoq.server.sqlite :as sqlite]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as jdbc-connection]
   [next.jdbc.result-set :as rs]
   [next.jdbc.sql :as jdbc-sql])
  (:import
   (com.zaxxer.hikari HikariDataSource)))

(def config
  {:kondoq/db {:dbtype "sqlite"
               :dbname "data/kondoq.sqlite"
               :username "kondoq-user" ; hikari uses username instead of user
               :password "kondoq-password"}})


(defn- apply-connection-options [connectable]
  (jdbc/with-options connectable jdbc/unqualified-snake-kebab-opts))

;; Using a connection pool keeps the sqlite database file open between calls to
;; prevent reparsing the schema etc. on each database action.
(defmethod ig/init-key :kondoq/db [_ config]
  (let [pool (->> config
                  (jdbc-connection/->pool HikariDataSource)
                  sqlite/wrap-datasource
                  apply-connection-options)]
    (with-open [conn (jdbc/get-connection pool)]
      (sqlite/log-pragmas conn)
      pool)))

(defmethod ig/halt-key! :kondoq/db [_ pool]
  ;; CHECKME: does getting the connection like this rely on next.jdbc internals?
  ;; invoking .close on a next.jdbc connection itself doesn't work?
  (let [connectable (:connectable pool)]
    (log/info "closing connection pool" connectable)
    (.close connectable)))

(defn create-schema [db]
  (jdbc/execute! db [(str "CREATE TABLE projects ("
                          " project TEXT PRIMARY KEY CHECK(length(project) <= 127),"
                          " location TEXT NOT NULL CHECK(length(location) <= 255),"
                          " nof_stars INTEGER)"
                          " STRICT")])
  (jdbc/execute! db ["CREATE INDEX projects_location_index ON projects(location)"])

  (jdbc/execute! db [(str "CREATE TABLE namespaces ("
                          " ns TEXT, "
                          " language TEXT NOT NULL,"
                          " test INTEGER NOT NULL,"
                          " location TEXT NOT NULL,"
                          " project TEXT NOT NULL,"
                          " PRIMARY KEY(ns, language)"
                          " FOREIGN KEY(project) REFERENCES projects(project)"
                          "   ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED )"
                          "   STRICT")])
  (jdbc/execute! db ["CREATE INDEX namespaces_project_index ON namespaces(project)"])

  (jdbc/execute! db [(str "CREATE TABLE var_usages ("
                          " symbol TEXT NOT NULL,"
                          " arity INTEGER,"
                          " used_in_ns TEXT NOT NULL,"
                          " language TEXT NOT NULL,"
                          " line_no INTEGER NOT NULL,"
                          " column_no INTEGER NOT NULL,"
                          " start_context INTEGER,"
                          " end_context INTEGER,"
                          " FOREIGN KEY(used_in_ns, language) REFERENCES namespaces(ns, language)"
                          "   ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)"
                          "   STRICT")])
  (jdbc/execute! db ["CREATE INDEX var_usages_symbol_index ON var_usages(symbol)"])
  (jdbc/execute! db ["CREATE INDEX var_usages_arity_index ON var_usages(arity)"])
  (jdbc/execute! db ["CREATE INDEX var_usages_ns_index ON var_usages(used_in_ns)"])

  (jdbc/execute! db [(str "CREATE TABLE contexts ("
                          " ns TEXT NOT NULL,",
                          " language TEXT NOT NULL, "
                          " start_context INTEGER NOT NULL,"
                          " single_line INTEGER NOT NULL,"
                          " source_code TEXT NOT NULL,"
                          " FOREIGN KEY(ns, language) REFERENCES namespaces(ns, language)"
                          "   ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED)"
                          "   STRICT")])
  (jdbc/execute! db ["CREATE UNIQUE INDEX contexts_index ON contexts(ns, start_context, single_line)"]))

(defn delete-schema [db]
  (jdbc/execute! db ["DROP TABLE IF EXISTS contexts"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS var_usages"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS namespaces"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS projects"]))

(defn schema-exists [db]
  (let [tables (with-open [con (jdbc/get-connection db)]
                 (-> con
                     .getMetaData
                     (.getTables nil nil nil (into-array ["TABLE" "VIEW"]))
                     (rs/datafiable-result-set jdbc/unqualified-snake-kebab-opts)))]
    (->> tables
         (map :table-name)
         (into #{})
         (= #{"contexts" "namespaces" "projects" "var_usages"}))))

;; Used in project uploading, so doesn't use a separate transaction.
(defn insert-project
  "Insert a new project named `project` into `db` located at url `location`."
  [db project location nof-stars]
  (let [sql (-> (sql-h/insert-into :projects)
                (sql-h/values [{:project project
                                :location location
                                :nof-stars nof-stars}])
                (sql/format {:pretty true}))]
    (jdbc/execute! db sql)))

;; Used when rolling back a project insertion, so doesn't use a separate
;; transaction.
(defn delete-project
  "Delete `project` from the database `db`, removing all related usages, namespaces
  and source file contexts.
  Foreign key cascade constraints take care of deleting these child items."
  [db project]
  (let [sql (sql/format {:delete-from :projects
                         :where [:= :project :?project]}
                        {:params {:project project}})
        _ (jdbc/execute! db sql)]))

;; Used by the API to delete a project.
(defn delete-project-by-location [db location]
  (let [sql (sql/format {:delete-from :projects
                         :where [:= :location :?location]}
                        {:params {:location location}})]
    ;; Use a transaction to ensure any subsequent database actions see the
    ;; change. (in sqlite parlance, they use this commit as their end mark in the
    ;; write-ahead log).
    (jdbc/with-transaction [db-tx db]
      (jdbc/execute! db-tx sql))))

(defn- source-lines-as-string
  "Return the source code from a sequence of `source-lines` starting at `from` up to
  and including `to` as a single string."
  [source-lines from to]
  (->> source-lines
       (drop (dec from))
       (take (inc (- to from)))
       (string/join "\n")))

(defn- insert-contexts [db usages source-lines]
  (doseq [{:keys [used-in-ns language line-no start-context end-context]} usages]
    (let [;; Multi line context.
          sql (-> (sql-h/insert-into :contexts)
                  (sql-h/values [{:ns (str used-in-ns)
                                  :language (name language)
                                  :start-context start-context
                                  :single-line false
                                  :source_code (source-lines-as-string
                                                source-lines
                                                start-context
                                                end-context)}])
                  (sql-h/upsert (-> (sql-h/on-conflict)
                                    (sql-h/do-nothing)))
                  (sql/format {:pretty true}))
          _ (jdbc/execute! db sql)
          ;; Single line context.
          sql (-> (sql-h/insert-into :contexts)
                  (sql-h/values [{:ns (str used-in-ns)
                                  :language (name language)
                                  :start-context line-no
                                  :single-line true
                                  :source_code (source-lines-as-string
                                                source-lines
                                                line-no
                                                line-no)}])
                  (sql-h/upsert (-> (sql-h/on-conflict)
                                    (sql-h/do-nothing)))
                  (sql/format {:pretty true}))
          _ (jdbc/execute! db sql)])))

(defn insert-namespace
  "Insert the var usages present in `namespace-analysis` (obtained using
  [[kondoq.analysis/analyze]]) in `db`.
  The namespace will be marked as belonging to `project` and having a public
  url of `location`."
  [db namespace-analysis project location]
  (let [{:keys [namespace language test-namespace usages source-lines]}
        namespace-analysis]
    (when (and namespace (seq usages))
      (let [sql (-> (sql-h/insert-into :namespaces)
                    (sql-h/values [{:ns (str namespace)
                                    :language (name language)
                                    :test test-namespace
                                    :location location
                                    :project project}])
                    (sql/format {:pretty true}))
            _ (jdbc/execute! db sql)
            sql (-> (sql-h/insert-into :var-usages)
                    (sql-h/values (map (fn [o]
                                         (-> o
                                             (update :symbol str)
                                             (update :used-in-ns str)
                                             (update :language name)))
                                       usages))
                    (sql/format {:pretty true}))
            _ (jdbc/execute! db sql)]
        (insert-contexts db usages source-lines)))))

(defn- symbol-arity-where-clause [arity]
  (if (= -1 arity)
    [:and [:= :u.symbol :?symbol]]
    [:and
     [:= :u.symbol :?symbol]
     [:= :u.arity arity]]))

(defn search-usages
  "Search for var usages of string `fq-symbol-name` with arity `arity`, returning
  the results for page `page` (with a size of `page-size`).
  Arity can be a specific number, -1 (all arities) or nil (arity-less usage only)."
  [db fq-symbol-name arity page page-size]
  (let [sql (sql/format {:select-distinct [[:u.symbol :symbol]
                                           [:u.arity :arity]
                                           [:u.used-in-ns :used-in-ns]
                                           [:u.line-no :line-no]
                                           [:u.column-no :column-no]
                                           [:sc.source-code :line]
                                           [:u.start-context :start-context]
                                           [:u.end-context :end-context]
                                           [:mc.source-code :context]]
                         :from [[:var-usages :u]]
                         :where (symbol-arity-where-clause arity)
                         :order-by [[:n.test :asc] ; Test namespaces come last.
                                    [:n.project :asc]
                                    [:u.used-in-ns :asc]
                                    [:u.line-no :asc]]
                         :inner-join [[:namespaces :n]
                                      [:= :u.used-in-ns :n.ns]
                                      [:contexts :sc] ; Single-line context.
                                      [:and
                                       [:= :u.used-in-ns :sc.ns]
                                       [:= true :sc.single-line]
                                       [:= :u.line-no :sc.start-context]]
                                      [:contexts :mc] ; Multi-line context.
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

(defn search-usages-count
  "Search for the count of var usages of string `fq-symbol-name` with arity `arity`.
  Arity has the same meaning as in [[search-usages]]."
  [db fq-symbol-name arity]
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

(defn search-namespaces
  "Search for the namespaces containing usages of string `fq-symbol-name` with
  arity `arity`.
  Arity has the same meaning as in [[search-usages]]."
  [db fq-symbol-name arity]
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

(defn search-projects
  "Search for all projects present in `db`.
  Projects have a name, location nof-stars and namespace count. "
  [db]
  (let [sql (sql/format
             {:select-distinct [[:p.project :project]
                                [:p.location :location]
                                [:p.nof-stars :nof-stars]
                                [[:over [[:count :*]
                                         {:partition-by [:n.project]}
                                         :ns-count]]]]
              :from [[:projects :p]]
              :inner-join [[:namespaces :n] [:= :p.project :n.project]]
              :order-by [:project]}
             {:pretty true})]
    (jdbc-sql/query db sql)))

;; See https://cljdoc.org/d/com.github.seancorfield/honeysql/2.2.891/doc/getting-started/sql-clause-reference#window-partition-by-and-over for the windowing functions
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
              :where [[:like :symbol :?q]]
              :order-by [[:all-arities-count :desc] [:count :desc][:arity :asc]]
              :limit :?limit}
             {:params {:q q
                       :limit limit}
              :pretty true})]
    (->> (jdbc-sql/query db sql)
         (map #(update % :symbol symbol))
         (partition-by :symbol)
         ;; Insert a pseudo entry for all arities in the list if there is more
         ;; than one arity for a var.
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

(defn search-namespaces-usages
  "Search for all usages of string `fq-symbol-name` with arity `arity`, returning a `page` page-number of `page-size` usages.
  Returns a map of:
  - usages: the current page of found usages.
  - namespaces: all namespaces of the current page of usages.
  - usages-count: total count of usages.
  - page: the `page` of this result
  - page-size: the `page-size` of this result"
  [db fq-symbol-name arity page page-size]
  (let [usages (->> (search-usages db fq-symbol-name arity page page-size)
                    (remove #(nil? (:symbol %))) ; result with nils caused by filter clauses?
                    (map (fn [u] (-> u
                                     (update :symbol symbol)
                                     (update :used-in-ns symbol)))))
        used-in-namespaces (into #{} (map :used-in-ns usages))
        usages-count (search-usages-count db fq-symbol-name arity)
        namespaces (->> (search-namespaces db fq-symbol-name arity)
                        (map (fn [ns] (update ns :ns symbol)))
                        ;; Only return the namespaces in the current page of
                        ;; usages.
                        (filter (fn [ns] (used-in-namespaces (:ns ns)))))]
    {:usages usages
     :namespaces namespaces
     :usages-count usages-count
     :page page
     :page-size page-size}))

(comment

  (defn- db [] (:kondoq/db integrant.repl.state/system))

  (db)
  (search-symbol-counts (db) "%defn%" 10)
  (count (search-usages  (db) "integrant.core/ref" nil 0 100))
  (search-usages-count  (db) "integrant.core/ref" nil)
  (time (search-namespaces (db) "clojure.core/defn" "-1"))

  (with-open [con (jdbc/get-connection (db))]
    (-> con
        .getMetaData
        (.getTables nil nil nil (into-array ["TABLE" "VIEW"]))
        (rs/datafiable-result-set jdbc/unqualified-snake-kebab-opts)))

  (schema-exists (db))

  (jdbc/execute-one! (db) ["select count(*) as count from namespaces"])
  (jdbc/get-connection (db))
  (jdbc/execute! (jdbc/get-connection (db)) ["pragma journal_mode"])
  (jdbc/execute! (db) [(str "PRAGMA journal_mode = WAL, foreign_keys = ON; "
                            "PRAGMA foreign_keys = ON; "
                            "PRAGMA cache_size = 10000;" ; in 4k pages
                            )])
  (delete-schema (db))
  (create-schema (db))
  )
