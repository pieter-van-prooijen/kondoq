(ns kondoq.core
  (:require
   [kondoq.config :as config]
   [kondoq.events :as events]
   [kondoq.views :as views]
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
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (mount-root))
