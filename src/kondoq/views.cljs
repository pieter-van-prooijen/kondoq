(ns kondoq.views
  (:require [goog.dom :as dom]
            [kondoq.events :as events]
            [kondoq.pagination]
            [kondoq.project-views :as project-views]
            [kondoq.subs :as subs]
            [kondoq.util :refer [<sub >evt usage-key add-line-markup] :as util]))

(defn ancestors-expanded [k parents expanded]
  (if-let [parent (parents k)]
    (and (expanded parent)
         (ancestors-expanded parent parents expanded))
    true))

(defn usage-expandable [x]
  (let [expanded (<sub [::subs/expanded])
        is-expanded (expanded x)
        parents (<sub [::subs/parents])
        ancestors-expanded (ancestors-expanded x parents expanded)]
    [:span.tag.is-size-6 {:on-click (fn [e]
                                      (.preventDefault e)
                                      (>evt [::events/toggle-expanded [x parents]]))}
     x
     (if (and is-expanded ancestors-expanded)
       [:button.delete]
       [:span {:dangerouslySetInnerHTML {:__html "&nbsp;&hellip;"}}])]))

(defn highlight-as-clojure [s]
  (-> s
      (js/hljs.highlight #js {:language "clojure"})
      .-value))

(defn code-fragment [line context is-collapsed start-context line-no]
  (let [class (when is-collapsed "single-line")
        html (if is-collapsed
               (-> line
                   (highlight-as-clojure)
                   (add-line-markup line-no line-no))
               (-> (or context ";; no context available")
                   (highlight-as-clojure)
                   (add-line-markup start-context line-no)))]
    [:pre {:class class}
     [:code {:dangerouslySetInnerHTML {:__html html}}]]))

(defn usage-code [{:keys [line context line-no start-context location]
                   :as usage}]
  (let [key (usage-key usage)
        expanded (<sub [::subs/expanded])
        is-expanded (expanded key)
        parents (<sub [::subs/parents])
        ancestors-expanded (ancestors-expanded key parents expanded)
        is-collapsed (or (not is-expanded) (not ancestors-expanded))]
    [:<>
     [:div {:on-click (fn [e]
                        (.preventDefault e)
                        (>evt [::events/toggle-expanded [key parents]]))}
      [code-fragment line context is-collapsed start-context line-no]]
     (when-not is-collapsed
       (let [href (str location "#L" line-no)]
         [:a.tag.is-link {:href href :target "_blank"} location]))]))

(defn usages-row [row idx]
  (let [[[project project-rs] [namespace namespace-rs] [usage _]] row]
    ^{:key idx}
    [:tr
     (when project
       [:td {:rowSpan project-rs}
        [usage-expandable project]])
     (when namespace
       [:td {:rowSpan namespace-rs}
        [usage-expandable namespace]])
     [:td
      [usage-code usage]]]))

(defn invoke-fetch
  ([e]
   (invoke-fetch (.-value (dom/getElement "search-var")) nil e))
  ([symbol arity e]
   (let [page-size (<sub [::subs/page-size])]
     (.preventDefault e)
     (>evt [::events/fetch-namespaces-usages [symbol arity 0 page-size]]))))

(defn invoke-fetch-with-enter [symbol arity e]
  (when (= (.-code e) "Enter")
    (invoke-fetch symbol arity e)))

(defn symbol-counts-row [substr {:keys [symbol count arity]}]
  (let [[before middle after] (util/split-with-substr symbol substr)]
    ^{:key (str symbol "-" arity)}
    [:tr.symbol-counts-row.is-clickable {:on-click
                                         (partial invoke-fetch symbol arity)
                                         :tab-index
                                         0
                                         :on-key-down
                                         (partial invoke-fetch-with-enter symbol arity)}
     [:td
      before
      [:span.has-text-weight-bold middle]
      after]
     [:td count]
     [:td (condp = arity
            nil "n/a"
            -1 "(*)"
            (str "(" arity ")"))]]))

(defn fetch-symbol-counts [_]
  (>evt [::events/fetch-symbol-counts
         [(.-value (dom/getElement "search-var"))]]))

(defn pagination []
  (let [symbol (<sub [::subs/symbol])
        arity (<sub [::subs/arity])
        page (<sub [::subs/page])
        page-count (<sub [::subs/page-count])
        page-size (<sub [::subs/page-size])]
    (when (pos? page-count)
      [kondoq.pagination/pagination
       page
       page-count
       (fn [page] (>evt [::events/fetch-namespaces-usages
                         [symbol arity page page-size]]))])))

(defn search-panel [is-active]
  [:div {:class (when-not is-active "is-hidden")}
   [:form {:on-submit invoke-fetch} ; handle <enter> key in form field
    [:div.field.has-addons
     [:div.control
      [:input.input {:id "search-var"
                     :autoFocus true ; TODO set focus when tab becomes active
                     :type "text"
                     :placeholder "search for var..."
                     :autoComplete "off"
                     :on-change fetch-symbol-counts
                     :on-focus fetch-symbol-counts}]]
     [:div.control
      [:div.button.is-primary {:on-click invoke-fetch}
       "Search"]]]
    [:p.help "Type two or more characters of a var for a list of matches,"
     " selectable using the mouse or <TAB>."]]

   ;; type-ahead table
   (when (> (count (<sub [::subs/symbol-counts])) 1)
     [:table.table.is-family-monospace
      [:thead
       [:tr
        [:td "symbol"]
        [:td "count"]
        [:td "arity"]]]
      [:tbody
       (map (partial symbol-counts-row (<sub [::subs/symbol-counts-q]))
            (<sub [::subs/symbol-counts]))]])

   ;; results
   [:div.mt-6
    (if-let [symbol (<sub [::subs/symbol])]
      (let [usages-count (<sub [::subs/usages-count])
            arity (<sub [::subs/arity])]
        [:h2.subtitle
         [:span.has-text-weight-bold usages-count]
         " usages of "
         [:span.has-text-weight-bold symbol]
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
    [pagination]]])

(defn switch-to-panel [panel e]
  (.preventDefault e)
  (>evt [::events/set-active-panel [panel]]))

(defn panel-navbar-item [panel active-panel label]
  [:a.navbar-item {:class (when (= active-panel panel) "has-text-primary")
                   :on-click (partial switch-to-panel panel)}
   label])

(defn main-panel []
  (let [active-panel (<sub [::subs/active-panel])]
    [:div.container
     [:nav.navbar
      [:div.navbar-brand
       [:div.navbar-item
        [:span.title.is-4 "Kondoq"]]]
      [:div.navbar-menu
       [:div.navbar-start
        ^{:key :search} [panel-navbar-item :search active-panel "Search"]
        ^{:key :projects} [panel-navbar-item :projects active-panel "Projects"]]]]
     [search-panel (= active-panel :search)]
     [project-views/projects-panel (= active-panel :projects)]]))

