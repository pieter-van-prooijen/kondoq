(ns kondoq.test-utils
  (:require [clojure.test :as t]
            [integrant.core :as ig]
            [kondoq.database :as db]))

;;
;; Checkme: can this run in parallel with the regular repl system ?
;; seeing "table already exists" errors
(def ^:dynamic *system*)
(def ^:dynamic *db*)

(defn system-fixture [f]
  (let [test-db-file (java.io.File/createTempFile "kondoq-test" ".sqlite")
        config (assoc-in db/config
                         [:kondoq/db :dbname]
                         (.getAbsolutePath test-db-file))
        system (ig/init config)] ; with-redefs is parallel
    (with-redefs [*system* system
                  *db* (:kondoq/db system)]
      (try
        (f)
        (finally
          (ig/halt! *system*)
          (.delete test-db-file))))))

(defn database-fixture [f]
  (db/delete-schema *db*)
  (db/create-schema *db*)
  (f))

