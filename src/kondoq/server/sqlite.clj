(ns kondoq.server.sqlite
  "Sqlite specific connection pool handling."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc])
  (:import [javax.sql DataSource]
           [java.lang AutoCloseable]))

;; HikariCP's connectionInitSql only allows a single statement to be executed?
;; Wrap the original datasource to initialize with the correct sqlite pragmas upon
;; connection.

(def pragmas {:journal_mode "WAL"
              :foreign_keys "ON"
              :cache_size 10000         ; In 4k pages.
              :synchronous "NORMAL"})

(defn- init-sqlite [conn]
  (doseq [[pragma value] pragmas]
    (jdbc/execute! conn [(str "PRAGMA " (if value (str (name pragma) " = " value) (name pragma)))])))

;; DS should be an auto-closeable datasource instance, like Hikaridatasource.
(defn wrap-datasource [ds]
  (reify DataSource
    (getConnection [_]
      (let [conn (.getConnection ds)]
        (init-sqlite conn)
        conn))
    (getConnection [_ username password]
      (let [conn (.getConnection ds username password)]
        (init-sqlite conn)
        conn))
    AutoCloseable
    (close [_]
      (.close ds))))

(defn get-pragma [conn k]
  (-> conn
      (jdbc/execute! [(str "PRAGMA " (name k))])
      (get-in [0 k])))

(defn log-pragmas [conn]
  (log/info (str "sqlite settings: "
                 (->> (keys pragmas)
                      (map #(vector (name %) (get-pragma conn %)))
                      (string/join)))))
