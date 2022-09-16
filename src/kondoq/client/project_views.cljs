(ns kondoq.client.project-views
  "Views for the project panel."
  (:require [clojure.string :as string]
            [goog.dom :as dom]
            [kondoq.client.project-events :as project-events]
            [kondoq.client.subs :as subs]
            [kondoq.client.util :refer [<sub >evt] :as util]))

(defn- show-enter-project-url [e]
  (.preventDefault e)
  (>evt [::project-events/show-enter-project-url]))

;; Generic cancel function.
(defn- cancel-projects [e]
  (.preventDefault e)
  (>evt [::project-events/cancel-projects]))

(defn- add-project [e]
  (.preventDefault e)
  (let [project-url (.-value (dom/getElement "project-url"))
        token (.-value (dom/getElement "token"))]
    (if (string/starts-with? project-url "https://github.com/")
      (>evt [::project-events/add-project [project-url token]])
      (js/alert "Project URL should have the form https://github.com/<user>/<project>"))))

(defn- add-project-form [is-active]
  (let [projects-state (<sub [::subs/projects-state])
        current-project (<sub [::subs/current-project])]
    [:div.modal {:class (when is-active "is-active")}
     [:div.modal-background {:on-click cancel-projects}]
     [:div.modal-card
      [:header.modal-card-head
       [:p.modal-card-title "Add GitHub project"]
       [:button.delete {:on-click cancel-projects}]]
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
         [:label.label "GitHub Personal Access Token (leave empty for oauth authorization)"]
         [:div.control
          [:input.input {:id "token"
                         :name "token"
                         :type "text"}]] ;; Should really be password field?
         [:div.help
          [:p "Without authorization, the GitHub API is limited to 200 requests/h."]
          [:p
           "Get a personal access token (" [:strong "only"] " for retrieving public git repositories) "
           "from your github account settings at "
           [:a {:target "_blank"
                :href "https://github.com/settings/tokens"}
            "https://github.com/settings/tokens" ]]
          [:p "If you leave the token field blank, the app will redirect you to the github oauth login page to get a temporary token that way."]
          [:br]
          [:p "Tokens will " [:strong "not"] " be stored or logged in the Kondoq backend."]
          [:br]]

         [:div.field.is-grouped
          [:div.control
           [:button.button.is-link
            {:on-click #(when (= projects-state :entering-project-url) add-project)
             :class (when (= projects-state :adding-project) "is-loading")}
            "Add"]]
          [:div.control
           [:button.button.is-link.is-light
            {:on-click cancel-projects}
            "Cancel"]]]]
        (cond
          (#{:adding-project :error-adding-project} projects-state)
          [:div.level.mt-4
           [:div.level-left
            [:div.level-item
             [:h6.title.is-6 (:project current-project)]]
            [:div.level-item
             (if (= projects-state :adding-project)
               [:progress.progress.is-primary {:max (:ns-total current-project)
                                               :value (:ns-count current-project)}]
               [:<>
                [:div.is-warning "Error: " (:error current-project)]
                (when-let [current-file (:current-file current-project)]
                  [:div.is-warning "File: " current-file])])]]])]]]]))

(defn- delete-project [project location e]
  (.preventDefault e)
  (when (js/confirm (str "Do you want to delete project " project " ?"))
    (>evt [::project-events/delete-project location])))

(defn- project-row [{:keys [project location nof-stars ns-count]}]
  ^{:key project} [:tr
                   [:td project]
                   [:td [:a {:href location :target "_blank"} location]]
                   [:td  nof-stars]
                   [:td.has-text-right ns-count]
                   [:td
                    [:button.delete {:on-click #(delete-project project location %)}]]])

(defn projects-panel [is-active]
  [:div {:class (when-not is-active "is-hidden")}
   [:table.table
    [:thead
     [:tr
      [:td "Name"]
      [:td "Location"]
      [:td "Stars"]
      [:td "Namespaces"]
      [:td ""]]]
    [:tbody
     (map project-row (<sub [::subs/projects]))]]
   [:div
    [:a.button {:on-click show-enter-project-url} "Add project..."]]
   [add-project-form (not= (<sub [::subs/projects-state]) :showing-projects)]])

