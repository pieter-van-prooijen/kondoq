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

;; When clicking on an item, automatically expand all its items to the left.
(defn expand-ancestors
  "Put all ancestors of item `k` in `expanded` using the `child->parent` map to
   find the parent."
  [k child->parent expanded]
  (if-let [parent (child->parent k)]
    (into (conj expanded parent) (expand-ancestors parent child->parent expanded))
    expanded))

(re-frame/reg-event-db
 ::toggle-expanded
 (fn-traced [db [_ [x child->parent]]]
            (update db :expanded (fn [expanded]
                                   (if (expanded x)
                                     (disj expanded x)
                                     (->> (conj expanded x)
                                          (expand-ancestors x child->parent)))))))

(re-frame/reg-event-db
 ::clear-http-failure
 (fn-traced [db _]
            (dissoc db :http-failure)))

(re-frame/reg-event-db
 ::register-http-failure
 (fn-traced [db [_ http-failure]]
            (assoc db :http-failure http-failure)))

(re-frame/reg-event-fx
 ::fetch-namespaces-usages
 (fn-traced [{:keys [db]} [_ [fq-symbol-name arity page page-size]]]
            {:fx [[:http {:url (-> "/usages"
                                   (goog.uri.utils/appendParam "fq-symbol-name"
                                                               fq-symbol-name)
                                   (goog.uri.utils/appendParam "arity"
                                                               arity)
                                   (goog.uri.utils/appendParam "page"
                                                               page)
                                   (goog.uri.utils/appendParam "page-size"
                                                               page-size))
                          :on-success ::process-fetch-namespaces-usages}]]
             :db (-> db
                     (assoc :symbol (symbol fq-symbol-name))
                     (assoc :arity arity))}))

(re-frame/reg-event-db
 ::process-fetch-namespaces-usages
 (fn-traced [db [_ response]]
            (let [{:keys [namespaces usages usages-count page page-size]}
                  response]
              (-> db
                  (assoc :namespaces namespaces)
                  (assoc :usages usages)
                  (assoc :usages-count usages-count)
                  (assoc-in [:pagination :page] page)
                  (assoc-in [:pagination :page-size] page-size)
                  (assoc :symbol-counts [])
                  ;; When paging, all namespaces and projects are expanded.
                  (assoc :expanded (-> #{}
                                       (into (map :project namespaces))
                                       (into (map :ns namespaces))))))))

;; When fetching the symbol counts, keep track of the request number
;; (which is echoed by the backend), so slow responses for early requests
;; (e.g. when typing the first two letters, which take more time to search)
;; won't overwrite faster (earlier) responses
;; to later requests (when typing three or more letters).
(re-frame/reg-event-fx
 ::fetch-symbol-counts
 (fn-traced [{:keys [db]} [_ [q]]]
            (let [request-no (inc (:symbol-counts-request-no db 0))]
              {:fx [[:http {:url (-> "/symbol-counts"
                                     (goog.uri.utils/appendParam  "q" q )
                                     (goog.uri.utils/appendParam  "request-no" request-no))
                            :on-success ::process-symbol-counts}]]
               :db (assoc db :symbol-counts-request-no request-no)})))

(re-frame/reg-event-db
 ::process-symbol-counts
 (fn-traced [db [_ response]]
            (let [{:keys [symbol-counts-q symbol-counts request-no]} response]
              (if (>= request-no (:symbol-counts-request-no db 0))
                (-> db
                    (assoc :symbol-counts-q symbol-counts-q)
                    (assoc :symbol-counts symbol-counts))
                db)))) ; out-of-order request

(re-frame/reg-event-db
 ::set-active-panel
 (fn-traced [db [_ [panel]]]
            (assoc db :active-panel panel)))

(comment
  (goog.uri.utils/appendPath "/symbol-counts" "http://foo/bar")
  (expand-ancestors :a {:a :b} #{:a})
  (js/encodeURIComponent "http://bla")

  )
