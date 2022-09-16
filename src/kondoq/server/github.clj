(ns kondoq.server.github
  (:require [clj-http.client :as client]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [kondoq.server.analysis :as analysis]
            [kondoq.server.database :as db]
            [kondoq.server.etag :refer [get-etag-body insert-etag-body]]
            [kondoq.server.project-status :as project-status]
            [lambdaisland.uri :refer [uri assoc-query]]
            [next.jdbc :as jdbc]
            [next.jdbc.transaction])
  (:import java.util.Base64))

;; Fetch a github resource with authentication and etag caching
(defn- fetch-github-resource [etag-db url token]
  (let [[cached-etag cached-body] (get-etag-body etag-db url)
        response (-> (client/get url
                                 {:accept "application/vnd.github.v3+json"
                                  :debug false
                                  :headers
                                  ;; GitHub doesn't use "Bearer" ?
                                  (merge (when-not (string/blank? token)
                                           {:authorization (str "token " token)})
                                         (when cached-etag
                                           {:if-none-match cached-etag}))}))
        etag (get-in response [:headers "Etag"])
        body (if (and (= (:status response) 304) cached-body)
               (do
                 (log/info "github url already cached" url)
                 (edn/read-string cached-body))
               (-> response
                   :body
                   (json/read-value (json/object-mapper {:decode-key-fn true}))))]
    (if (:truncated body)
      (throw (ex-info "too many items in the response" {:url url}))
      (do
        (when (not= (:status response) 304)
          (log/info "inserting body of url with etag" url etag)
          (insert-etag-body etag-db url etag body))
        body))))

;; List of source file name patterns which should not be included.
(defn- skip-blob? [project-name {:keys [path]}]
  (or
   (#{"project.clj"} path)
   ;; Clj-kondo has some duplicate namespaces.
   (and (= project-name "clj-kondo")
        (or (string/includes? path "inlined")
            (string/includes? path "corpus")))
   ;; Reitit examples cause duplicate namespace errors
   (and (= project-name "reitit")
        (string/includes? path "examples"))))

;; List of file name / blob-url / display-url maps.
(defn- fetch-clojure-source-files [etag-db user project-name branch token]
  (let [tree-url (str "https://api.github.com/repos/"
                      user "/" project-name
                      "/git/trees/"
                      branch
                      "?recursive=true")
        body (fetch-github-resource etag-db tree-url token)]
    (->> (:tree body)
         (filter #(re-find #"(?:clj|cljs|cljc)$" (:path %)))
         (remove (partial skip-blob? project-name))
         (map (fn [{:keys [path url sha]}]
                {:blob-url url
                 :display-url (str "https://github.com/"
                                   user "/" project-name
                                   "/blob/"
                                   branch  ; Use sha instead?
                                   "/" path)
                 :sha sha})))))

(defn- fetch-repo-info [etag-db user project-name token]
  (let [repo-url (str "https://api.github.com/repos/" user "/" project-name)
        body (fetch-github-resource etag-db repo-url token)]
    {:nof-stars (:stargazers_count body)
     :default-branch (:default_branch body)}))

;; Insert/analyze a file (git blob) of a project.
;; Note that clj-kondo requires the extension of a file to determine the
;; language to analyze.
(defn- insert-git-blob [db project ^String blob encoding sha display-url]
  (when (= encoding "base64")
    (log/info "inserting file " display-url)
    ;; clj-kondo only handles file paths as input
    (let [extension (re-find #"\.[^.]+$" display-url)
          file (java.io.File/createTempFile (str "kondoq-" sha) extension)]
      (try
        (with-open [w (io/output-stream file)]
          (.write w (.decode (Base64/getMimeDecoder) blob)))
        (let [namespace-analysis (analysis/analyze (.getAbsolutePath file))]
          (db/insert-namespace db namespace-analysis project display-url))
        (finally
          (.delete file))))))

(defn upsert-project
  "Add the var usages of the GitHub project at `project-url` to the database
  at `db-arg`, using an optional `token` for access and `etag-db` for source file
  caching.
  A project is added in a single transaction, any errors will rollback
  all related changes."
  [db-arg etag-db project-url token]
  (try
    (jdbc/with-transaction [db db-arg]
      (let [[user project] (take-last 2 (string/split project-url #"/"))
            {:keys [default-branch nof-stars]}
            (fetch-repo-info etag-db user project token)
            source-files (fetch-clojure-source-files etag-db user project
                                                     default-branch token)
            ns-total (count source-files)]
        (db/delete-project db project)
        (db/insert-project db project project-url nof-stars)
        (doseq [[{:keys [blob-url sha display-url]} index]
                (map vector source-files (range ns-total))]
          (let [{:keys [content encoding]}
                (fetch-github-resource etag-db blob-url token)]
            (insert-git-blob db project content encoding sha display-url)
            (project-status/update-project-status project-url project (inc index) ns-total
                                                  display-url)
            (when (Thread/interrupted)
              (throw (InterruptedException. "upsert-project cancelled")))))))
    (catch Throwable t
      ;; Don't report cancels instigated by the user.
      (when-not (instance? InterruptedException t)
        (log/warn t "upsert-project saw exception:")
        (project-status/update-project-with-error project-url (.getMessage t))))
    (finally
      ;; Keep the project status around in case an error happened.
      (let [{error :error} (project-status/fetch-project-status project-url)]
        (when-not error
          (project-status/delete-project-status project-url))))))

(defn oauth-authorize-url [state config]
  (let [{:keys [github-client-id github-callback-uri]} config]
    (-> (uri "https://github.com/login/oauth/authorize")
        (assoc-query
         "client_id" github-client-id
         "redirect_uri" github-callback-uri
         "state" state)
        str)))

;; Return a token for the given temporary code when logging into github.
(defn oauth-exchange-token [code config]
  (let [{:keys [github-client-id github-client-secret]} config
        response (client/post "https://github.com/login/oauth/access_token"
                              {:form-params {:client_id github-client-id
                                             :client_secret github-client-secret
                                             :code code}
                               :accept "application/vnd.github.v3+json"})]
    (-> response
        :body
        (json/read-value (json/object-mapper {:decode-key-fn true}))
        :access_token)))


(comment

  (defn- db [] (:kondoq/db integrant.repl.state/system))
  (defn- etag-db [] (:kondoq/etag-db integrant.repl.state/system))

  (def token (.strip (slurp "github-token.txt")))

  (db/delete-project (db) "kafka-streams-clojure")
  (fetch-clojure-source-files (etag-db) "pieter-van-prooijen" "frontpage" token)
  (upsert-project (db) (etag-db) "https://github.com/pieter-van-prooijen/kafka-streams-clojure" token)
  (upsert-project (db) (etag-db) "https://github.com/pieter-van-prooijen/frontpage" token)

  (re-find #"\.[^.]+$" "foo.cljs")

  (def base64 (slurp "test.base64"))
  (String. (.decode (Base64/getMimeDecoder) base64))

  )
