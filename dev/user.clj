(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [kondoq.server.core]
            [shadow.cljs.devtools.server]))

(integrant.repl/set-prep! (constantly kondoq.server.core/config))

(comment

  ;; integrant backend system start/stop
  (go)
  (halt)
  (reset) ; gives "address already in use" exception but still works?
  (reset-all)

  (:adapter/jetty integrant.repl.state/system)
  ;; start the shadow cljs build, connect using cider-connect-sibling-cljs (C-c C-x s s) to
  ;; get proper repl context switching between clj and cljs
  ;; see .dir-locals.el and the dev dependencies how to make this work.
  (shadow.cljs.devtools.server/start!)
  (shadow.cljs.devtools.server/stop!)


  ,)
