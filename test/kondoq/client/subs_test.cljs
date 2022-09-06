(ns kondoq.client.subs-test
  (:require [cljs.test :as t :include-macros true :refer [deftest is]]
            [kondoq.client.subs :as subs]))

(def add-count-to-last-coll @#'kondoq.client.subs/add-count-to-last-coll)
(def usages-as-rows @#'kondoq.client.subs/usages-as-rows)

(deftest should-add-count-to-last-coll
  (is (= [[1] [[2 3] nil nil]] (add-count-to-last-coll [[1] [2 2 2]]))))

(deftest usages-as-rows-with-without--expansion
  (let [usage-1 {:used-in-ns 'test-ns-1.core :location "test-ns-1.clj#L"}
        usage-2 {:used-in-ns 'test-ns-2.core :location "test-ns-2.clj#L"}
        usages [usage-1 usage-2]
        symbol->namespace {'test-ns-1.core {:project "test-project" :location "test-ns-1.clj"}
                           'test-ns-2.core {:project "test-project" :location "test-ns-2.clj"}}
        ]
    ;; With an expanded project, all namespaces are listed.
    (is (= [[["test-project" 2] ['test-ns-1.core 1] [usage-1 1]]
            [nil                ['test-ns-2.core 1] [usage-2 1]]]
           (usages-as-rows usages #{"test-project"} symbol->namespace)))
    ;; Without an expanded project, only show the first namespace.
    (is (= [[["test-project" 1] ['test-ns-1.core 1] [usage-1 1]]]
           (usages-as-rows usages #{} symbol->namespace)))))


(comment

  (t/run-tests)

  )
