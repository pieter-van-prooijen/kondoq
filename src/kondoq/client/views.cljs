(ns kondoq.client.views
  "Main view of the app."
  (:require [kondoq.client.config :as config]
            [kondoq.client.events :as events]
            [kondoq.client.pagination]
            [kondoq.client.project-views :as project-views]
            [kondoq.client.search-views :as search-views]
            [kondoq.client.subs :as subs]
            [kondoq.client.util :refer [<sub >evt] :as util]))

(defn- switch-to-panel [panel e]
  (.preventDefault e)
  (>evt [::events/set-active-panel [panel]]))

(defn- panel-navbar-item [panel active-panel label]
  [:a.navbar-item {:class (when (= active-panel panel) "has-text-primary")
                   :on-click (partial switch-to-panel panel)}
   label])

(defn- error-message []
  (let [{:keys [last-error-code status status-text response content-type]}
        (<sub [::subs/http-failure])]
    [:article.message.is-danger {:class (when-not last-error-code "is-hidden")}
     [:div
      [:h1.title.is-4 "Error"]
      [:p "Error type: " last-error-code " (" ({6 "HTTP" 8 "Timeout"} last-error-code "unknown") ")"]
      [:p "HTTP status code: " status]
      [:p "HTTP status text: " status-text]
      (when response
        ;; Only show the html as-is when debugging!
        (if (and config/debug? (= content-type "text/html"))
          [:div {:dangerouslySetInnerHTML {:__html response}}]
          [:div response]))]]))

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
     [search-views/search-panel (= active-panel :search)]
     [project-views/projects-panel (= active-panel :projects)]
     (let [manifest (<sub [::subs/manifest])
           config-path (<sub [::subs/config-path])]
       [:div.mt-4
        [:span.tag "Config: " config-path]
        [:span.tag "Version: " (get manifest "Implementation-Version" "N/A")]
        [:span.tag "Creation-Date: " (get manifest "Creation-Date" "N/A")]
        [:span.tag "Git-Commit: " (get manifest "Git-Commit" "N/A")]])]))

