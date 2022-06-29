(ns kondoq.etag
  (:require [honey.sql :as sql]
            [honey.sql.helpers :as sql-h]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as jdbc-sql]))

;; Cache db which maps urls to etag/response bodies to minimize requests to
;; github. Etag hits do not count toward the request limit.
;; Separate db to keep the main code database clean.
;; TODO: ttl to remove obsolete urls, perhaps replace with ehache ?

(def config
  {:kondoq/etag-db {:dbtype "sqlite"
                    :dbname "data/etag-db.sqlite"
                    :user "kondoq-user"
                    :password "kondoq-password"}})

(defn create-etag-schema [db]
  (jdbc/execute! db [(str "CREATE TABLE IF NOT EXISTS etags ("
                          " url TEXT PRIMARY KEY,"
                          " body TEXT NOT NULL,"
                          " etag TEXT NOT NULL)")]))

(defn delete-etag-schema [db]
  (jdbc/execute! db ["DROP TABLE IF EXISTS etags"]))

(defmethod ig/init-key :kondoq/etag-db [_ config]
  (let [etag-db (jdbc/with-options config jdbc/unqualified-snake-kebab-opts)]
    (create-etag-schema etag-db)
    etag-db))

(defn get-etag-body [db url]
  (let [[{:keys [etag body]}]
        (->> (sql/format {:select [:etag :body]
                          :from [:etags]
                          :where [:= :url :?url]}
                         {:params {:url url}
                          :pretty true})
             (jdbc-sql/query db))]
    [etag body]))

(defn insert-etag-body [db url etag body]
  (jdbc/with-transaction [db-tx db]
    (let [body-str (pr-str body)
          sql (-> (sql-h/insert-into :etags)
                  (sql-h/values [{:url url :body body-str :etag etag}])
                  (sql-h/on-conflict :url)
                  (sql-h/do-update-set {:body body-str :etag etag})
                  (sql/format {:pretty true}))]
      (jdbc/execute! db-tx sql))))

(comment

  (defn- db [] (:kondoq/etag-db integrant.repl.state/system))

  (create-etag-schema (db))

  (get-etag-body (db) "https://api.github.com/repos/pieter-van-prooijen/frontpage/git/trees/master?recursive=true" )


  )
