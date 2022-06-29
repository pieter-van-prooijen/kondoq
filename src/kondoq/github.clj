(ns kondoq.github
  (:require [clj-http.client :as client]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [kondoq.analysis]
            [kondoq.database :as db]
            [kondoq.etag :refer [get-etag-body insert-etag-body]]
            [next.jdbc :as jdbc]
            [next.jdbc.transaction])
  (:import java.util.Base64))

;; - use github tokens (either oauth or basic auth:
;; - implement webflow in app to allow repo submission from a form and fetch
;; the data using the user's github access?

;; Fetch a github resource with authentication and etag caching
(defn fetch-github-resource [etag-db url token]
  (let [[cached-etag cached-body] (get-etag-body etag-db url)
        response (-> (client/get url
                                 {:accept "application/vnd.github.v3+json"
                                  :debug false
                                  :headers
                                  ;; personal access tokens have non-standard "token" scheme?
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
          (insert-etag-body etag-db url etag body))
        body))))

;; list of file name / blob-url / display-url maps
(defn fetch-clojure-source-files [etag-db user project-name token]
  (let [tree-url (str "https://api.github.com/repos/"
                      user "/" project-name
                      "/git/trees/master?recursive=true")
        body (fetch-github-resource etag-db tree-url token)]
    (->> (:tree body)
         (filter #(re-find #"(?:clj|cljs|cljc)$" (:path %)))
         (remove #(#{"project.clj"} (:path %)))
         (map (fn [{:keys [path url sha]}]
                {:blob-url url
                 :display-url (str "https://github.com/"
                                   user "/" project-name
                                   "/blob/master/" ; use sha?
                                   path)
                 :sha sha})))))

;; insert/analyze a file (git blob) of a project
;; note that clj-kondo needs the extension of a file to determine the
;; language
(defn insert-git-blob [db project ^String blob encoding sha display-url]
  (when (= encoding "base64")
    ;; clj-kondo only handles file paths as input
    (let [extension (re-find #"\.[^.]+$" display-url)
          file (java.io.File/createTempFile (str "kondoq-" sha) extension)]
      (try
        (with-open [w (io/output-stream file)]
          (.write w (.decode (Base64/getMimeDecoder) blob)))
        (db/insert-path db project (.getAbsolutePath file) display-url)
        (finally
          (.delete file))))))

;; Wrap the whole action in a single transaction, so it can be cancelled and
;; rolled-back.
(defn upsert-project [db-arg etag-db project-url token]
  (try
    (jdbc/with-transaction [db db-arg]
      (let [[user project] (take-last 2 (string/split project-url #"/"))
            source-files (fetch-clojure-source-files etag-db user project token)
            ns-total (count source-files)]
        (db/delete-project db project)
        (db/insert-project db project project-url)
        (doseq [[{:keys [blob-url sha display-url]} index]
                (map vector source-files (range ns-total))]
          (let [{:keys [content encoding]} (fetch-github-resource etag-db blob-url token)]
            (insert-git-blob db project content encoding sha display-url)
            (db/update-project-status project-url project (inc index) ns-total)
            (when (Thread/interrupted)
              (throw (InterruptedException. "upsert-project cancelled")))))))
    (finally
      (db/delete-project-status project-url))))

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
