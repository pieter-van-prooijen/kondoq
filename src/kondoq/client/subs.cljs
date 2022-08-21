(ns kondoq.client.subs
  (:require
   [cljs.math :as math]
   [kondoq.client.util :refer [usage-key]]
   [re-frame.core :refer [reg-sub]]))

;; Change the last collection of xs in colls to a seq of [[x count] nil nil ...].
(defn- add-count-to-last-coll
  ([colls]
   (let [bl (butlast colls)
         l (last colls)
         size (count l)
         with-count (if (seq l)
                      (concat [[(first l) size]] (repeat (dec size) nil))
                      [])]
     (concat bl [with-count]))))

;; Add x to the last collection in colls.
(defn- add-to-last-coll [x colls]
  (let [bl (butlast colls)
        l (last colls)]
    (concat bl [(concat l [x])])))

;; Add x in a new collection added to colls.
(defn- add-new-coll [x colls]
  (concat colls [[x]]))

;; Update the collection under key in m for the given value change.
(defn- update-coll-under-key [m key last-value current-value]
  (if (or (nil? last-value) (= last-value current-value))
    ;; No change, just add it.
    (update m key #(add-to-last-coll current-value %))
    ;; Value change, convert the previous list of column values to a count + nils
    (-> m
        (update key add-count-to-last-coll)
        (update key #(add-new-coll current-value %)))))

(defn- usage-location [usage namespace-location]
  (str namespace-location "#L" (:line-no usage))) ; GitHub line fragment.

;; Reducing function adding a single usage.
(defn- add-usage [{:keys [projects namespaces usages skip] :as result}
                  usage
                  expanded
                  symbol->namespace]
  (let [last-project (first (last projects))
        last-namespace (first (last namespaces))
        last-usage (first (last usages))
        current-namespace (:used-in-ns usage)
        current-project (get-in symbol->namespace
                                [current-namespace :project]
                                (str current-namespace "-UNKNOWN"))
        current-location (get-in symbol->namespace [current-namespace :location])
        ;; Add the location to the usage to construct the source file link.
        usage-with-location (assoc usage :location
                                   (usage-location usage current-location))]

    (if (or (skip current-project) (skip current-namespace))
      result
      (-> result
          (update-coll-under-key :projects last-project current-project)
          (update-coll-under-key :namespaces last-namespace current-namespace)
          (update-coll-under-key :usages last-usage usage-with-location)
          ;; Skip any not-expanded project or namespace in the next iteration.
          (update :skip (fn [old-skip] (reduce (fn [r x]
                                                 (if-not (expanded x)
                                                   (conj r x)
                                                   r))
                                               old-skip
                                               [current-project current-namespace])))))))

;; Transform the list of usages in a list of rows suitable for <table> rendering:
;; Each row has three columns, for project, namespace and usage.
;; Each column is either [x row-span] (a non-empty cell) or nil (an empty cell in the table,
;; covered by a rowspan in one of cells above it).
;; Example:
;; ([["re-frame" 2] [re-frame.core 2] [{:symbol inc, :ns re-frame.core, :line-no 42, :line "(inc x)"} 1]]
;;  [nil            nil               [{:symbol inc, :ns re-frame.core, :line-no 43, :line "(inc y)"} 1]])
;;
(defn- usages-as-rows [usages expanded symbol->namespace]
  (-> (reduce (fn [r usage]
                (add-usage r usage expanded symbol->namespace))
              {:projects [] :namespaces [] :usages [] :skip #{}}
              usages)
      (update :projects add-count-to-last-coll)
      (update :namespaces add-count-to-last-coll)
      (update :usages add-count-to-last-coll)
      (as-> $
          (map vector
               (apply concat (:projects $))
               (apply concat (:namespaces $))
               (apply concat (:usages $))))))

(reg-sub
::usages
(fn [db _]
  (:usages db)))

(reg-sub
 ::usages-count
 (fn [db _]
   (:usages-count db 0)))

(reg-sub
 ::expanded
 (fn [db _]
   (:expanded db)))

(reg-sub
 ::namespaces
 (fn [db _]
   (:namespaces db)))

(reg-sub
 ::symbol->namespace
 :<- [::namespaces]
 (fn [namespaces _]
   (zipmap (map :ns namespaces) namespaces)))

(reg-sub
 ::usages-rows
 :<- [::usages]
 :<- [::expanded]
 :<- [::symbol->namespace]
 (fn [[usages expanded symbol->namespace] _]
   (usages-as-rows usages expanded symbol->namespace)))

(reg-sub
 ::projects
 (fn [db _]
   (:projects db)))

(reg-sub
::manifest
(fn [db _]
  (:manifest db)))

(reg-sub
 ::config-path
 (fn [db _]
   (:config-path db)))

(reg-sub
 ::symbol-counts
 (fn [db _]
   (:symbol-counts db)))

(reg-sub
 ::symbol-counts-q
 (fn [db _]
   (:symbol-counts-q db)))

(reg-sub
 ::symbol
 (fn [db _]
   (:symbol db)))

(reg-sub
 ::arity
 (fn [db _]
   (:arity db)))

;; Keep track of generic child -> parent relationships for use in the views.
(reg-sub
 ::child->parent
 :<- [::usages]
 :<- [::symbol->namespace]
 (fn [[usages symbol->namespace] _]
   (-> {}
       (into (map (fn [o]
                    [(usage-key o) (:ns o)])
                  usages))
       (into (map (fn [[ns {project :project}]]
                    [ns project])
                  symbol->namespace)))))

(reg-sub
::active-panel
(fn [db _]
  (:active-panel db)))

;;
;; Pagination.
(reg-sub
 ::page
 (fn [db _]
   (get-in db [:pagination :page] 0)))

(reg-sub
 ::page-size
 (fn [db _]
   (get-in db [:pagination :page-size] 20)))

(reg-sub
 ::page-count
 :<- [::usages-count]
 :<- [::page-size]
 (fn [[usages-count page-size] _]
   (math/ceil (/ usages-count page-size))))

;;
;; Projects tab subs.
(reg-sub
 ::projects-state
 (fn [db _]
   (:projects-state db :showing-projects)))

(reg-sub
::current-project
(fn [db _]
  (:current-project db {})))

;;
;; Last seen http failure.
(reg-sub
 ::http-failure
 (fn [db _]
   (:http-failure db)))

(comment

  )
