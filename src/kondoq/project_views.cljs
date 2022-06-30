(ns kondoq.project-views
  (:require [goog.dom :as dom]
            [kondoq.project-events :as project-events]
            [kondoq.subs :as subs]
            [kondoq.util :refer [<sub >evt] :as util]))

;; Adding a project states:
;; - showing-projects
;; - entering-project-url (and a token)
;; - adding-project (with feedback about the loaded namespaces etc.)
;; - showing-projects or showing-error
;;

(defn show-enter-project-url [e]
  (.preventDefault e)
  (>evt [::project-events/show-enter-project-url]))

(defn cancel-enter-project-url [e]
  (.preventDefault e)
  (>evt [::project-events/cancel-enter-project-url]))

(defn add-project [e]
  (.preventDefault e)
  (>evt [::project-events/add-project [(.-value (dom/getElement "project-url"))
                                       (.-value (dom/getElement "token"))]]))

(defn cancel-add-project [e]
  (.preventDefault e)
  (>evt [::project-events/cancel-add-project]))

(defn add-project-form [is-active]
  (let [projects-state (<sub [::subs/projects-state])
        current-project (<sub [::subs/current-project])]
    [:div.modal {:class (when is-active "is-active")}
     [:div.modal-background {:on-click cancel-enter-project-url}]
     [:div.modal-card
      [:header.modal-card-head
       [:p.modal-card-title "Add Github project"]
       [:button.delete {:on-click cancel-enter-project-url}]]
      [:section.modal-card-body
       [:form {:on-submit add-project}

        [:div.field
         [:label.label "GitHub URL"]
         [:div.control
          [:input.input {:id "project-url"
                         :name "project-url"
                         :type "text"}]]
         [:p.help "Project URL should have the form https://github.com/<user>/<project>"]]

        [:div.field
         [:label.label "Github Personal Access Token (optional)"]
         [:div.control
          [:input.input {:id "token"
                         :name "token"
                         :type "text"}]] ;; should really be password?
         [:p.help {:dangerouslySetInnerHTML
                   {:__html
                    (str "Without authorization, the GitHub API is limited to 200 requests/h. <br>"
                         "Get a personal access token (for retrieving public git repositories) "
                         "from your github account settings at "
                         "<a target='_blank' href='https://github.com/settings/tokens'>https://github.com/settings/tokens</a>.<br>"
                         "This token will <strong>not</strong> be stored or logged in the Kondoq backend.")}}]]

        [:div.field.is-grouped
         [:div.control
          [:button.button.is-link
           {:on-click #(when (= projects-state :entering-project-url) add-project)
            :class (when (= projects-state :adding-project) "is-loading")}
           "Add"]]
         [:div.control
          [:button.button.is-link.is-light
           {:on-click #(condp = projects-state
                         :entering-project-url (cancel-enter-project-url %)
                         :adding-project (cancel-add-project %))}
           "Cancel"]]]]
       (when (= projects-state :adding-project)
         [:div.level.mt-4
          [:div.level-left
           [:div.level-item
            [:h6.title.is-6 (:project current-project)]]
           [:div.level-item
            [:progress.progress.is-primary {:max (:ns-total current-project)
                                            :value (:ns-count current-project)}]]]])]]]))

(defn delete-project [project location e]
  (.preventDefault e)
  (when (js/confirm (str "Do you want to delete project " project " ?"))
    (>evt [::project-events/delete-project location])))

(defn project-row [{:keys [project location ns-count]}]
  ^{:key project} [:tr
                   [:td project]
                   [:td [:a {:href location :target "_blank"} location]]
                   [:td.has-text-right ns-count]
                   [:td
                    [:button.delete {:on-click #(delete-project project location %)}]]])

(defn projects-panel [is-active]
  [:div {:class (when-not is-active "is-hidden")}
   [:h5.title.is-5 "Projects:"]
   [:table.table
    [:thead
     [:tr
      [:td "Name"]
      [:td "Location"]
      [:td "Namespaces"]
      [:td ""]]]
    [:tbody
     (map project-row (<sub [::subs/projects]))]]
   [:div
    [:a.button {:on-click show-enter-project-url} "Add project..."]]
   [add-project-form (not= (<sub [::subs/projects-state]) :showing-projects)]])
