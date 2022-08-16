(ns kondoq.client.pagination
  (:require [cljs.math :as math]))

(defn- random-int
  "Generate a random integer between `min-int` (inclusive) and `max-int`
  (exclusive)."
  [min-int max-int]
  (let [min (math/ceil min-int)
        max (math/floor max-int)]
    (math/floor (+ (* (math/random) (- max min)) min))))

(defn build-page-nums
  "Build an ordered list of page numbers to display for `nof-pages` of results,
  with the current page at `page` (zero-based).
  The list has start, current and end blocks, where the current block is the
  sublist of numbers surrounding `page` and `nof-start-end` the number of entries
  at the beginning and end of the list.
  -1 entries in the list denotes the display of an ellipsis between the blocks."
  ([nof-pages page]
   (build-page-nums nof-pages page 1))
  ([nof-pages page nof-start-end]
   (let [last-page (max (- nof-pages 1) 0)
         ;; list of page numbers to display
         start-page-nums (range 0 (min nof-start-end nof-pages))
         current-page-nums (range (max (dec page) 0) (min (+ page 2) (+ last-page 1)))
         end-page-nums (range (max (- nof-pages nof-start-end) 0) nof-pages)
         all-page-nums (concat start-page-nums current-page-nums end-page-nums)
         ;; Set of pagenumbers which should show an ellipsis (after/before the start/end block)
         ;; Don't show the ellipsis if it is part of any of the page ranges to show as numbers
         ellipsis (apply disj
                         #{(min (inc (last start-page-nums)) last-page)
                           (max (dec (first end-page-nums)) 0)}
                         all-page-nums)]
     (->> (concat all-page-nums ellipsis)
          (into #{} ) ;; de-duplicate page numbers
          sort
          (map (fn [n] (if (ellipsis n) -1 n))))))) ; indicate the ellipsis in the list

(defn- pagination-attributes [page disabled page-change-fn]
  (merge
   {:href "#"
    :on-click (fn [e]
                (.preventDefault e)
                (page-change-fn page))}
   (when disabled {:disabled "disabled"})))

(defn pagination-attributes-random
  "Return the attributes of a random page component to supplement the
  pagination component.
  See the pagination component for the parameter details."
  [nof-pages page-change-fn]
  (merge
   {:href "#"
    :on-click (fn [e]
                (.preventDefault e)
                (page-change-fn (random-int 0 nof-pages)))}
   (when (< nof-pages 5) {:disabled "disabled"})))

(defn pagination
  "Render a pagination component for `nof-pages` of results positioned at
  `page`.
  `page-change-fn` is invoked with the new page number when one of the page
  buttons is clicked."
  [page nof-pages page-change-fn]
  (let [page-nums (build-page-nums nof-pages page 1)]
    [:nav.pagination {:role "navigation"}
     [:ul.pagination-list
      [:li>a.pagination-previous (pagination-attributes (dec page)
                                                        (zero? page)
                                                        page-change-fn)
       "Previous"]
      ;; Can't use page-nums as a react key because of duplicate -1 entries.
      (for [[item key] (map vector page-nums (map inc (range)))]
        (if (= item page)
          [:li {:key key}
           [:a.pagination-link.is-current (inc page)]]
          (if (neg? item)
            [:li {:key key}
             [:a.pagination-ellipsis {:dangerouslySetInnerHTML {:__html "&hellip;"}}]]
            [:li {:key key}
             [:a.pagination-link (pagination-attributes item false page-change-fn)
              (inc item)]])))
      [:li>a.pagination-next (pagination-attributes (inc page)
                                                    (= page (dec nof-pages))
                                                    page-change-fn)
       "Next"]
      [:li>a.pagination-link
       (pagination-attributes (random-int 0 nof-pages)
                              (< nof-pages 5)
                              page-change-fn)
       "Random"]]]))

