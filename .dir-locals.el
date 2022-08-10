;; -*-Lisp-*-
((nil
  (cider-default-cljs-repl . shadow)
  (cider-shadow-default-options . "app")
  (cider-shadow-watched-builds . '("app"))
  ;; risky var, mark this as safe to prevent warnings
  (cider-jack-in-nrepl-middlewares . ("refactor-nrepl.middleware/wrap-refactor"
                                      "cider.nrepl/cider-middleware"
                                      "shadow.cljs.devtools.server.nrepl/middleware"))))

