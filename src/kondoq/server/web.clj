(ns kondoq.server.web
  "Web setup, ring handlers and related functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [kondoq.server.database :as db]
            [kondoq.server.edn-handler :as eh]
            [kondoq.server.github :as github]
            [kondoq.server.project-status :as project-status]
            [kondoq.server.util :as util]
            [reitit.core :as reitit]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as resource]
            [ring.util.response :as resp]))

(def config {:adapter/jetty {:port 3002
                             :db (ig/ref :kondoq/db)
                             :etag-db (ig/ref :kondoq/etag-db)}})

(def endpoints {::usages {}
                ::symbol-counts {}
                ::oauth-callback {}
                ::fetch-projects {}
                ::crud-project {:methods {:get {}
                                          :put {:content-type "application/x-www-form-urlencoded"}
                                          :delete {}}}
                ::cancel-add-project {:methods {:delete {}}}
                ::tests-exception {}
                ::tests-echo {:methods {:post {}
                                        :put {}}}})

(def router (reitit/router
             [["/usages" ::usages]
              ["/symbol-counts" ::symbol-counts]
              ["/oauth-callback" ::oauth-callback]
              ["/projects" ::fetch-projects]
              ["/projects/:project-url" ::crud-project]
              ["/projects/:project-url/adding" ::cancel-add-project]
              ["/tests/exception" ::tests-exception]
              ["/tests/echo" ::tests-echo]]))

(defn- edn-response
  ([body]
   (edn-response body 200))
  ([body status]
   (-> body
       pr-str
       resp/response
       (resp/status status)
       (resp/content-type "application/edn"))))

(defn- error-response [status message]
  (edn-response {:message message} status))

(defn- wrap-component
  "(from partsbin) Return a ring handler which makes the `component` map part
  of the ring request so it's accessible by the down-stream `handler`.
  For instance (wrap-component h {:db db}) will return a handler which has the
  database accessible under :db in the request map"
  [handler component]
  (fn wrap-component [request]
    (handler (merge request component))))

(defn- wrap-handle-exception
  "ExceptionInfo thrown in a handler should have a :status entry."
  [h]
  (fn [r]
    (try
      (h r)
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [status] :or {status 500}} (ex-data e)]
          (log/info (.getMessage e))
          (error-response status (.getMessage e))))
      (catch java.lang.Throwable t
        (log/error "unhandled exception" t)
        (error-response 500 (.getMessage t))))))

(defmethod ig/init-key :adapter/jetty [_ {:keys [port db etag-db]}]
  (log/info "starting jetty server on port" port)
  (let [server (ring.adapter.jetty/run-jetty
                (-> (fn [{:keys [router endpoints] :as r}]
                      (eh/handle r router endpoints))
                    keyword-params/wrap-keyword-params
                    params/wrap-params
                    (resource/wrap-resource "public")
                    (wrap-component {:db db
                                     :etag-db etag-db
                                     :github-oauth-config (util/read-config)
                                     :router router
                                     :endpoints endpoints})
                    (wrap-handle-exception))
                ;; :join? => true will block the thread until the server ends.
                {:port port :join? false})]
    server))

(defmethod ig/halt-key! :adapter/jetty [_ server]
  (log/info "stopping jetty server" server)
  (.stop server)
  ;; Reset will sometimes start the server before it is really stopped?
  (.join server)
  (log/info "stopped jetty server" server))

(defn- coerce-param
  "Coerce the request parameter string `s` to its correct type using `convert-fn`.
   A blank or missing parameter reverts to the `default` value."
  [s default convert-fn]
  (if (string/blank? s)
    default
    (convert-fn s)))

(defmethod eh/handle-request [:get ::usages] [{:keys [db params]}]
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
(defmethod eh/handle-request [:get ::fetch-projects]
  [{:keys [db]}]
  (let [projects (db/search-projects db)
        manifest (fetch-uberjar-manifest)
        config-path (:path (util/read-config))]
    (edn-response {:projects projects :manifest manifest :config-path config-path})))

(defmethod eh/handle-request [:get ::symbol-counts]
  [{:keys [db params]}]
  (let [{:keys [q request-no]} params
        symbol-counts (if (>= (count q) 2)
                        (db/search-symbol-counts db (str "%" q "%") 10)
                        [])]
    (edn-response {:symbol-counts-q q
                   :symbol-counts symbol-counts
                   :request-no request-no})))

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
(defmethod eh/handle-request [:put ::crud-project]
  [{:keys [db etag-db github-oauth-config params]}]
  (let [project-url (:project-url params)
        token (:token params)]
    (if-not (string/blank? token)
      (add-project db etag-db project-url token)
      (let [oauth-state (util/random-string 15)]
        (project-status/init-project-status-placeholder project-url oauth-state)
        (log/info "redirecting to github login with state" oauth-state)
        (edn-response {:status :not-authorized
                       :location (github/oauth-authorize-url oauth-state github-oauth-config)})))))

(defmethod eh/handle-request [:get ::crud-project]
  [request]
  (let [project-url (get-in request [:params :project-url])]
    (-> (or (project-status/fetch-project-status project-url) {})
        (dissoc :future)
        (edn-response))))

(defmethod eh/handle-request [:delete ::crud-project]
  [request]
  (let [project-url (get-in request [:params :project-url])
        db (:db request)]
    (db/delete-project-by-location db project-url)
    (log/info "deleted project " project-url)
    (edn-response "" 204)))

(defmethod eh/handle-request [:get ::oauth-callback]
  [{:keys [db etag-db github-oauth-config params]}]
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
      (error-response 401 (str "cannot exchange token in oauth callback with state "
                               oauth-state)))))

(defmethod eh/handle-request [:delete ::cancel-add-project]
  [request]
  (let [project-url (get-in request [:params :project-url])
        {project-future :future} (project-status/fetch-project-status project-url)]
    (if (future? project-future)
      (let [result (.cancel project-future true)]
        (log/info "cancelled upload of project (result, is-cancelled): "
                  project-url result (.isCancelled project-future))
        (edn-response {} 202))
      (error-response 404 project-url))))

(defmethod eh/handle-request [:get ::tests-exception]
  [request]
  (throw (ex-info (str "test exception for path " (:uri request))
                  {:status 500})))

(defmethod eh/handle-request [:put ::tests-echo]
  [request]
  (edn-response (:parsed-body request) 200))

(defn server-port [system]
  (-> system
      (:adapter/jetty system)
      (.getConnectors)
      (aget 0)
      (.getLocalPort)))

(comment

  (defn- db [] (:kondoq/db integrant.repl.state/system))

  (server-port integrant.repl.state/system)

  (require 'clj-http.client)
  (clj-http.client/get "http://localhost:8280/usages?fq-symbol-name&arity")
  (h/handle-request {:request-method :get
                     :endpoint-key ::symbol-counts1
                     :params {:fq-symbol-name "clojure.core/inc"
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
