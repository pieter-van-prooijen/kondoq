(ns kondoq.server.database-test
  (:require [clojure.test :as t :refer [deftest is]]
            [honey.sql :as sql]
            [kondoq.server.analysis :refer [analyze]]
            [kondoq.server.database :as db]
            [kondoq.server.test-utils :refer [*db*] :as tu]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as jdbc-sql]))

(t/use-fixtures :once tu/system-fixture)
(t/use-fixtures :each tu/database-fixture)

(def test-source
  "(ns test-project) (defn foo [] (inc 1))")

(defn- table-empty? [db table]
  (let [{count :count} (jdbc/execute-one! db [(str "select count(*) as count from " table)])]
    (zero? count)))

(deftest check-database-project-name-limits
  (is (let [[m] (db/insert-project *db* "some-project" "http://example.com" 42)]
        (= 1 (:next.jdbc/update-count m)))
      "normal length project name/location should insert")
  (is (thrown? java.sql.SQLException
               (db/insert-project *db* (apply str (repeat 128 "a")) "http://example.com" 42))
      "should not allow project names > 127 characters")
  (is (thrown? java.sql.SQLException
               (db/insert-project *db* "some-project" (apply str (repeat 256 "a")) 42))
      "should not allow project locations > 255 characters"))

(deftest source-lines-extraction
  (let [filename "/tmp/kondoq-test.clj"
        context-foo "(defn foo []\n  (inc 1)\n(dec 2))"
        context-bar " (defn bar []\n  (inc 2))"
        _ (spit filename (str "(ns test-project)\n\n"
                              context-foo
                              "\n\n"
                              context-bar
                              "\n"))
        _ (db/insert-project *db* "test-project" "http://example.com" 42)
        analysis (analyze filename)
        _ (db/insert-namespace *db* analysis "test-project"
                               "http://example.com/kondoq-test.clj")
        [usage-1 usage-2] (db/search-usages *db* "clojure.core/inc" 1 0 10)]
    (is (= 3 (:start-context usage-1)))
    (is (= "  (inc 1)" (:line usage-1)))
    (is (= context-foo (:context usage-1)))
    (is (= 7 (:start-context usage-2)))
    (is (= "  (inc 2))" (:line usage-2)))
    (is (= context-bar (:context usage-2)))
    (.delete (java.io.File. filename))))

;; Returns the filename to be deleted by the test itself.
(defn- insert-test-project
  ([]
   (insert-test-project 42))
  ([nof-stars]
   (let [filename "/tmp/kondoq-test.clj"
         context "(defn foo []\n  (inc 1)\n(dec 2))"]
     (spit filename (str "(ns test-project)\n\n" context "\n"))
     (db/insert-project *db* "test-project" "http://example.com" nof-stars)
     (db/insert-namespace *db* (analyze filename) "test-project"
                          "http://example.com/kondoq-test.clj")
     filename)))

(deftest fetch-usages-with-empty-string
  (let [filename (insert-test-project)
        {:keys [_ _ usages]} (db/search-namespaces-usages *db* "" -1 0 10)]
    (is (empty? usages))
    (.delete (java.io.File. filename))))

;; Doesn't work yet, now way to specify "escape" after a like clause in honeysql.
(deftest escape-like-pattern-characters
  (let [filename (insert-test-project)
        symbol-counts (db/search-symbol-counts *db* "%dec%" 10)
        _ (is (= 1 (count symbol-counts)))
        symbol-counts (db/search-symbol-counts *db* "%d%ec%" 10)
        _ (is (= 1 (count symbol-counts)))] ;; should be zero matches
    (.delete (java.io.File. filename))))

(deftest should-recognize-test-namespaces
  (let [filename "/tmp/kondoq-test.clj"
        context-foo "(defn foo []\n  (inc 1)\n(dec 2))"
        _ (spit filename (str "(ns kondoq-test)\n\n"
                              context-foo))
        _ (db/insert-project *db* "test-project" "http://example.com" 42)
        analysis (analyze filename)
        _ (db/insert-namespace *db* analysis "test-project"
                               "http://example.com/kondoq-test.clj")
        [{:keys [:ns :test]}] (jdbc-sql/query *db* (sql/format {:select [:ns :test]
                                                                :from [:namespaces]}))]
    (is (:test-namespace analysis))
    (is (= 1 test)) ; Sqlite stores booleans as 0 or 1.
    (is test)
    (is (= "kondoq-test" ns))
    (.delete (java.io.File. filename))))

(deftest should-handle-same-name-clj-cljs-namespaces
  (let [filename-clj "/tmp/kondoq-test.clj"
        context-foo "(defn foo []\n  (inc 1)\n(dec 2))"
        _ (spit filename-clj (str "(ns kondoq-test)\n\n"
                                  context-foo))
        _ (db/insert-project *db* "test-project" "http://example.com" 42)
        analysis-clj (analyze filename-clj)
        _ (db/insert-namespace *db* analysis-clj "test-project"
                               "http://example.com/kondoq-test.clj")

        filename-cljs "/tmp/kondoq-test.cljs"
        _ (spit filename-cljs (str "(ns kondoq-test)\n\n"
                                   context-foo))
        analysis-cljs (analyze filename-cljs)
        _ (db/insert-namespace *db* analysis-cljs "test-project"
                               "http://example.com/kondoq-test.cljs")

        namespaces (jdbc-sql/query *db* (sql/format {:select [:ns :language]
                                                     :from [:namespaces]}))]

    (let [{:keys [ns language]} (first namespaces)]
      (is (= "kondoq-test" ns))
      (is (= "clj" language)))
    (let [{:keys [ns language]} (second namespaces)]
      (is (= "kondoq-test" ns))
      (is (= "cljs" language)))

    (.delete (java.io.File. filename-clj))
    (.delete (java.io.File. filename-cljs))))

;; Check if the "cascade on delete.." constraints work correctly.
(deftest should-remove-namespaces-usages-context-when-project-is-removed
  (let [filename (insert-test-project)
        tables ["projects" "namespaces" "var_usages" "contexts"]]
    (is (every? #(not (table-empty? *db* %)) tables))
    (db/delete-project-by-location *db* "http://example.com")
    (is (every? #(table-empty? *db* %) tables))
    (.delete (java.io.File. filename))))

(deftest should-see-if-schema-exists
  (is (db/schema-exists *db*)))

(deftest should-return-nof-stars
  (insert-test-project 1234)
  (let [[{:keys [nof-stars]}]  (db/search-projects *db*)]
    (is (= 1234 nof-stars))))
