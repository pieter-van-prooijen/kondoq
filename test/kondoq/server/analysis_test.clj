(ns kondoq.server.analysis-test
  (:require [clojure.test :as t :refer [deftest is]]
            [kondoq.server.analysis :refer [analyze]]))

(def valid-var-usage? @#'kondoq.server.analysis/valid-var-usage?)
(def usage-context @#'kondoq.server.analysis/usage-context)

(deftest valid-var-usage
  (is (valid-var-usage? {:to 'to-ns
                         :from 'from-ns
                         :row 42
                         :end-row 44
                         :col 43
                         :name 'foo}))
  (is (not (valid-var-usage? {:from 'from-ns
                              :row 42
                              :col 43
                              :name 'foo}))))

(deftest usage-context-selection
  (is (= [0 10] (usage-context 3 [[4 24] [5 10] [0 10]]))
      "Should pick the largest context that matches. Contexts are inclusive.")
  (is (nil? (usage-context 4 [[2 3] [5 6]]))
      "Should return nil if no context matches"))

(deftest cljc-usage
  (let [filename "/tmp/kondoq-analysis-test.cljc"
        _ (spit filename "(ns kondoq-analysis-test)\n\n(defn foo []\n  (inc 1))")
        {:keys [usages]} (analyze filename)]
    (is (= 1 (count (filter #(= (:symbol %) 'clojure.core/inc) usages)))
        "Report a usage in the clj context.")
    (is (= 1 (count (filter #(= (:symbol %) 'cljs.core/inc) usages)))
        "Report a usage in the cljs context.")
    (.delete (java.io.File. filename))))

(deftest javascript-var-usage
  (let [filename "/tmp/kondoq-analysis-test.cljs"
        _ (spit filename "(ns kondoq-analysis-test)\n\n(defn foo []\n  (js/alert \"js var usage\"))")
        {:keys [usages]} (analyze filename)]
    (is (= 1 (count usages)))
    (is (= 'cljs.core/defn (-> usages first :symbol))
        "Should ignore javascript var usages.")
    (.delete (java.io.File. filename))))

