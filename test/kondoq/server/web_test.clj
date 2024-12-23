(ns kondoq.server.web-test
  (:require [clj-http.client :as client]
            [clojure.string :as string]
            [clojure.test :as t :refer [deftest is]]
            [kondoq.server.test-utils :as tu]))

(t/use-fixtures :once tu/system-fixture)
(t/use-fixtures :each tu/database-fixture)

(defn- http-get
  ([path]
   (http-get path {}))
  ([path options]
   (try
     (let [resp (client/get
                 (str tu/*base-url* path)
                 (merge {:accept "application/edn"} options))
           result {:status (:status resp)}]
       (if-let [body (:body resp)]
         (assoc  result :body (read-string body))
         result))
     (catch clojure.lang.ExceptionInfo e
       (let [resp (ex-data e)]
         {:status (:status resp)
          :body (read-string (:body resp))})))))

(defn- http-put
  ([path body]
   (http-put path body {}))
  ([path body options]
   (try
     (let [resp (client/put
                 (str tu/*base-url* path)
                 (merge {:accept "application/edn"
                         :content-type "application/edn"
                         :body (pr-str body)}
                        options))]
       (select-keys resp [:status :body]))
     (catch clojure.lang.ExceptionInfo e
       (let [resp (ex-data e)]
         {:status (:status resp)
          :body (read-string (:body resp))})))))

(defn- http-date []
  ;; RFC-1132 does not have correct formatting for days < 10 for rfc
  ;; (single digit only)?
  (.format java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(deftest should-respond-with-not-found-or-not-allowed
  (let [{:keys [status body]} (http-get "/bla/foo")
        message (:message body)]
    (is (= 404 status))
    (is (string/includes? message "not found for path" )))
  (let [{:keys [status body]} (http-get "/tests/echo")
        message (:message body)]
    (is (= 405 status))
    (is (string/includes? message "not allowed"))))

(deftest should-negotiate-content
  (let [{:keys [status body]} (http-get "/projects" {:accept "text/plain"})
        message (:message body)]
    (is (= 406 status))
    (is (string/includes? message "does not match application/edn" )))
  (let [{:keys [status]} (http-get "/projects" {:accept "*/*"})]
    (is (= 200 status))))

(deftest should-check-read-preconditions
  (let [{:keys [status body]} (http-get "/projects"
                                        {:accept "application/edn"
                                         :headers {"If-None-Match" "some-tag"}})
        message (:message body)]
    (is (= 412 status))
    (is (string/includes? message "not supported" )))
  (let [{:keys [status]} (http-get "/projects"
                                   {:accept "application/edn"
                                    :headers {"If-None-Match" "*"}})]
    (is (= 304 status)))
  (let [{:keys [status body]} (http-get "/projects"
                                        {:accept "application/edn"
                                         :headers {"If-Modified-Since" (http-date)}})
        message (:message body)]
    (is (= 412 status))
    (is (string/includes? message "not supported" ))))

(deftest should-check-write-preconditions
  (let [{:keys [status body]} (http-put "/tests/echo" {}
                                        {:headers {"If-Match" "some-tag"}})
        message (:message body)]
    (is (= 412 status))
    (is (string/includes? message "not supported" )))
  (let [payload {:bar :foo}
        {:keys [status body]} (http-put "/tests/echo" payload
                                        {:headers {"If-Match" "*"}})]
    (is (= 200 status))
    (is (= payload (read-string body))))
  (let [{:keys [status body]} (http-put "/tests/echo" {:foo :bar}
                                        {:headers {"If-Unmodified-Since" (http-date)}})
        message (:message body)]
    (is (= 412 status))
    (is (string/includes? message "not supported" ))))

(deftest should-handle-exceptions
  (let [resp (http-get "/projects")]
    (is (= 200 (:status resp)))
    (is (contains? (:body resp) :manifest)))
  (let [resp (http-get "/tests/exception")]
    (is (= 500 (:status resp)))
    (is (string/includes? (get-in resp [:body :message]) "/tests/exception"))))

;; TODO: test wrong or missing content-length header, not possible with
;; the apache client?
(deftest should-receive-request-representation
  (let [payload {:my :payload}
        resp (http-put "/tests/echo" payload)]
    (is (= 200 (:status resp)))
    (is (= payload (read-string (:body resp)))))
  (let [payload {:my :payload}
        resp (http-put "/tests/echo"
                       payload
                       {:content-type "text/plain"})]
    (is (= 415 (:status resp))))
  (let [resp (http-put "/tests/echo"
                       (string/join (repeat (* 65 1024) "X")))] ; Limit is 64KB
    (is (= 413 (:status resp)))
    (is (string/includes? (get-in resp [:body :message]) "body too large"))))

