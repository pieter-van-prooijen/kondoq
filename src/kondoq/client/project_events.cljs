(ns kondoq.client.project-events
  (:require [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.uri.utils]
            [re-frame.core :as re-frame]))

;; Adding a project has the following states:
;; - showing-projects
;; - entering-project-url (and a token)
;; - adding-project (with feedback about the loaded namespaces etc.)
;; - error-adding-project (something happened when fetching)

;; Fetch a list of all the projects and some application info.
(re-frame/reg-event-fx
 ::fetch-projects
 (fn-traced [_ [_ _]]
            {:fx [[:http {:url "/projects"
                          :on-success ::process-fetch-projects}]]}))

(re-frame/reg-event-db
 ::process-fetch-projects
 (fn-traced [db [_ response]]
            (let [{:keys [projects manifest config-path]} response]
              (assoc db :projects projects
                     :manifest manifest
                     :config-path config-path))))

;; Generic cancel event, dispatches to the correct one depending on the state.
(re-frame/reg-event-fx
 ::cancel-projects
 (fn-traced [{{projects-state :projects-state} :db} [_ _]]
            {:fx [[:dispatch
                   (condp = projects-state
                     :entering-project-url [::cancel-enter-project-url]
                     :adding-project [::cancel-add-project]
                     :error-adding-project [::cancel-add-project-with-error])]]}))

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
            {:db (merge db {:projects-state :adding-project
                            :current-project {:location location}})
             :fx [[:http {:url (goog.uri.utils/appendPath "projects"
                                                          (js/encodeURIComponent location))
                          :method "PUT"
                          :body (str "token=" (js/encodeURIComponent token))
                          :headers {:content-type "application/x-www-form-urlencoded"}
                          :on-success ::added-project-result}]]}))

(re-frame/reg-event-fx
 ::added-project-result
 (fn-traced [_ [_ response]]
            (if-let [location (get response :location)]
              {:fx [[:redirect location]]} ; Oauth redirect.
              {:fx [[:dispatch [::fetch-project]]]})))  ; Regular upload.

(re-frame/reg-event-fx
 ::cancel-add-project
 (fn-traced [{:keys [db]} [_ _]]
            {:fx [[:http (let [location (get-in db [:current-project :location])]
                           {:url (-> "/projects"
                                     (goog.uri.utils/appendPath (js/encodeURIComponent location))
                                     (goog.uri.utils/appendPath "/adding"))
                            :method "DELETE"
                            :on-success ::fetch-project})]]}))

(re-frame/reg-event-fx
 ::cancel-add-project-with-error
 (fn-traced [{:keys [db]} [_ _]]
            {:db (-> db
                     (assoc :projects-state :showing-projects)
                     (dissoc :current-project))
             :fx [[:dispatch [::fetch-projects]]]}))

(re-frame/reg-event-fx
 ::fetch-project
 (fn-traced [{:keys [db]} [_ _]]
            {:fx [[:http (let [location (get-in db [:current-project :location])]
                           {:url (goog.uri.utils/appendPath "/projects"
                                                            (js/encodeURIComponent location))
                            :on-success ::process-project})]]}))

;; Answer the effect map for a given state of the project.
(defn process-project [db {:keys [ns-count ns-total error] :as project}]
  (cond
    ;; An error happened, user should cancel the upload after reading.
    error
    {:db (assoc db :projects-state :error-adding-project
                :current-project project)}
    ;; Finished uploading.
    (or (empty? project) (= ns-total ns-count))
    {:db (-> db
             (assoc :projects-state :showing-projects)
             (dissoc :current-project))
     :fx [[:dispatch [::fetch-projects]]]}
    :else
    {:db (assoc db
                :projects-state :adding-project
                :current-project project)
     :fx [[:dispatch-later {:ms 500 :dispatch [::fetch-project]}]]}))

(re-frame/reg-event-fx
 ::process-project
 (fn-traced [{:keys [db]} [_ response]]
            (process-project db response)))


(re-frame/reg-event-fx
 ::delete-project
 (fn-traced [{:keys [_]} [_ location]]
            {:fx [[:http {:url (-> "projects"
                                   (goog.uri.utils/appendPath (js/encodeURIComponent location)))
                          :method "DELETE"
                          :on-success [::fetch-projects]}]]}))

(comment
  (js/encodeURIComponent "https://github.com/pieter-van-prooijen/kafka-streams-clojure"))
