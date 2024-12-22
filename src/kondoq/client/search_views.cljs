(ns kondoq.client.search-views
  "Views for the search panel."
  (:require [goog.dom :as dom]
            [kondoq.client.pagination]
            [kondoq.client.search-events :as events]
            [kondoq.client.subs :as subs]
            [kondoq.client.util :refer [<sub >evt usage-key add-line-markup] :as util]))

(defn- ancestors-expanded? [k child->parent expanded]
  (if-let [parent (child->parent k)]
    (and (expanded parent)
         (ancestors-expanded? parent child->parent expanded))
    true))

(defn- usage-expandable [x]
  (let [expanded (<sub [::subs/expanded])
        is-expanded (expanded x)
        child->parent (<sub [::subs/child->parent])
        ancestors-expanded (ancestors-expanded? x child->parent expanded)]
    [:span.tag.is-size-6 {:on-click (fn [e]
                                      (.preventDefault e)
                                      (>evt [::events/toggle-expanded [x child->parent]]))}
     x
     (if (and is-expanded ancestors-expanded)
       [:button.delete]
       [:span {:dangerouslySetInnerHTML {:__html "&nbsp;&hellip;"}}])]))

(defn- highlight-as-clojure [s]
  (-> s
      (js/hljs.highlight #js {:language "clojure"})
      .-value))

(defn- code-fragment [line context is-collapsed start-context line-no]
  (let [class (when is-collapsed "single-line")
        html (if is-collapsed
               (-> line
                   (highlight-as-clojure)
                   (add-line-markup line-no line-no))
               (-> (or context ";; no context available")
                   (highlight-as-clojure)
                   (add-line-markup start-context line-no)))]
    [:pre {:class class}
     ;; See core.cljs which configures highlight.js to escape unsafe sources.
     [:code {:dangerouslySetInnerHTML {:__html html}}]]))

(defn- usage-code [{:keys [line context line-no start-context location]
                    :as usage}]
  (let [key (usage-key usage)
        expanded (<sub [::subs/expanded])
        is-expanded (expanded key)
        child->parent (<sub [::subs/child->parent])
        ancestors-expanded (ancestors-expanded? key child->parent expanded)
        is-collapsed (or (not is-expanded) (not ancestors-expanded))]
    [:<>
     [:div {:on-click (fn [e]
                        (.preventDefault e)
                        ;; Allow user to copy text without collapsing the usage.
                        (when (not (util/text-selected?))
                          (>evt [::events/toggle-expanded [key child->parent]])))}
      [code-fragment line context is-collapsed start-context line-no]]
     (when-not is-collapsed
       [:a.tag.is-link {:href location :target "_blank"} location])]))

(defn- usages-row [row idx]
  (let [[[project project-rs] [namespace namespace-rs] [usage _]] row]
    ^{:key idx}
    [:tr
     (when project
       [:td {:rowSpan project-rs}
        [usage-expandable project]])
     (when namespace
       [:td {:rowSpan namespace-rs}
        [usage-expandable namespace]])
     ;; Usage should always be present and have a rowSpan of 1.
     [:td
      [usage-code usage]]]))

(defn- invoke-fetch
  ([page-size e]
   (invoke-fetch (.-value (dom/getElement "search-var")) nil page-size e))
  ([symbol arity page-size e]
   (.preventDefault e)
   (>evt [::events/fetch-namespaces-usages [symbol arity 0 page-size]])))

(defn- invoke-fetch-with-enter [symbol arity page-size e]
  (when (= (.-code e) "Enter")
    (invoke-fetch symbol arity page-size e)))

(defn- symbol-counts-row [substr {:keys [symbol count arity]}]
  (let [[before middle after] (util/split-with-substr (str symbol) substr)
        page-size (<sub [::subs/page-size])]
    ^{:key (str symbol "-" arity)}
    [:tr.symbol-counts-row.is-clickable {:on-click
                                         (partial invoke-fetch symbol arity page-size)
                                         :tab-index
                                         0
                                         :on-key-down
                                         (partial invoke-fetch-with-enter symbol arity page-size)}
     [:td
      before
      [:span.has-text-weight-bold middle]
      after]
     [:td count]
     [:td (condp = arity
            nil "n/a"
            -1 "(*)"
            (str "(" arity ")"))]]))

(defn- symbol-counts-table [symbol-count-q symbol-counts]
  (when (> (count symbol-counts) 0)
    [:table.table.is-family-monospace
     [:thead
      [:tr
       [:td "symbol"]
       [:td "count"]
       [:td "arity"]]]
     [:tbody
      ;; Having a ratom deref in a lazy seq gives a warning.
      (doall (map (partial symbol-counts-row symbol-count-q) symbol-counts))]]))

(defn- fetch-symbol-counts [e]
  (let [search-for (-> e .-target .-value)]
    (>evt [::events/fetch-symbol-counts [search-for]])))

(defn- pagination []
  (let [symbol (<sub [::subs/symbol])
        arity (<sub [::subs/arity])
        page (<sub [::subs/page])
        page-count (<sub [::subs/page-count])
        page-size (<sub [::subs/page-size])]
    (when (pos? page-count)
      [kondoq.client.pagination/pagination
       page
       page-count
       (fn [page] (>evt [::events/fetch-namespaces-usages
                         [symbol arity page page-size]]))])))

(defn search-panel [is-active]
  (let [page-size (<sub [::subs/page-size])
        invoke-fetch-with-page-size (partial invoke-fetch page-size)]
    [:div {:class (when-not is-active "is-hidden")}
     [:form {:on-submit invoke-fetch-with-page-size}     ; handle <enter> key in form field
      [:div.field.has-addons
       [:div.control
        [:input.input {:id "search-var"
                       :autoFocus true    ; TODO set focus when tab becomes active
                       :type "text"
                       :placeholder "search for var..."
                       :autoComplete "off"
                       :on-input fetch-symbol-counts
                       :on-focus fetch-symbol-counts}]]
       [:div.control
        [:div.button.is-primary {:on-click invoke-fetch-with-page-size}
         "Search"]]]
      [:p.help "Type two or more characters of a var for a list of matches,"
       " selectable using the mouse or <TAB>."]]

     ;; type-ahead table
     [symbol-counts-table (<sub [::subs/symbol-counts-q]) (<sub [::subs/symbol-counts])]

     ;; results
     [:div.mt-6
      (if-let [symbol (<sub [::subs/symbol])]
        (let [usages-count (<sub [::subs/usages-count])
              arity (<sub [::subs/arity])]
          [:h2.subtitle
           [:span.has-text-weight-bold usages-count]
           " usages of "
           [:span.has-text-weight-bold (str symbol)]
           " "
           (condp = arity
             nil "(arity n/a)"
             -1 "(all arities)"
             (str "(arity " arity ")"))])
        [:h2.subtitle "no current search, type a query"])
      [pagination]
      [:table.table
       [:thead
        [:tr
         [:th "Project"]
         [:th "Namespace"]
         [:th "Usages"]]]
       [:tbody
        (map usages-row (<sub [::subs/usages-rows]) (range))]]
      [pagination]]]))
