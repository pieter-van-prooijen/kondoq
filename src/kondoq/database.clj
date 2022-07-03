(ns kondoq.database
  (:require [clojure.tools.logging :as log]
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

  (jdbc/execute! db [(str "CREATE TABLE occurrences ("
                          " symbol TEXT NOT NULL,"
                          " ns TEXT NOT NULL,"
                          " line_no INTEGER NOT NULL,"
                          " line TEXT NOT NULL,"
                          " start_context INTEGER,"
                          " context TEXT,"
                          " FOREIGN KEY(ns) REFERENCES namespaces(ns) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED )")])
  (jdbc/execute! db ["CREATE INDEX occurrences_symbol_index ON occurrences(symbol)"])
  (jdbc/execute! db ["CREATE INDEX occurrences_ns_index ON occurrences(ns)"]))

(defn delete-schema [db]
  (jdbc/execute! db ["DROP TABLE IF EXISTS occurrences"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS namespaces"])
  (jdbc/execute! db ["DROP TABLE IF EXISTS projects"]))

(defn schema-exists [db]
  (let [[{:keys [cnt]}]
        (jdbc/execute! db ["SELECT count(*) as cnt FROM sqlite_master WHERE type='table' AND name='occurrences'"])]
    (= cnt 1)))

(defn insert-project [db project location]
  (let [sql (-> (sql-h/insert-into :projects)
                ;; values does sql escaping
                (sql-h/values [{:project project
                                :location location}])
                (sql/format {:pretty true}))]
    (jdbc/execute! db sql)))

;; foreign key constraints should delete all occurrences/namespaces as well
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

;;
;; Temporary project properties while they are being added.
;; In *memory*, works because of single-writer
(def current-projects (atom {}))

(defn init-project-status [location project-future]
  (swap! current-projects (fn [m]
                            (assoc m location {:future project-future
                                               :location location
                                               :project ""
                                               :ns-count 0
                                               :ns-total -1}))))

(defn update-project-status [location project ns-count ns-total current-file]
  (swap! current-projects (fn [m]
                            (update m location
                                    (fn [p]
                                      (merge p {:location location
                                                :project project
                                                :ns-count ns-count
                                                :ns-total ns-total
                                                :current-file current-file}))))))

(defn update-project-with-error [location error]
  (swap! current-projects (fn [m]
                            (update m location
                                    (fn [p]
                                      (merge p {:location location
                                                :error error}))))))

(defn fetch-project-status [location]
  (get @current-projects location))

(defn delete-project-status [location]
  (swap! current-projects (fn [m] (dissoc m location))))

(defn insert-path [db project path location]
  (let [[namespace occurrences] (analysis/analyze path)]
    (when (and namespace (seq occurrences))
      (let [sql (-> (sql-h/insert-into :namespaces)
                    (sql-h/values [{:ns (str namespace)
                                    :location location
                                    :project project}])
                    (sql/format {:pretty true}))
            _ (jdbc/execute! db sql)
            sql (-> (sql-h/insert-into :occurrences)
                    (sql-h/values (map (fn [o]
                                         (-> o
                                             (update :symbol str)
                                             (update :ns str)))
                                       occurrences))
                    (sql/format {:pretty true}))
            _ (jdbc/execute! db sql)]))))

(defn search-occurrences [db fq-symbol-name]
(let [sql (sql/format {:select [[:o.symbol :symbol]
                                [:o.ns :ns]
                                [:o.line-no :line-no]
                                [:o.line :line]
                                [:o.start-context :start-context]
                                [:o.context :context]]
                       :from [[:occurrences :o]]
                       :where [:= :o.symbol :?symbol-name]
                       :order-by [[:namespaces.project :asc] [:o.ns :asc] [:o.line-no :asc]]
                       :inner-join [:namespaces [:= :o.ns :namespaces.ns]]}
                      {:params {:symbol-name fq-symbol-name}
                       :pretty true})]
  (jdbc-sql/query db sql)))

(defn search-namespaces [db fq-symbol-name]
(let [sql (sql/format
           {:select-distinct [[:n.ns :ns]
                              [:n.project :project]
                              [:n.location :location]]
            :from [[:namespaces :n] [:occurrences :o]]
            :where [:= :o.symbol :?symbol-name]
            :inner-join [:namespaces [:= :o.ns :namespaces.ns]]}
           {:params {:symbol-name fq-symbol-name}
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
                                [[:over [[:count :*]
                                         {:partition-by [:symbol]}
                                         :count]]]]
              :from [:occurrences]
              :where [:like :symbol :?q]
              :order-by [[:count :desc]]
              :limit :?limit}
             {:params {:q q
                       :limit limit}
              :pretty true})]
    (jdbc-sql/query db sql)))

(defn fetch-projects-namespaces-occurrences [db fq-symbol-name]
  (let [occurrences (->> (search-occurrences db fq-symbol-name)
                         (map (fn [o] (-> o
                                          (update :symbol symbol)
                                          (update :ns symbol)))))
        namespaces (->> (search-namespaces db fq-symbol-name)
                        (map (fn [ns] (-> ns
                                          (update :ns symbol)))))
        projects (search-projects db)]
    {:projects projects
     :namespaces namespaces
     :occurrences occurrences}))

(comment

  (defn- db [] (:kondoq/db integrant.repl.state/system))

  (db)
  (schema-exists (db))

  (delete-schema (db))
  (create-schema (db))
  )
