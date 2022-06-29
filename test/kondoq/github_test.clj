(ns kondoq.github-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest testing is]:as t]
            [integrant.core :as ig]
            [kondoq.database :as db]
            [kondoq.github :refer [upsert-project fetch-github-resource]])
  (:import java.util.Base64))

;;
;; Checkme: can this run in parallel with the regular repl system ?
;; seeing "table already exists" errors
(def ^:dynamic *system*)
(def ^:dynamic *db*)
(def ^:dynamic *slow* false)

;; simulate retrieving a base64 encoded source blob from this project's sources
(defn project-source-file-blob [source-file]
  (->> (str "src/kondoq/" source-file)
       slurp
       .getBytes
       (.encode (Base64/getMimeEncoder))
       String.))

(def source-files {:tree [{:path "database.clj"
                           :url "http://example.com/database.clj"
                           :sha "sha-1"}
                          {:path "web.clj"
                           :url "http://example.com/web.clj"
                           :sha "sha-2"}]})

(def blobs {"http://example.com/database.clj"
            {:content (project-source-file-blob "database.clj")
             :encoding "base64"}
            "http://example.com/web.clj"
            {:content (project-source-file-blob "web.clj")
             :encoding "base64"}})

(defn mocked-fetch-github-resource [_ url _]
  ;; condp invokes predicate with (pred clause x)
  (condp (fn [substr s] (string/includes? s substr)) url
    "/git/trees/master" source-files
    ".clj" (if *slow*
             (do
               (Thread/sleep 500)
               (get blobs url))
             (get blobs url))))

;; One system start for every test
(defn system-fixture [f]
  (let [test-db-file (java.io.File/createTempFile "kondoq-test" ".sqlite")
        config (assoc-in db/config
                         [:kondoq/db :dbname]
                         (.getAbsolutePath test-db-file))
        system (ig/init config)] ; with-redefs is parallel
    (with-redefs [fetch-github-resource mocked-fetch-github-resource
                  *system* system
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

(t/use-fixtures :once system-fixture)
(t/use-fixtures :each database-fixture)

(defn poll-for-occurrences [n]
  (let [occurrences (db/search-occurrences *db* "clojure.core/defn")]
    (if (> (count occurrences) 0)
      true
      (if (= n 0)
        (throw (ex-info "poll-for-occurrences expired" {}))
        (do
          (Thread/sleep 100)
          (recur (dec n)))))))

(defn poll-for-no-occurrences [n]
  (let [occurrences (db/search-occurrences *db* "clojure.core/defn")]
    (if (= (count occurrences) 0)
      true
      (if (= n 0)
        (throw (ex-info "poll-for-*no*-occurrences expired" {}))
        (do
          (Thread/sleep 100)
          (recur (dec n)))))))

(defn poll-for-project-status [location n]
  (let [{:keys [ns-count]} (db/fetch-project-status location)]
    (if (> ns-count 0)
      true
      (if (= n 0)
        (throw (ex-info "poll-for-project-status expired" {}))
        (do
          (Thread/sleep 100)
          (recur location (dec n)))))))

(def project-url "http://example.com/test-owner/test-project")

(deftest test-upsert-project
  (testing "upsert a project"
    (upsert-project *db* nil project-url nil)
    (is (poll-for-occurrences 10) "occurrences should have been added")))

(deftest test-cancel-upsert-project
  (testing "cancel upserting a project"
    (with-redefs [*slow* true] ; project upload should take about 1500ms
      (let [project-future (future (upsert-project *db* nil project-url nil))]
        (db/init-project-status project-url project-future)
        (is (poll-for-project-status project-url 10)
            "some namespaces should have been added before cancelling")

        ;; cancel should trigger rollback because of interrupted exception
        (.cancel project-future true)

        (Thread/sleep 2000) ; wait till transaction is finished and rolled back
        (is (nil? (db/fetch-project-status project-url))
            "project status should have been deleted after cancel")
        (is (poll-for-no-occurrences 10)
            "the entire project should have been removed in the rollback")))))


