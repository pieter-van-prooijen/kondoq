(ns kondoq.views
  (:require [goog.dom :as dom]
            [kondoq.events :as events]
            [kondoq.project-views :as project-views]
            [kondoq.subs :as subs]
            [kondoq.util :refer [<sub >evt occurrence-key add-line-markup] :as util]))

(defn ancestors-expanded [k parents expanded]
  (if-let [parent (parents k)]
    (and (expanded parent)
         (ancestors-expanded parent parents expanded))
    true))

(defn occurrence-expandable [x]
  (let [expanded (<sub [::subs/expanded])
        is-expanded (expanded x)
        parents (<sub [::subs/parents])
        ancestors-expanded (ancestors-expanded x parents expanded)]
    [:span.tag {:on-click (fn [e]
                            (.preventDefault e)
                            (>evt [::events/toggle-expanded [x parents]]))}
     x
     (if (or (not is-expanded) (not ancestors-expanded))
       [:span {:dangerouslySetInnerHTML {:__html "&nbsp;&hellip;"}}]
       [:button.delete {:class (when (or (not is-expanded) (not ancestors-expanded))
                                 "is-hidden")}])]))

(defn external-code-link [occurrence]
  (let [key (occurrence-key occurrence)
        location (:location (get parents key))]
    (when location
      [:a.tag.is-link {:href location} location])))

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

(defn occurrence-code [{:keys [line context line-no start-context location]
                        :as occurrence}]
  (let [key (occurrence-key occurrence)
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

(defn occurrence-row [row idx]
  (let [[[project project-rs] [namespace namespace-rs] [occurrence _]] row]
    ^{:key idx}
    [:tr
     (when project
       [:td {:rowSpan project-rs}
        [occurrence-expandable project]])
     (when namespace
       [:td {:rowSpan namespace-rs}
        [occurrence-expandable namespace]])
     [:td
      [occurrence-code occurrence]]]))

(defn invoke-fetch
  ([e]
   (invoke-fetch (.-value (dom/getElement "search-var")) e))
  ([symbol e]
   (.preventDefault e)
   (>evt [::events/fetch-namespaces-occurrences
          [symbol]])))

(defn invoke-fetch-with-enter [symbol e]
  (when (= (.-code e) "Enter")
    (invoke-fetch symbol e)))

(defn symbol-counts-row [substr {:keys [symbol count]}]
  (let [[before middle after] (util/split-with-substr symbol substr)]
    ^{:key symbol}
    [:tr.symbol-counts-row.is-clickable {:on-click
                                         (partial invoke-fetch symbol)
                                         :tab-index
                                         0
                                         :on-key-down
                                         (partial invoke-fetch-with-enter symbol)}
     [:td
      before
      [:span.has-text-weight-bold middle]
      after]
     [:td count]]))

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
                     :on-change (fn [_]
                                  (>evt [::events/fetch-symbol-counts
                                         [(.-value (dom/getElement "search-var"))]]))}]]
     [:div.control
      [:div.button.is-primary {:on-click invoke-fetch}
       "Search"]]]
    [:p.help "Type three or more characters of a var for a list of matches,"
     " selectable using the mouse or <TAB>."]]

   ;; type-ahead table
   [:table.table.is-family-monospace
    [:tbody
     (map (partial symbol-counts-row (<sub [::subs/symbol-counts-q]))
          (<sub [::subs/symbol-counts]))]]

   ;; results
   [:div.mt-6
    (if-let [symbol (<sub [::subs/symbol])]
      [:h2.subtitle "Results for '" symbol "' :"]
      [:h2.subtile "No active search, type a query"])
    [:table.table
     [:thead
      [:tr
       [:th "Project"]
       [:th "Namespace"]
       [:th "Occurrences"]]]
     [:tbody
      (map occurrence-row (<sub [::subs/occurrence-rows]) (range))]]]])

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

