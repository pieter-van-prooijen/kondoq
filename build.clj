;; build the uberjar using 'clj -T:build uber'
;; invoke with 'java -jar target/kondoq-<version>-standalone.jar'
;;
(ns build
  (:require [clojure.string :as string]
            [clojure.tools.build.api :as b]))

(def lib 'kondoq)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"})
  ;; remove any shadow-cljs dev build code
  (b/delete {:path "resources/public/js/compiled"}))

(defn working-tree-clean? []
  (-> (b/git-process {:git-args "diff --stat HEAD"})
      (string/split #"\n")
      count
      zero?))

(defn uber [{:keys [check-working-tree] :or {check-working-tree true}}]
  (clean nil)

  (when (and check-working-tree (not (working-tree-clean?)))
    (println "working tree not clean, aborting, skip this check with 'check-working-tree false' as arguments")
    (System/exit 1))

  (b/process {:command-args ["npx" "shadow-cljs" "release" "app"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'kondoq.server
           :manifest {"Creation-Date" (str (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC))
                      "Implementation-Version" version
                      "Git-Commit" (b/git-process {:git-args "rev-parse HEAD"})}}))
