(ns kondoq.client.db
  (:refer-clojure :exclude [namespace])
  (:require [cljs.pprint]
            [clojure.string :as string]
            [malli.core :as m]
            [malli.error :as me]
            [re-frame.core :as re-frame]))

(def not-blank (m/-simple-schema {:type :db/not-blank
                                  :pred #(and (string? %) (not (string/blank? %)))
                                  :type-properties {:error-message "should not be a blank string."}}))

(def var-usage
  (m/schema [:map {:closed true}
             [:symbol :qualified-symbol]
             [:arity [:maybe :int]]
             [:used-in-ns :symbol]
             [:line-no :int]
             [:column-no :int]
             [:line :not-blank]
             [:context {:optional true} :not-blank]
             [:start-context :int]
             [:end-context :int]]
            {:registry (merge (m/default-schemas)
                              {:not-blank not-blank})}))

(def namespace
  (m/schema [:map {:closed true}
             [:ns :symbol]
             [:project :not-blank]
             [:location :not-blank]]
            {:registry (merge (m/default-schemas)
                              {:not-blank not-blank})}))

(def project
  (m/schema [:map {:closed true}
             [:project {:optional true} :not-blank]
             [:location :not-blank]
             [:ns-count {:optional true} :int]
             [:ns-total {:optional true} :int]
             [:current-file {:optional true} :not-blank]
             [:error {:optional true} :not-blank]]
            {:registry (merge (m/default-schemas)
                              {:not-blank not-blank})}))

(def symbol-count
  (m/schema [:map {:closed true}
             [:symbol :qualified-symbol]
             [:arity [:maybe :int]] ; nil is no arity, -1 is all arities.
             [:count :int]
             [:all-arities-count :int]]))

(def db
  (m/schema [:map {:closed true}
             [:active-panel [:enum :search :projects]]
             ;;
             ;; Search panel state.
             ;;
             ;; Search results (current page).
             [:usages [:sequential :var-usage]]
             [:usages-count :int]
             [:namespaces [:sequential :namespace]]
             [:expanded [:set [:or :not-blank :symbol :qualified-symbol]]]
             [:symbol {:optional true} :qualified-symbol]
             [:arity {:optional true} [:maybe :int]]
             [:pagination {:optional true} [:map {:closed true}
                                            [:page :int] ; zero based
                                            [:page-size :int]]]
             ;; Type-ahead results.
             [:symbol-counts-q {:optional true} :string]
             [:symbol-counts [:sequential :symbol-count]]
             [:symbol-counts-request-no {:optional true} :int]
             ;; Backend information.
             [:manifest [:maybe [:map]]]
             [:config-path :not-blank]
             ;;
             ;; Projects panel state.
             ;;
             [:projects [:sequential :project]]
             [:current-project {:optional true} :project]
             [:projects-state [:enum
                               :showing-projects
                               :entering-project-url
                               :adding-project
                               :error-adding-project]]
             [:http-failure {:optional true} :map]
             [:validation {:optional true} [:map {:closed true}
                                            [:event [:sequential :any]]
                                            [:error :map]]]]
            {:registry (merge (m/default-schemas)
                              {:not-blank not-blank
                               :var-usage var-usage
                               :namespace namespace
                               :project project
                               :symbol-count symbol-count})}))

(def initial-db
  "Initial database before fetching information from the backend."
  {:active-panel :search
   :usages []
   :usages-count 0
   :namespaces []
   :expanded #{}
   :symbol-counts []
   :projects []
   :projects-state :showing-projects
   :manifest {}
   :config-path "UNKNOWN"})

(defn validate
  "Validate `app-db` and put any errors and the event which caused them
  under the :validation key.
  Don't overwrite previous validation errors."
  [event app-db]
  (if-let [error (-> (m/explain db app-db)
                     me/humanize)]
    (let [validation {:event event :error error}]
      (.error js/console (str "error in db: " (with-out-str (cljs.pprint/pprint validation))))
      (if-not (contains? app-db :validation)
        (assoc app-db :validation validation)
        app-db))
    app-db))

(def app-db-validator
  (re-frame/->interceptor
   :id :app-db-validator
   :after (fn [context]
            ;; App database is sometimes nil, don't validate.
            (if-let [app-db (re-frame/get-effect context :db)]
              (re-frame/assoc-effect context
                                     :db
                                     (validate (re-frame/get-coeffect context :event) app-db))
              context))))

(comment
  (m/schema? (m/schema not-blank))
  (m/validate db initial-db)
  (-> (m/explain db initial-db)
      (me/humanize))

  (validate :test initial-db)
  (validate :test (dissoc initial-db :active-panel))
  )

