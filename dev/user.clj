(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [kondoq.server.core]))

;; Use the integrant system config defined in kondoq.server.core
(integrant.repl/set-prep! (constantly kondoq.server.core/config))

(comment

  ;; Integrant backend system start/stop
  (go)
  (halt)

  (reset) ; stop/reload namespaces/start
  (reset-all) ; stop/reload all namespaces/start

  ,)
