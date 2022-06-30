(ns kondoq.web
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [kondoq.database :as db]
            [kondoq.github :as github]
            [reitit.ring :as rring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [ring.util.response :as resp]))

(def config {:adapter/jetty {:port 3002
                             :db (ig/ref :kondoq/db)
                             :etag-db (ig/ref :kondoq/etag-db)}})

;; From partsbin, pour the request into the component to make the component
;; accessible in down-stream handlers (wrap-component h {:db db}) will return a
;; handler which has the database accessible under :db in the request map
(defn wrap-component [handler component]
  (fn [request]
    (handler (merge request component))))


(declare create-handler)

(defmethod ig/init-key :adapter/jetty [_ {:keys [port db etag-db]}]
  (log/info "starting jetty server on port" port)
  (let [server (ring.adapter.jetty/run-jetty
                (-> (create-handler)
                    keyword-params/wrap-keyword-params
                    params/wrap-params
                    (resource/wrap-resource "public")
                    (wrap-component {:db db})
                    (wrap-component {:etag-db etag-db}))
                ;; :join? => true will block the thread until the server ends
                {:port port :join? false})]
    server))

(defmethod ig/halt-key! :adapter/jetty [_ server]
  (log/info "stopping jetty server" server)
  (.stop server)
  ;; reset will sometimes start the server before it is really stopped?
  (.join server)
  (log/info "stopped jetty server" server))

(defn fetch-projects-namespaces-occurrences-handler [{:keys [db params]}]
  (let [fq-symbol-name (:fq-symbol-name params)
        body (db/fetch-projects-namespaces-occurrences db fq-symbol-name)]
    (-> body
        pr-str
        resp/response
        (resp/content-type "application/edn"))))

(defn fetch-symbol-counts-handler [{:keys [db params]}]
  (let [q (:q params)
        body (if (> (count q) 2)
               (db/search-symbol-counts db (str "%" q "%") 10)
               [])]
    (-> body
        pr-str
        resp/response
        (resp/content-type "application/edn"))))

(defn add-project-handler [{:keys [db etag-db params] :as request}]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])
        token (:token params)
        ;; load in background
        project-future (future (github/upsert-project db etag-db project-url token))]
    (db/init-project-status project-url project-future)
    (resp/created (str "/projects/" project-url))))

(defn get-project-status-handler [request]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])]
    (-> (or (db/fetch-project-status project-url) {})
        (dissoc :future)
        pr-str
        resp/response
        (resp/content-type "application/edn"))))

(defn delete-project-handler [request]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])
        db (:db request)]
    (db/delete-project-by-location db project-url)
    (-> (resp/response (str "/projects/" project-url))
        (resp/status 202))))

(defn cancel-add-project-handler [request]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])
        {project-future :future} (db/fetch-project-status project-url)]
    (if (future? project-future)
      (let [result (.cancel project-future true)]
        (log/info "cancelled upload of project (result, is-cancelled): "
                  project-url result (.isCancelled project-future))
        (-> (resp/response (str "/projects/" project-url))
            (resp/status 202)))
      (resp/not-found project-url))))

(defn create-handler []
  (let [router (rring/router
                [["/occurrences" fetch-projects-namespaces-occurrences-handler]
                 ["/symbol-counts" fetch-symbol-counts-handler]
                 ["/projects/:project-url" {:name :projects ; name is required?
                                            :get get-project-status-handler
                                            :put add-project-handler
                                            :delete delete-project-handler}]
                 ["/projects/:project-url/adding" {:name :projects-adding
                                                   :delete cancel-add-project-handler}]])]
    (rring/ring-handler router)))

(comment

  (def h (create-handler))
  (h {:request-method :get :uri "/projects/https%3A%2F%2Fgithub.com%2Fpieter-van-prooijen%2Fkafka-streams-clojure"})
  (h {:request-method :get :uri "/symbol-counts"})
  (h )
  )
