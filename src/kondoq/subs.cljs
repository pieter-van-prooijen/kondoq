(ns kondoq.subs
  (:require
   [cljs.math :as math]
   [kondoq.util :refer [usage-key]]
   [re-frame.core :refer [reg-sub]]))

;; change the last coll in colls to a seq of [[x count] nil]
(defn add-count-to-last-coll
  ([colls]
   (let [bl (butlast colls)
         l (last colls)
         size (count l)
         with-count (if (seq l)
                      (concat [[(first l) size]] (repeat (dec size) nil))
                      [])]
     (concat bl [with-count]))))

;; add x to the last collection in colls, creating it if needed
(defn add-to-last-coll [x colls]
  (let [bl (butlast colls)
        l (last colls)]
    (concat bl [(concat l [x])])))

;; add x in a new collection added to colls
(defn add-new-coll [x colls]
  (concat colls [[x]]))

;; update the collection under key in result for the given value change
(defn update-coll-under-key [m key last-value current-value]
  (if (or (nil? last-value) (= last-value current-value))
    (update m key (partial add-to-last-coll current-value))
    (-> m
        (update key add-count-to-last-coll)
        (update key (partial add-new-coll current-value)))))

(defn usage-location [usage namespace-location]
  (str namespace-location "#L" (:line-no usage)))

(defn add-usage [{:keys [projects namespaces skip] :as result}
                 usage
                 expanded
                 indexed-namespaces]
  (let [last-project (first (last projects))
        last-namespace (first (last namespaces))
        current-namespace (:used-in-ns usage)
        current-project (get-in indexed-namespaces
                                [current-namespace :project]
                                (str current-namespace "-UNKNOWN"))
        current-location (get-in indexed-namespaces [current-namespace :location])
        ;; add the location to the usage to construct the source file link
        updated-usage (assoc usage :location
                             (usage-location usage current-location))]

    (if (or (skip current-project) (skip current-namespace))
      result
      (-> result
          (update-coll-under-key :projects last-project current-project)
          (update-coll-under-key :namespaces last-namespace current-namespace)
          (update :usages (partial add-to-last-coll [updated-usage 1]))
          ;; skip any not-expanded project or namespace in the next iteration
          (update :skip (fn [skip] (reduce (fn [r x]
                                             (if-not (expanded x)
                                               (conj r x)
                                               r))
                                           skip
                                           [current-project current-namespace])))))))

;; rows for the result table, each column is either [x row-span] or nil
;; e.g
;; ([["re-frame" 3]
;;   [re-frame.core 2]
;;   [{:symbol inc, :ns re-frame.core, :line-no 42, :line "(inc x)"} 1]]
;;  [nil
;;   nil
;;   [{:symbol inc, :ns re-frame.core, :line-no 43, :line "(inc y)"} 1]])
;;
(defn usages-as-rows [expanded indexed-namespaces usages]
  (-> (reduce (fn [r usage] (add-usage r usage expanded indexed-namespaces))
              {:projects [] :namespaces [] :usages [] :skip #{}}
              usages)
      (update :projects add-count-to-last-coll)
      (update :namespaces add-count-to-last-coll)
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
 ::symbol
 (fn [db _]
   (:symbol db)))

(reg-sub
 ::arity
 (fn [db _]
   (:arity db)))

(reg-sub
 ::namespaces
 (fn [db _]
   (:namespaces db)))

(reg-sub
 ::indexed-namespaces
 :<- [::namespaces]
 (fn [namespaces _]
   (reduce (fn [r ns] (assoc r (:ns ns) ns)) {} namespaces)))

(reg-sub
 ::projects
 (fn [db _]
   (:projects db)))

(reg-sub
 ::manifest
 (fn [db _]
   (:manifest db)))

(reg-sub
 ::expanded
 (fn [db _]
   (:expanded db)))

(reg-sub
 ::symbol-counts
 (fn [db _]
   (:symbol-counts db)))

(reg-sub
 ::symbol-counts-q
 (fn [db _]
   (:symbol-counts-q db)))

;; convert the usages into a sequence of rows for display in table

(reg-sub
 ::usages-rows
 :<- [::usages]
 :<- [::indexed-namespaces]
 :<- [::expanded]
 (fn [[usages indexed-namespaces expanded] _]
   (usages-as-rows expanded indexed-namespaces usages)))

;; keep track of generic child -> parent relationships for proper ui display
(reg-sub
 ::parents
 :<- [::usages]
 :<- [::indexed-namespaces]
 (fn [[usages indexed-namespaces] _]
   (-> {}
       (into (map (fn [o]
                    [(usage-key o) (:ns o)])
                  usages))
       (into (map (fn [[ns {project :project}]]
                    [ns project])
                  indexed-namespaces)))))

(reg-sub
 ::active-panel
 (fn [db _]
   (:active-panel db)))

;;
;; Pagination
(reg-sub
 ::page
 (fn [db _]
   (:page db 0)))

(reg-sub
 ::page-size
 (fn [db _]
   (:page-size db 20)))

(reg-sub
 ::page-count
 :<- [::usages-count]
 :<- [::page-size]
 (fn [[usages-count page-size] _]
   (math/ceil (/ usages-count page-size))))

;;
;; Projects tab subs

(reg-sub
 ::projects-state
 (fn [db _]
   (:projects-state db :showing-projects)))

(reg-sub
 ::current-project
 (fn [db _]
   (:current-project db {})))

(comment
  (require 'kondoq.db)
  (add-to-last-coll :a [[:c] [:b]]) ; ([:c] (:b :a))
  (add-to-last-coll :a [[]]) ; ((:a))

  (add-count-to-last-coll [])

  (math/ceil 0.0)

  (usages-as-rows #{"re-frame"} (:namespaces kondoq.db/default-db) (:usages kondoq.db/default-db))


  )
