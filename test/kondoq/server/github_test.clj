(ns kondoq.server.github-test
  (:require [clj-http.client]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is]:as t]
            [jsonista.core :as json]
            [kondoq.server.database :as db]
            [kondoq.server.github :refer [upsert-project]]
            [kondoq.server.project-status :as project-status]
            [kondoq.server.test-utils :refer [*db* *etag-db*] :as tu])
  (:import java.util.Base64))

(def ^:dynamic *slow* false)

;; Simulate retrieving a base64 encoded source blob from this project's sources.
(defn project-source-file-blob [source-file]
  (->> (str "src/kondoq/server/" source-file)
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

;; Mock the http calls to GitHub.
(defn mocked-http-get [url _]
  (let [body (condp #(string/includes? %2 %1) url
               "/git/trees/master" source-files
               ".clj" (if *slow*
                        (do
                          (Thread/sleep 500)
                          (get blobs url))
                        (get blobs url)))]
    {:headers {"Etag" url} ; Etag is not really used for now
     :status 200
     :body (json/write-value-as-string body)}))

(defn system-fixture [f]
  (with-redefs [clj-http.client/get mocked-http-get]
    (f)))

(t/use-fixtures :once tu/system-fixture system-fixture)
(t/use-fixtures :each tu/database-fixture)

(defn poll-for-usages [n]
  (let [usages (db/search-usages *db* "clojure.core/defn" -1 0 10)]
    (if (> (count usages) 0)
      true
      (if (= n 0)
        (throw (ex-info "poll-for-usages expired" {}))
        (do
          (Thread/sleep 100)
          (recur (dec n)))))))

(defn poll-for-no-usages [n]
  (let [usages (db/search-usages *db* "clojure.core/defn" -1 0 10)]
    (if (= (count usages) 0)
      true
      (if (= n 0)
        (throw (ex-info "poll-for-*no*-usages expired" {}))
        (do
          (Thread/sleep 100)
          (recur (dec n)))))))

(defn poll-for-project-status [location n]
  (let [{:keys [ns-count]} (project-status/fetch-project-status location)]
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
    (upsert-project *db* *etag-db* project-url nil)
    (is (poll-for-usages 10) "Usages should have been added.")))

(deftest test-cancel-upsert-project
  (testing "Cancel upserting a project."
    (with-redefs [*slow* true] ; Project upload should take about 1500ms.
      (let [project-future (future (upsert-project *db* *etag-db* project-url nil))]
        (project-status/init-project-status project-url project-future)
        (is (poll-for-project-status project-url 10)
            "Some namespaces should have been added before cancelling.")

        ;; cancel should trigger rollback because of an 'interrupted' exception
        (.cancel project-future true)

        (Thread/sleep 2000) ; wait till transaction is finished and rolled back
        (is (nil? (project-status/fetch-project-status project-url))
            "Project status should have been deleted after cancel.")
        (is (poll-for-no-usages 10)
            "The entire project should have been removed in the rollback.")))))


