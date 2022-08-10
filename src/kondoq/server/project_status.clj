(ns kondoq.server.project-status
  "Manage temporary project status properties, while a project is being added.
  In memory, works because of single writer."
  (:require [clj-http.client :as client]
            [jsonista.core :as json]
            [lambdaisland.uri :refer [uri assoc-query]]
            [kondoq.server.util :as util]
            [clojure.tools.logging :as log]
            [clojure.string :as string]))

(def current-projects (atom {}))

(defn init-project-status [location project-future]
  (swap! current-projects (fn [m]
                            (assoc m location {:future project-future
                                               :location location
                                               :project ""
                                               :ns-count 0
                                               :ns-total -1}))))

(defn init-project-status-placeholder [location oauth-state]
  (swap! current-projects (fn [m]
                            (assoc m oauth-state {:oauth-state oauth-state
                                                  :location location
                                                  :project ""
                                                  :ns-count 0
                                                  :ns-total -1}))))

(defn update-project-status [location project ns-count ns-total current-file]
  (swap! current-projects (fn [m]
                            (update m location
                                    (fn [p]
                                      (merge p {:location location
                                                :project project
                                                :ns-count ns-count
                                                :ns-total ns-total
                                                :current-file current-file}))))))

(defn update-project-with-error [location error]
  (swap! current-projects (fn [m]
                            (update m location
                                    (fn [p]
                                      (merge p {:location location
                                                :error error}))))))

(defn fetch-project-status [location-or-state]
  (get @current-projects location-or-state))

(defn delete-project-status [location-or-state]
  (swap! current-projects (fn [m] (dissoc m location-or-state))))

(comment
  (github-authorize-url ["some-state"])

  (def state (util/random-string 15))
  (init-project-status-placeholder "http://example.com/some-project" state)
  (def url (github-authorize-url state))
  (def code "6a34a039bdca3f7e1f5d")
  (github-exchange-token state code)

  (json/read-value "{\"access_token\":\"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\",\"token_type\":\"bearer\",\"scope\":\"\"}" (json/object-mapper {:decode-key-fn true}))

  (System/setProperty "config" "github-oauth-dev.edn")
  (read-config)
  
  )
