(ns kondoq.events
  (:require [cljs.reader :refer [read-string]]
            [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.net.XhrIo :as xhrio]
            [goog.uri.utils]
            [re-frame.core :as re-frame]))

(re-frame/reg-event-fx
 ::initialize-db
 (fn-traced [_ _]
            ;; just fetch the projects, no query
            {:db {:active-panel :search
                  :expanded #{}}
             :dispatch [::fetch-namespaces-occurrences ""]}))

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

(re-frame/reg-fx
 :http
 (fn [[url success-event method]]
   (xhrio/send url
               (fn [ev]
                 (let [^XMLHttpRequest target (.-target ev)
                       response (.getResponseText target)]
                   (re-frame/dispatch [success-event response])))
               (or method "GET")
               nil
               #js {:accept "application/edn"})))


(re-frame/reg-event-fx
 ::fetch-namespaces-occurrences
 (fn-traced [{:keys [db]} [_ [fq-symbol-name]]]
            {:http [(goog.uri.utils/appendParam "/occurrences"
                                                "fq-symbol-name" fq-symbol-name)
                    ::process-fetch-namespaces-occurrences]
             :db (assoc db :symbol fq-symbol-name)}))

(re-frame/reg-event-db
 ::process-fetch-namespaces-occurrences
 (fn-traced [db [_ response-body]]
            (let [{:keys [projects namespaces occurrences]} (read-string response-body)]
              (-> db
                  (assoc :namespaces namespaces)
                  (assoc :occurrences occurrences)
                  (assoc :projects projects)
                  (assoc :symbol-counts [])))))

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
