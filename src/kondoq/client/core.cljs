(ns kondoq.client.core
  (:require [clojure.string :as string]
            [kondoq.client.config :as config]
            [kondoq.client.events :as events]
            [kondoq.client.views :as views]
            [re-frame.core :as re-frame]
            [reagent.dom :as rdom]))

(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

;; called by shadow-cljs after reloading a file
(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [views/main-panel] root-el)))

;; configured in shadow-cljs in :builds/:app/:modules/:app/:init-fn
(defn init []
  ;; configure highlight.js to detect malicious code blocks
  (js/hljs.configure #js {:throwUnescapedHTML true})
  ;; detect returning from the github oauth login screen.
  (let [search-params (js/URLSearchParams. (.. js/window -location -search))
        href (.. js/window -location -href)
        href-without-search (string/replace href #"\?.*$" "")
        adding-project (.get search-params "adding-project")]
    (if adding-project
      (do
        ;; reset the app url so a reload won't jump to the adding-project state again.
        (. js/history pushState {} "" href-without-search)
        (re-frame/dispatch-sync [::events/initialize-with-adding-project adding-project]))
      (re-frame/dispatch-sync [::events/initialize-db])))
  (dev-setup)
  (mount-root))
