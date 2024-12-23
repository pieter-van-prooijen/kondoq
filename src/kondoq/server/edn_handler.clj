(ns kondoq.server.edn-handler
  "Generic HTTP protocol handling for edn formatted request/responses."
  (:require [clojure.edn :as edn]
            [reitit.core :as reitit])
  (:import [java.nio.charset StandardCharsets]))

;; See Malcom Spark's thoughts on yada:
;; https://gist.github.com/malcolmsparks/bcfdcd9ae51e69aa3018c04d48f8749b
;; And https://www.rest.guide

(def router (reitit/router
             [["/usages" ::usages]
              ["/symbol-counts" ::symbol-counts]
              ["/oauth-callback" ::oauth-callback]
              ["/projects" ::fetch-projects]
              ["/projects/:project-url" ::crud-project]
              ["/projects/:project-url/adding" ::cancel-add-project]
              ["/tests/exception" ::tests-exception]]))

;; Endpoint definition:
;; Key is retrieved from the route and used to lookup endpoint characteristics
;; and to use in the multi-method dispatch.

;; HTTP protocol errors use ExceptionInfo exceptions with a map containing at
;; least a :status key with the http status code and a message usable for logging.
(defn- ex-status [code message & args]
  (ex-info (apply format message args) {:status code}))

;; Default values for the endpoint configuration
(def default-endpoint-values {:methods {:get {}}
                              :max-request-content-length (* 64 1024)
                              :content-type "application/edn"})

(defmulti handle-request
  "Handle the ring request, dispatching on an endpoint key and method.
  The `request` contains the following extra keys:
  - :endpoint-key the id of the endpoint to dispatch on.
  - :path-params any parameters derived from the url path, also put in the
    general :params map.
  - :parsed-body the parsed body data in case of an edn request body."
  (fn [request] [(:request-method request) (:endpoint-key request)]))

(defmethod handle-request :default
  [request]
  (throw (ex-status 404 "Endpoint for [%s %s] not implemented"
                    (:request-method request ) (:endpoint-key request))))

(defn- get-supported-content-type [r endpoint]
  (get-in endpoint
          [:methods (:request-method r) :content-type] ; Method specific type
          (:content-type endpoint)))

(defn content-negotiation [r endpoint]
  (let [content-type (get-supported-content-type r endpoint)]
    (when-let [accept (get-in r [:headers "accept"])]
      (when-not (or (= accept "*/*") (= accept content-type))
        (throw (ex-status 406 "Not Acceptable, does not match %s" content-type))))))

(defn check-ranges [r _]
  (when (get-in r [:headers "range"])
    (throw (ex-status 400 "Range header not supported"))))

;; Check if-match etc., reject range headers if needed.
;; There's a strict order of evaluating the If-* headers, see RFC 7232, 6
(defn check-preconditions-for-modify [r _]
  (if-let [if-match (get-in r [:headers "if-match"])]
    ;; Only the wildcard case is handled, which means "no valid representation"
    ;; (and is always if-match = true)
    (when-not (= if-match "*")
      (throw (ex-status 412 "If-Match without wild-card not supported")))
    ;; Alternative for If-Match
    (when (get-in r [:headers "if-unmodified-since"])
      (throw (ex-status 412 "If-Unmodified-Since not supported")))))

(defn check-preconditions-for-read [r _]
  (if-let [if-none-match (get-in r [:headers "if-none-match"])]
    ;; Only the wildcard case is handled, which mean "no valid representation"
    ;; (and is always if-none-match = false)
    (if (= if-none-match "*")
      (throw (ex-status 304 "Not Modified"))
      (throw (ex-status 412 "If-None-Match without wild-card not supported")))
    ;; Alternative for If-None-Match
    (when (get-in r [:headers "if-modified-since"])
      (throw (ex-status 412 "If-Modified-Since is not supported")))))

;; Return the request body as edn or nil if the body was already parsed by
;; a previous handler (e.g. wrap-param).
(defn parse-edn [stream nof-bytes]
  (let [buffer (byte-array nof-bytes)
        nof-read (.read stream buffer)
        payload (String. buffer StandardCharsets/UTF_8)]
    (when (>= nof-read 0) ; -1 means eof seen, body already processed.
      (.close stream)
      (when-not (= nof-read nof-bytes)
        (throw (ex-status 400 "Cannot read all %d bytes in payload, only read %d" nof-bytes nof-read)))
      (edn/read-string payload))))

;; Check / read length, convert to clojure if it's an edn request, else assume
;; an already parsed request
(defn receive-request-representation
  [r {:keys [max-request-content-length] :as endpoint}]
  (let [content-length-header (get-in r [:headers "content-length"])]
    (when-not content-length-header
      (throw (ex-status 411 "Content-Length header required")))
    (let [content-length (parse-long content-length-header)]
      (when (> content-length  max-request-content-length)
        (throw (ex-status 413 "Request body too large (%d > %d)"
                          content-length max-request-content-length)))
      (let [requested-content-type (get-in r
                                           [:headers "content-type"]
                                           "application/octet-stream")
            supported-content-type (get-supported-content-type r endpoint)]
        (when-not (= requested-content-type supported-content-type)
          (throw (ex-status 415 "Unsupported media type %s in request, should be %s"
                            requested-content-type supported-content-type)))
        (parse-edn (:body r) content-length)))))

;; "The only ring handler you'll need"
;; Assumes a router and endpoint keys in the request.
(defn handle [r router endpoints]
  (let [match (reitit/match-by-path router (:uri r))]
    (when-not match
      (throw (ex-status 404 "Endpoint not found for path %s" (:uri r))))
    (let [endpoint-key (get-in match [:data :name])]
      (when-not (contains? endpoints endpoint-key)
        (throw (ex-status 404 "Endpoint with key %s matched but not configured"
                          endpoint-key)))
      (let [endpoint (merge default-endpoint-values (get endpoints endpoint-key))]
        (when-not (contains? (:methods endpoint) (:request-method r))
          (throw (ex-status 405 "Method %s not allowed for endpoint with key %s"
                            (:request-method r) endpoint-key)))
        (let [parsed-body
              (condp contains? (:request-method r)
                #{:get :head} (do
                                (content-negotiation r endpoint)
                                (check-ranges r endpoint)
                                (check-preconditions-for-read r endpoint))
                #{:post :put} (do
                                (check-preconditions-for-modify r endpoint)
                                (receive-request-representation r endpoint))
                #{:delete :head})]
          (-> r
              (assoc :parsed-body parsed-body)
              ;; Add the reitit path params onto the request, merged with the
              ;; regular request params.
              (assoc :path-params (:path-params match))
              (update :params #(merge % (:path-params match)))
              ;; Add the endpoint key for the dispatch.
              (assoc :endpoint-key endpoint-key)
              handle-request))))))
