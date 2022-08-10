(ns kondoq.client.project-events
  (:require [cljs.reader :refer [read-string]]
            [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.uri.utils]
            [re-frame.core :as re-frame]))

;; Adding project states:
;; - showing-projects
;; - entering-project-url (and a token)
;; - adding-project (with feedback about the loaded namespaces etc.)
;; - error-adding-project (something happened when fetching)
;;

;; Fetch a list of all the projects and some application info
(re-frame/reg-event-fx
 ::fetch-projects
 (fn-traced [_ [_ _]]
            {:http ["/projects"
                    ::process-fetch-projects]}))

(re-frame/reg-event-db
 ::process-fetch-projects
 (fn-traced [db [_ response-body]]
            (let [{:keys [projects manifest config-path]}
                  (read-string response-body)]
              (-> db
                  (assoc :projects projects)
                  (assoc :manifest manifest)
                  (assoc :config-path config-path)))))

;; Generic cancel event, dispatches to the correct one depending on the state
(re-frame/reg-event-fx
 ::cancel-projects
 (fn-traced [{{projects-state :projects-state} :db} [_ _]]
            {:dispatch
             (condp = projects-state
               :entering-project-url [::cancel-enter-project-url]
               :adding-project [::cancel-add-project]
               :error-adding-project [::cancel-add-project-with-error])}))

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
            {:http [(goog.uri.utils/appendPath "projects"
                                               (js/encodeURIComponent location))
                    ::added-project-result
                    "PUT"
                    (str "token=" (js/encodeURIComponent token))
                    {:content-type "application/x-www-form-urlencoded"}]
             :db (merge db {:projects-state :adding-project
                            :current-project {:location location}})}))

(re-frame/reg-event-fx
 ::added-project-result
 (fn-traced [_ [_ response-text]]
            (if-let [location (get (read-string  response-text) :location)]
              (set! (. js/window -location) location) ; Oauth redirect
              {:dispatch [::fetch-project]})))  ; Regular upload

(re-frame/reg-event-fx
 ::cancel-add-project
 (fn-traced [{:keys [db]} [_ _]]
            {:http (let [location (get-in db [:current-project :location])]
                     [(-> "/projects"
                          (goog.uri.utils/appendPath (js/encodeURIComponent location))
                          (goog.uri.utils/appendPath "/adding"))
                      ::fetch-project
                      "DELETE"
                      _
                      _
                      ::cancel-add-project])}))

(re-frame/reg-event-fx
 ::cancel-add-project-with-error
 (fn-traced [{:keys [db]} [_ _]]
            {:db (assoc db :projects-state :showing-projects
                        :current-project {})
             :dispatch [::fetch-projects]}))

(re-frame/reg-event-fx
 ::fetch-project
 (fn-traced [{:keys [db]} [_ _]]
            {:http (let [location (get-in db [:current-project :location])]
                     [(goog.uri.utils/appendPath "/projects"
                                                 (js/encodeURIComponent location))
                      ::process-project
                      "GET"])}))

;; answer the effect map for a given state of the project
(defn process-project [db {:keys [ns-count ns-total error] :as project}]
  (cond
    ;; an error happened, user should cancel the upload after reading
    error
    {:db (assoc db :projects-state :error-adding-project
                :current-project project)}
    ;; finished uploading
    (or (empty? project) (= ns-total ns-count))
    {:db (assoc db :projects-state :showing-projects
                :current-project {})
     :dispatch [::fetch-projects]}
    :else
    {:db (assoc db :projects-state :adding-project
                :current-project project)
     :dispatch-later {:ms 500 :dispatch [::fetch-project]}}))

(re-frame/reg-event-fx
 ::process-project
 (fn-traced [{:keys [db]} [_ response-body]]
            (process-project db (read-string response-body))))


(re-frame/reg-event-fx
 ::delete-project
 (fn-traced [{:keys [_]} [_ location]]
            {:http [(-> "projects"
                        (goog.uri.utils/appendPath (js/encodeURIComponent location)))
                    [::fetch-projects]
                    "DELETE"]}))

(comment
  (coll? [:a])
  (js/encodeURIComponent "https://github.com/pieter-van-prooijen/kafka-streams-clojure"))
