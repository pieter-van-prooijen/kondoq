(ns kondoq.client.views
  (:require [goog.dom :as dom]
            [kondoq.client.events :as events]
            [kondoq.client.pagination]
            [kondoq.client.project-views :as project-views]
            [kondoq.client.subs :as subs]
            [kondoq.client.util :refer [<sub >evt usage-key add-line-markup] :as util]))

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
       [:a.tag.is-link {:href location :target "_blank"} location])]))

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
     ;; usage should always be present and have a rowSpan of 1
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

(defn symbol-counts-table [symbol-count-q symbol-counts]
  (when (> (count symbol-counts) 0)
    [:table.table.is-family-monospace
     [:thead
      [:tr
       [:td "symbol"]
       [:td "count"]
       [:td "arity"]]]
     [:tbody
      (map (partial symbol-counts-row symbol-count-q) symbol-counts)]]))

(defn fetch-symbol-counts [e]
  (let [search-for (-> e .-target .-value)]
    (>evt [::events/fetch-symbol-counts [search-for]])))

(defn pagination []
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
  [:div {:class (when-not is-active "is-hidden")}
   [:form {:on-submit invoke-fetch} ; handle <enter> key in form field
    [:div.field.has-addons
     [:div.control
      [:input.input {:id "search-var"
                     :autoFocus true ; TODO set focus when tab becomes active
                     :type "text"
                     :placeholder "search for var..."
                     :autoComplete "off"
                     :on-input fetch-symbol-counts
                     :on-focus fetch-symbol-counts}]]
     [:div.control
      [:div.button.is-primary {:on-click invoke-fetch}
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

(defn error-message []
  (let [{:keys [last-error-code status status-text response content-type]}
        (<sub [::subs/http-failure])]
    [:article.message.is-danger {:class (when-not last-error-code "is-hidden")}
     [:div
      [:h1.title.is-4 "Error"]
      [:p "Error type: " last-error-code " (" ({6 "HTTP" 8 "Timeout"} last-error-code "unknown") ")"]
      [:p "HTTP status code: " status]
      [:p "HTTP status text: " status-text]
      (when (and response (= content-type "text/html"))
        [:div {:dangerouslySetInnerHTML {:__html response}}])
      ]]))

(defn main-panel []
  (let [active-panel (<sub [::subs/active-panel])
        projects-count (count (<sub [::subs/projects]))]
    [:div.container
     [error-message]
     [:nav.navbar
      [:div.navbar-brand
       [:div.navbar-item
        [:span.title.is-4 "Kondoq"]]]
      [:div.navbar-menu
       [:div.navbar-start
        ^{:key :search} [panel-navbar-item :search active-panel "Search"]
        ^{:key :projects} [panel-navbar-item :projects active-panel
                           (str "Projects (" projects-count ")")]]]]
     [search-panel (= active-panel :search)]
     [project-views/projects-panel (= active-panel :projects)]
     (let [manifest (<sub [::subs/manifest])
           config-path (<sub [::subs/config-path])]
       [:div.mt-4
        [:span.tag "Config: " config-path]
        [:span.tag "Version: " (get manifest "Implementation-Version" "N/A")]
        [:span.tag "Creation-Date: " (get manifest "Creation-Date" "N/A")]
        [:span.tag "Git-Commit: " (get manifest "Git-Commit" "N/A")]])]))

