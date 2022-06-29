(ns kondoq.main
  (:require [integrant.core :as ig]
            [kondoq.database :as db]))

;; add the specified source file paths of the project to the database
;; invoke with 'clj -M -m kondoq.main project-name path... '
(defn -main [project & paths]
  (let [system (ig/init (merge db/config))
        db (:kondoq/db system)]
    (doseq [path paths]
      (println "adding " path)
      (db/insert-path db project path (str "file:" path)))
    (ig/halt! system)))

(comment

  (-main "kondoq" "src/kondoq/views.cljs")
  )
