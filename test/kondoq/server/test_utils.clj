(ns kondoq.server.test-utils
  "Database setup and teardown fixtures."
  (:require [integrant.core :as ig]
            [kondoq.server.database :as db]
            [kondoq.server.etag :as etag]))

(def ^:dynamic *system*)
(def ^:dynamic *db*)
(def ^:dynamic *etag-db*)

(defn system-fixture [f]
  (let [test-db-file (java.io.File/createTempFile "kondoq-test" ".sqlite")
        test-etag-db-file (java.io.File/createTempFile "kondoq-test-etag" ".sqlite")
        config (-> (merge db/config etag/config)
                   (assoc-in [:kondoq/db :dbname] (.getAbsolutePath test-db-file))
                   (assoc-in [:kondoq/etag-db :dbname] (.getAbsolutePath test-etag-db-file)))
        system (ig/init config)] ; with-redefs is parallel
    (with-redefs [*system* system
                  *db* (:kondoq/db system)
                  *etag-db* (:kondoq/etag-db system)]
      (try
        (f)
        (finally
          (ig/halt! *system*)
          (.delete test-db-file)
          (.delete test-etag-db-file))))))

;; When this gives "table already exists" errors when running tests from cider,
;; it might be that the sqlite file is locked. Try closing the repl first.
(defn database-fixture [f]
  (db/delete-schema *db*)
  (db/create-schema *db*)
  (etag/delete-etag-schema *etag-db*)
  (etag/create-etag-schema *etag-db*)
  (f))

