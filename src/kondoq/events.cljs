(ns kondoq.events
  (:require [cljs.reader :refer [read-string]]
            [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.net.XhrIo :as xhrio]
            [goog.uri.utils]
            [kondoq.project-events :as project-events]
            [re-frame.core :as re-frame]))

(re-frame/reg-event-fx
 ::initialize-db
 (fn-traced [_ _]
            {:db {:active-panel :search
                  :expanded #{}}
             :dispatch [::project-events/fetch-projects]}))

;; when clicking on an item, automatically expand all its items to the left
(defn expand-ancestors [k parents expanded]
  (if-let [parent (parents k)]
    (into (conj expanded parent) (expand-ancestors parent parents expanded))
    expanded))

(re-frame/reg-event-db
 ::toggle-expanded
 (fn-traced [db [_ [x parents]]]
            (update db :expanded (fn [expanded]
                                   (if (expanded x)
                                     (disj expanded x)
                                     (->> (conj expanded x)
                                          (expand-ancestors x parents)))))))

;; Execute a http request.
;; After success, dispatches an event with the response body or a user defined
;; event tupple
(re-frame/reg-fx
 :http
 (fn [[url success-event-or-tupple method body headers]]
   (xhrio/send url
               (fn [ev]
                 (let [^XMLHttpRequest target (.-target ev)
                       response (.getResponseText target)]
                   (if (coll? success-event-or-tupple)
                     (re-frame/dispatch success-event-or-tupple)
                     (re-frame/dispatch [success-event-or-tupple response]))))
               (or method "GET")
               body
               (clj->js (merge {:accept "application/edn"} headers)))))

;; page is zero based
(re-frame/reg-event-fx
 ::fetch-namespaces-usages
 (fn-traced [{:keys [db]} [_ [fq-symbol-name arity page page-size]]]
            {:http [(-> "/usages"
                        (goog.uri.utils/appendParam "fq-symbol-name"
                                                    fq-symbol-name)
                        (goog.uri.utils/appendParam "arity"
                                                    arity)
                        (goog.uri.utils/appendParam "page"
                                                    page)
                        (goog.uri.utils/appendParam "page-size"
                                                    page-size))
                    ::process-fetch-namespaces-usages]
             :db (-> db
                     (assoc :symbol fq-symbol-name)
                     (assoc :arity arity))}))

(re-frame/reg-event-db
 ::process-fetch-namespaces-usages
 (fn-traced [db [_ response-body]]
            (let [{:keys [namespaces usages usages-count page page-size]}
                  (read-string response-body)]
              (-> db
                  (assoc :namespaces namespaces)
                  (assoc :usages usages)
                  (assoc :usages-count usages-count)
                  (assoc :page page)
                  (assoc :page-size page-size)
                  (assoc :symbol-counts [])
                  ;; when paging, all namespaces and projects are expanded
                  (assoc :expanded (-> #{}
                                       (into (map :project namespaces))
                                       (into (map :ns namespaces))))))))

(re-frame/reg-event-fx
 ::fetch-symbol-counts
 (fn-traced [{:keys [db]} [_ [q]]]
            {:http [(goog.uri.utils/appendParam "/symbol-counts" "q" q)
                    ::process-symbol-counts]
             :db (assoc db :symbol-counts-q q)}))

(re-frame/reg-event-db
 ::process-symbol-counts
 (fn-traced [db [_ response-body]]
            (let [symbol-counts (read-string response-body)]
              (assoc db :symbol-counts symbol-counts))))

(re-frame/reg-event-db
 ::set-active-panel
 (fn-traced [db [_ [panel]]]
            (assoc db :active-panel panel)))

(comment
  (goog.uri.utils/appendPath "/symbol-counts" "http://foo/bar")
  (expand-ancestors :a {:a :b} #{:a})
  (js/encodeURIComponent "http://bla")

  )
