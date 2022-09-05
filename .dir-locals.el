;; -*-Lisp-*-
((nil
  (cider-default-cljs-repl . shadow)
  (cider-shadow-default-options . "app")
  (cider-shadow-watched-builds . ("app"))

  ;; It's a list in the docs, but gives an error if not a string?
  (cider-clojure-cli-aliases . "dev")

  ;; Merge different nrepl sessions (clj and cljs) automatically based on
  ;; the host, so starting a separate cider-cljs jack-in works with repl
  ;; switching per source file. Needs cider 1.5.0+
  ;; https://docs.cider.mx/cider/usage/managing_connections.html#adding-repls-to-a-session
  (cider-merge-sessions . project)))

