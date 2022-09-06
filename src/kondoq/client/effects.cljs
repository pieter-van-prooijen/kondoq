(ns kondoq.client.effects
  (:require [cljs.reader :refer [read-string]]
            [goog.net.XhrIo :as xhrio]
            [goog.uri.utils]
            [kondoq.client.events :as events]
            [re-frame.core :as re-frame]))

;; Execute an http request with the supplied parameter map, expecting an
;; edn response.
;;
;; If successful, dispatch on-success with the parsed response
;; If an error occurred, dispatch an on-error without arguments.
;; If on-success or on-error are tupples, dispatch them as-is.
;; Also updates the db with the last seen http error for debugging.
(re-frame/reg-fx
 :http
 (fn [{:keys [url method body headers on-success on-error]}]
   (xhrio/send url
               (fn [ev]
                 (let [^XMLHttpRequest target (.-target ev)
                       response (.getResponseText target)]
                   (if (.isSuccess target)
                     (do
                       (if (coll? on-success)
                         (re-frame/dispatch on-success)
                         (re-frame/dispatch [on-success (read-string response)]))
                       (re-frame/dispatch [::events/clear-http-failure]))
                     (if on-error
                       (if (coll? on-error)
                         (re-frame/dispatch on-error)
                         (re-frame/dispatch [on-error]))
                       (re-frame/dispatch [::events/register-http-failure
                                           {:last-error-code (.getLastErrorCode target)
                                            :status (.getStatus target)
                                            :status-text (.getStatusText target)
                                            :response response
                                            :content-type (.getResponseHeader target "Content-Type")}])))))
               (or method "GET")
               body
               (clj->js (merge {:accept "application/edn"} headers)))))

;; Redirect the browser to the specified location.
(re-frame/reg-fx
 :redirect
 (fn [location]
   (set! (. js/window -location) location)))

