(ns kondoq.server
  (:require [integrant.core :as ig]
            [kondoq.database :as db]
            [kondoq.etag :as etag]
            [kondoq.web :as web]))

(def config (merge web/config
                   etag/config
                   db/config))

(defn -main [& _]
  (ig/init config))

(defn init-db [_]
  (let [system (ig/init config)]
    (db/create-schema (:kondoq/db system))
    (ig/halt! system)))

(comment
  (defn- db [] (:kondoq/db integrant.repl.state/system))

  ;; sometimes failes with "table namespaces already exists" but still creates
  ;; all the tables? Connection pool problem?
  (db/create-schema (db))
  (db/delete-schema (db))

  (db/search-namespaces (db) "clojure.core/inc")
  (db/fetch-projects-namespaces-occurrences (db) "cljs.core/str")
  (db/search-projects (db))

  (db/search-symbol-counts (db) "%dec%" 5)

  )
