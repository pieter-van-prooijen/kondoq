(ns kondoq.db)

(def default-db
  ;; occurrence of a var
  ;; sorted by project, namespace, line-no
  {:occurrences [{:symbol 'clojure.core/inc :ns 're-frame.core :line-no 42 :line "(inc x)" :context "(defn bla [x]\n  (inc x))"}
                 {:symbol 'clojure.core/inc :ns 're-frame.core :line-no 43 :line "(inc y)"}
                 {:symbol 'clojure.core/inc :ns 're-frame.util :line-no 10 :line "(inc z)"}
                 {:symbol 'clojure.core/inc :ns 'cheshire.core :line-no 10 :line "(inc zz)"}]

   ;; namespaces in a project, assumes namespaces are unique which might not be true
   ;; prefix with project?
   ;; location is the source file of the namespace
   :namespaces [{:ns 're-frame.core :project "re-frame" :location "https://..."}
                {:ns 're-frame.util :project "re-frame" :location "https://..."}
                {:ns 'cheshire.core :project "cheshire" :location "https://..."}]

   :projects [{:project "re-frame"
               :location "https://..."}]

   ;; expanded projects/namespaces in the search result table
   :expanded #{}

   ;; panel state
   :active-panel :search

   ;; occurrence search type-ahead state
   :symbol ""  ; fq symbol currently displayed
   :symbol-counts [] ; auto-completion list
   :symbol-counts-q "" ; current search string typed into form

   ;; projects tab state
   :projects-state :showing-projects
   :current-project {}
   })



