(ns kondoq.database-test
  (:require [clojure.test :as t :refer [deftest is]]
            [kondoq.database :as db]
            [kondoq.test-utils :refer [*db*] :as tu]))

(t/use-fixtures :once tu/system-fixture)
(t/use-fixtures :each tu/database-fixture)

(def test-source
  "(ns test-project) (defn foo [] (inc 1))")

(deftest check-database-project-name-limits
  (is (let [[m] (db/insert-project *db* "some-project" "http://example.com")]
        (= 1 (:next.jdbc/update-count m)))
      "normal length project name/location should insert")
  (is (thrown? java.sql.SQLException
               (db/insert-project *db* (apply str (repeat 128 "a")) "http://example.com"))
      "should not allow project names > 127 characters")
  (is (thrown? java.sql.SQLException
               (db/insert-project *db* "some-project" (apply str (repeat 256 "a"))))
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
        _ (db/insert-project *db* "test-project" "http://example.com")
        _ (db/insert-path *db* "test-project" filename "http://example.com/kondoq-test.clj")
        [usage-1 usage-2] (db/search-usages *db* "clojure.core/inc" 1 0 10)]
    (is (= 3 (:start-context usage-1)))
    (is (= "  (inc 1)" (:line usage-1)))
    (is (= context-foo (:context usage-1)))
    (is (= 7 (:start-context usage-2)))
    (is (= "  (inc 2))" (:line usage-2)))
    (is (= context-bar (:context usage-2)))
    (.delete (java.io.File. filename))))

(deftest fetch-usages-with-empty-string
  (let [filename "/tmp/kondoq-test.clj"
        context "(defn foo []\n  (inc 1)\n(dec 2))"
        _ (spit filename (str "(ns test-project)\n\n" context "\n"))
        _ (db/insert-project *db* "test-project" "http://example.com")
        _ (db/insert-path *db* "test-project" filename "http://example.com/kondoq-test.clj")
        {:keys [_ _ usages]} (db/fetch-namespaces-usages *db* "" -1 0 10)]
    (is (empty? usages))
    (.delete (java.io.File. filename))))

