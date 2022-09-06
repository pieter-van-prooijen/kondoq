(ns kondoq.client.db-test
  (:require [cljs.test :refer [deftest is run-tests] :include-macros true]
            [kondoq.client.db :as db]
            [malli.core :as m]))

(deftest initial-db
  (is (m/validate db/db db/initial-db)))

(comment

  ;; output is printed in the repl window, no reporting in cider
  (run-tests)

  )
