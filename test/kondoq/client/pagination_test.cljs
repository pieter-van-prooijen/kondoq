(ns kondoq.client.pagination-test
  (:require [cljs.test :as t :include-macros true]
            [kondoq.client.pagination :refer [build-page-nums]]))

(t/deftest build-pagenums-test
  (t/are [x y] (= x y)
    (build-page-nums 1 0) '(0)
    (build-page-nums 2 0) '(0 1)
    (build-page-nums 3 0) '(0 1 2)
    (build-page-nums 4 0) '(0 1 -1 3)

    (build-page-nums 2 1) '(0 1)
    (build-page-nums 3 2) '(0 1 2)
    (build-page-nums 4 3) '(0 -1 2 3)

    (build-page-nums 5 2) '(0 1 2 3 4)
    (build-page-nums 5 3) '(0 -1 2 3 4)

    (build-page-nums 6 3) '(0 -1 2 3 4 5)
    (build-page-nums 6 4) '(0 -1 3 4 5)
    (build-page-nums 7 3) '(0 -1 2 3 4 -1 6)

    (build-page-nums 7 3 2) '(0 1 2 3 4 5 6)))


(comment

  (build-pagenums-test)
  ;; output is printed in the repl window, no reporting in cider
  (t/run-tests)
  )

