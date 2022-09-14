(ns kondoq.client.search-events
  (:require [day8.re-frame.tracing :refer-macros [fn-traced]]
            [goog.uri.utils]
            [re-frame.core :as re-frame]))

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
             :db (assoc db :symbol (symbol fq-symbol-name)
                        :arity arity)}))

(re-frame/reg-event-db
 ::process-fetch-namespaces-usages
 (fn-traced [db [_ response]]
            (let [{:keys [namespaces usages usages-count page page-size]}
                  response]
              (-> db
                  (assoc :namespaces namespaces
                         :usages usages
                         :usages-count usages-count
                         :symbol-counts []
                         ;; When paging, all namespaces and projects are expanded.
                         :expanded (-> #{}
                                       (into (map :project namespaces))
                                       (into (map :ns namespaces))))
                  (assoc-in [:pagination :page] page)
                  (assoc-in [:pagination :page-size] page-size)))))

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
                (assoc db :symbol-counts-q symbol-counts-q
                       :symbol-counts symbol-counts)
                db)))) ; Out-of-order request, ignore.


