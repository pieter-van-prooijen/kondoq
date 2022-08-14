(ns kondoq.server.web
  "Web setup, ring handlers and related functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [kondoq.server.database :as db]
            [kondoq.server.github :as github]
            [kondoq.server.project-status :as project-status]
            [kondoq.server.util :as util]
            [reitit.ring :as rring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [ring.util.response :as resp]))

(def config {:adapter/jetty {:port 3002
                             :db (ig/ref :kondoq/db)
                             :etag-db (ig/ref :kondoq/etag-db)}})

(defn- wrap-component
  "(from partsbin) Return a ring handler which makes the `component` map part
  of the ring request so it's accessible by the down-stream `handler`.
  For instance (wrap-component h {:db db}) will return a handler which has the
  database accessible under :db in the request map"
  [handler component]
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
                    (wrap-component {:etag-db etag-db})
                    (wrap-component {:github-oauth-config (util/read-config)}))
                ;; :join? => true will block the thread until the server ends.
                {:port port :join? false})]
    server))

(defmethod ig/halt-key! :adapter/jetty [_ server]
  (log/info "stopping jetty server" server)
  (.stop server)
  ;; Reset will sometimes start the server before it is really stopped?
  (.join server)
  (log/info "stopped jetty server" server))

(defn- coerce-param [x default convert]
  (if (string/blank? x)
    default
    (convert x)))

(defn- edn-response
  ([body]
   (edn-response body 200))
  ([body status]
   (-> body
       pr-str
       resp/response
       (resp/status status)
       (resp/content-type "application/edn")))
  ([error status location]
   (-> (pr-str {:error error})
       resp/response
       (resp/header "Location" location)
       (resp/content-type "application/edn")
       (resp/status status))))

(defn- fetch-namespaces-usages-handler [{:keys [db params]}]
  (let [{:keys [fq-symbol-name arity page page-size]} params
        body (db/search-namespaces-usages db
                                          fq-symbol-name
                                          (coerce-param arity nil parse-long)
                                          (coerce-param page 0 parse-long)
                                          (coerce-param page-size 10 parse-long))]
    (edn-response body)))

;; Assumes running from an uberjar with only a single manifest on the classpath.
(defn- fetch-uberjar-manifest []
  (when-let [clazz (try (Class/forName "kondoq.server.core") ; this ns has a gen-class
                        (catch ClassNotFoundException _))]
    (->> (str "jar:" (-> clazz
                         .getProtectionDomain
                         .getCodeSource
                         .getLocation)
              "!/META-INF/MANIFEST.MF")
         io/input-stream
         java.util.jar.Manifest.
         .getMainAttributes
         (map (fn [[k v]] [(str k) v]))
         (into {}))))

;; The GET to /projects loads both the project list and the manifest.
(defn- fetch-projects-handler [{:keys [db]}]
  (let [projects (db/search-projects db)
        manifest (fetch-uberjar-manifest)
        config-path (:path (util/read-config))]
    (edn-response {:projects projects :manifest manifest :config-path config-path})))

(defn- fetch-symbol-counts-handler [{:keys [db params]}]
  (let [q (:q params)
        symbol-counts (if (>= (count q) 2)
                        (db/search-symbol-counts db (str "%" q "%") 10)
                        [])]
    (edn-response {:symbol-counts-q q :symbol-counts symbol-counts})))

(defn- add-project [db etag-db project-url token]
  (let [project-future (future (github/upsert-project db etag-db project-url token))]
    (log/info "adding project with url and token ending in"
              project-url
              (if token (re-find #".{4}$" token) "(no-token)"))
    (project-status/init-project-status project-url project-future)
    (resp/created (str "/projects/" project-url) "{}")))

;; Two paths when adding a project:
;; 1. With a pre-filled personal token, start loading the project immediately.
;; 2. With no token, create a place holder and redirect to the github login page.
(defn- add-project-handler [{:keys [db etag-db github-oauth-config params] :as request}]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])
        token (:token params)]
    (if-not (string/blank? token)
      (add-project db etag-db project-url token)
      (let [oauth-state (util/random-string 15)]
        (project-status/init-project-status-placeholder project-url oauth-state)
        (log/info "redirecting to github login with state" oauth-state)
        (edn-response {:status :not-authorized
                       :location (github/oauth-authorize-url oauth-state github-oauth-config)}
                      200)))))

(defn- oauth-callback-handler [{:keys [db etag-db github-oauth-config params]}]
  (let [oauth-state (:state params)
        code (:code params)
        location (:location (project-status/fetch-project-status oauth-state))]
    ;; Remove the project placeholder first, whatever happens.
    (project-status/delete-project-status oauth-state)
    (if-let [token (github/oauth-exchange-token code github-oauth-config)]
      (do
        (add-project db etag-db location token)
        ;; Set the state in the frontend to "adding-project"
        (resp/redirect (str "/index.html?adding-project=" location)))
      (edn-response {:error (str "cannot exchange token in oauth callback with state "
                                 oauth-state)}
                    401))))

(defn- get-project-status-handler [request]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])]
    (-> (or (project-status/fetch-project-status project-url) {})
        (dissoc :future)
        (edn-response))))

(defn- delete-project-handler [request]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])
        db (:db request)]
    (db/delete-project-by-location db project-url)
    (log/info "deleted project " project-url)
    (edn-response "" 204)))

(defn- cancel-add-project-handler [request]
  (let [project-url (get-in request [:reitit.core/match :path-params :project-url])
        {project-future :future} (project-status/fetch-project-status project-url)]
    (if (future? project-future)
      (let [result (.cancel project-future true)]
        (log/info "cancelled upload of project (result, is-cancelled): "
                  project-url result (.isCancelled project-future))
        (edn-response {} 202))
      (edn-response {:error project-url} 404))))

(defn- create-handler []
  (let [router (rring/router
                [["/usages" fetch-namespaces-usages-handler]
                 ["/symbol-counts" fetch-symbol-counts-handler]
                 ["/oauth-callback" oauth-callback-handler]
                 ["/projects" fetch-projects-handler]
                 ["/projects/:project-url" {:name :project-crud ; Name is required by reitit?
                                            :get get-project-status-handler
                                            :put add-project-handler
                                            :delete delete-project-handler}]
                 ["/projects/:project-url/adding" {:name :projects-adding
                                                   :delete cancel-add-project-handler}]])]
    (rring/ring-handler router)))

(comment

  (defn- db [] (:kondoq/db integrant.repl.state/system))

  (def h (create-handler))
  (h {:request-method :get :uri "/projects/https%3A%2F%2Fgithub.com%2Fpieter-van-prooijen%2Fkafka-streams-clojure"})
  (h {:request-method :get :uri "/symbol-counts"})
  (h )

  (require 'clj-http.client)
  (clj-http.client/get "http://localhost:8280/usages?fq-symbol-name&arity")
  (fetch-namespaces-usages-handler {:params {:fq-symbol-name "clojure.core/inc"
                                             :arity "-1"}
                                    :db (db)})

  (-> (clj-http.client/get "http://localhost:8280/projects")
      #_(fetch-projects-handler {:db (db)})
      :body
      )
  (clj-http.client/get "http://localhost:8280/projects")

  (:bla nil)
  (Class/forName "kondoq.server.web$fetch_uberjar_manifest")
  (fetch-uberjar-manifest)
  )
