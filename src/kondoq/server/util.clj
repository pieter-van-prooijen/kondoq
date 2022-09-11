(ns kondoq.server.util
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:import [java.util Base64]
           [java.security SecureRandom]))

(defn random-string
  "Generate a url-safe base64 string representation of an array of `nof-bytes`
  random bytes.
  `nof-bytes` Must be a multiple of 6 bits to avoid padding."
  [nof-bytes]
  (let [bytes (byte-array nof-bytes)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defn- read-config*
  "Read and returnthe edn file specified by the 'config' system property or
  environment variable.
  Returns an empty config if the property is not specified.
  Throws an exception if the file cannot be opened or parsed."
  []
  (let [path (or (System/getProperty "config") (System/getenv "config"))]
    (if (string/blank? path)
      (do
        (log/warn "Property or environment variable 'config' is not specified, no oauth config is used")
        {})
      (if-let [config (read-string (slurp path))]
        (assoc config :path path) ; add where this config was loaded from
        (throw (ex-info "Config file cannot be read or parsed." {:path path}))))))

(def read-config (memoize read-config*))

(comment

  (random-string 15)

  (read-config)
  )
