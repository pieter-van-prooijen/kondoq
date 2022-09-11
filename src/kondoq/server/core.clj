(ns kondoq.server.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [kondoq.server.database :as db]
            [kondoq.server.etag :as etag]
            [kondoq.server.github :as github]
            [kondoq.server.web :as web]))

(def full-config (merge web/config
                        etag/config
                        db/config))

(defn init
  ([]
   (init full-config))
  ([config]
   (let [system (ig/init config)
         db (:kondoq/db system)
         path (get-in config [:kondoq/db :dbname])]
     (if (db/schema-exists db)
       (log/info "schema already present in database " path)
       (do
         (log/info "creating schema in database " path)
         (db/create-schema db)))
     system)))

(defn -main [& _]
  (init))

(defn import-batch [{:keys [urls token]}]
  (let [system (init (merge db/config etag/config)) ; No need for the web server.
        db (:kondoq/db system)
        etag-db (:kondoq/etag-db system)]
    (doseq [url urls]
      (log/infof "Upserting project %s" url)
      (github/upsert-project db etag-db url token)
      (log/infof "Added project %s" url))
    (System/exit 0))) ; clojure-cli doesn't exit automatically ?

(comment
  (defn- db [] (:kondoq/db integrant.repl.state/system))

  ;; Sometimes fails with "table namespaces already exists" but still creates
  ;; all the tables? Connection pool problem?
  (db/create-schema (db))
  (db/delete-schema (db))

  (db/search-namespaces (db) "clojure.core/inc")
  (db/fetch-projects-namespaces-usages (db) "cljs.core/str" 0 10)
  (db/search-projects (db))

  (db/search-symbol-counts (db) "%dec%" 5)

  )
