(ns kondoq.database-test
  (:require [clojure.test :as t :refer [deftest is]]
            [kondoq.database :as db]
            [kondoq.test-utils :refer [*db*] :as tu]))

(t/use-fixtures :once tu/system-fixture)
(t/use-fixtures :each tu/database-fixture)

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
