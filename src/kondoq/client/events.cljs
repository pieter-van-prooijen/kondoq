(ns kondoq.client.events
  (:require [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.uri.utils]
            [kondoq.client.db :as db]
            [kondoq.client.project-events :as project-events]
            [re-frame.core :as re-frame]))

(re-frame/reg-event-fx
 ::initialize-db
 (fn-traced [_ _]
            {:db db/initial-db
             :fx [[:dispatch [::project-events/fetch-projects]]]}))

(re-frame/reg-event-fx
 ::initialize-with-adding-project
 (fn-traced [_ [_ project-location]]
            {:db (merge
                  db/initial-db
                  {:active-panel :projects
                   :projects-state :adding-project
                   :current-project {:location project-location}})
             :fx [[:dispatch [::project-events/fetch-project]]]}))

(re-frame/reg-event-db
 ::clear-http-failure
 (fn-traced [db _]
            (dissoc db :http-failure)))

(re-frame/reg-event-db
 ::register-http-failure
 (fn-traced [db [_ http-failure]]
            (assoc db :http-failure http-failure)))


(re-frame/reg-event-db
 ::set-active-panel
 (fn-traced [db [_ [panel]]]
            (assoc db :active-panel panel)))

(comment
  (expand-ancestors :a {:a :b} #{:a})
  (js/encodeURIComponent "http://bla")

  )
