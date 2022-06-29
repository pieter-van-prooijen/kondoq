(ns kondoq.subs
  (:require
   [kondoq.util :refer [occurrence-key]]
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

;; update the collection under key for the given value change
(defn update-colls [result key last-value current-value]
  (if (or (nil? last-value) (= last-value current-value))
    (update result key (partial add-to-last-coll current-value))
    (-> result
        (update key add-count-to-last-coll)
        (update key (partial add-new-coll current-value)))))

(defn add-occurrence [expanded
                      indexed-namespaces
                      {:keys [projects namespaces skip] :as result}
                      occurrence]
  (let [last-project (first (last projects))
        last-namespace (first (last namespaces))
        current-namespace (:ns occurrence)
        current-project (get-in indexed-namespaces
                                [current-namespace :project]
                                (str current-namespace "-UNKNOWN"))
        current-location (get-in indexed-namespaces [current-namespace :location])
        ;; add the location to the occurrence to construct the source file link
        updated-occurrence (assoc occurrence :location current-location)]

    (if (or (skip current-project) (skip current-namespace))
      result
      (-> result
          (update-colls :projects last-project current-project)
          (update-colls :namespaces last-namespace current-namespace)
          (update :occurrences (partial add-to-last-coll [updated-occurrence 1]))
          ;; skip any project or namespace in the next iteration
          (update :skip (fn [skip] (reduce (fn [r x]
                                             (if (not (expanded x))
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
(defn occurrences-as-rows [expanded indexed-namespaces occurrences]
  (-> (reduce (partial add-occurrence expanded indexed-namespaces)
              {:projects [] :namespaces [] :occurrences [] :skip #{}}
              occurrences)
      (update :projects add-count-to-last-coll)
      (update :namespaces add-count-to-last-coll)
      (as-> $
          (map vector
               (apply concat (:projects $))
               (apply concat (:namespaces $))
               (apply concat (:occurrences $))))))

(reg-sub
 ::occurrences
 (fn [db _]
   (:occurrences db)))

(reg-sub
 ::symbol
 (fn [db _]
   (:symbol db)))

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

;; convert the occurrences into a sequence of rows for display in table

(reg-sub
 ::occurrence-rows
 :<- [::occurrences]
 :<- [::indexed-namespaces]
 :<- [::expanded]
 (fn [[occurrences indexed-namespaces expanded] _]
   (occurrences-as-rows expanded indexed-namespaces occurrences)))

;; keep track of generic child -> parent relationships for proper ui display
(reg-sub
 ::parents
 :<- [::occurrences]
 :<- [::indexed-namespaces]
 (fn [[occurrences indexed-namespaces] _]
   (-> {}
       (into (map (fn [o]
                    [(occurrence-key o) (:ns o)])
                  occurrences))
       (into (map (fn [[ns {project :project}]]
                    [ns project])
                  indexed-namespaces)))))

(reg-sub
 ::active-panel
 (fn [db _]
   (:active-panel db)))

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


  (occurrences-as-rows #{"re-frame"} (:namespaces kondoq.db/default-db) (:occurrences kondoq.db/default-db))


  )
