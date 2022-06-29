(ns kondoq.project-events
  (:require [cljs.reader :refer [read-string]]
            [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.uri.utils]
            [kondoq.events :as events]
            [re-frame.core :as re-frame]))

(re-frame/reg-event-db
 ::show-enter-project-url
 (fn-traced [db [_ _]]
            (assoc db :projects-state :entering-project-url)))

(re-frame/reg-event-db
 ::cancel-enter-project-url
 (fn-traced [db [_ _]]
            (if (= (:projects-state db) :entering-project-url)
              (assoc db :projects-state :showing-projects)
              db)))

(re-frame/reg-event-fx
 ::add-project
 (fn-traced [{:keys [db]} [_ [location token]]]
            {:http [(-> "projects"
                        (goog.uri.utils/appendPath (js/encodeURIComponent location))
                        (goog.uri.utils/appendParam "token" token))
                    ::fetch-project
                    "PUT"]
             :db (merge db {:projects-state :adding-project
                            :current-project {:location location}})}))

(re-frame/reg-event-fx
 ::fetch-project
 (fn-traced [{:keys [db]} [_ _]]
            {:http (let [location (get-in db [:current-project :location])]
                     [(goog.uri.utils/appendPath "/projects"
                                                 (js/encodeURIComponent location))
                      ::process-project
                      "GET"])}))

(re-frame/reg-event-fx
 ::cancel-add-project
 (fn-traced [{:keys [db]} [_ _]]
            {:http (let [location (get-in db [:current-project :location])]
                     [(goog.uri.utils/appendPath "/projects"
                                                 (js/encodeURIComponent location))
                      ::fetch-project
                      "DELETE"])}))

;; answer the effect map for a given state of the project
(defn process-project [db {:keys [ns-count ns-total] :as project}]
  (if (or (empty? project) (= ns-total ns-count))
    {:db (assoc db :projects-state :showing-projects
                :current-project {})
     :dispatch [::events/fetch-namespaces-occurrences ""]} ; reload projects
    {:db (assoc db :projects-state :adding-project
                :current-project project)
     :dispatch-later {:ms 500 :dispatch [::fetch-project]}}))

(re-frame/reg-event-fx
 ::process-project
 (fn-traced [{:keys [db]} [_ response-body]]
            (process-project db (read-string response-body))))


(comment
  (js/encodeURIComponent "https://github.com/pieter-van-prooijen/kafka-streams-clojure"))
