(ns kondoq.server.analysis
  "Analyze Clojure(Script) source code for var usages using clj-kondo."
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.java.io :as io]))

(defn- usage-context
  "Select the context (for instance its containing defn) of a var usage at
  `line-no` from` a sequence of `contexts` specified as [start end (inclusive)]
  line number ranges in the source code file.
  If multiple contexts match, pick the largest one."
  [line-no contexts]
  (when-let [[from to] (->> contexts
                            (filter (fn [[start end]] (<= start line-no end)))
                            (sort (fn [[a-start a-end] [b-start b-end]]
                                    (- (- b-end b-start) (- a-end a-start))))
                            (first))]
    [from to]))

(defn- path->language
  "Determine the language of a source file using `path`.
  Cljc or unrecognized extensions have the :clj language, returns nil if the path
  does not have an extension."
  [path]
  (when-let [suffix (re-find #"\.([^.]+)$" path)]
    (condp = (second suffix)
      "clj" :clj
      "cljs" :cljs
      :clj)))

;; Some var-usages don't have a :to entry (e.g. in the case of using a "js/*" var)
(defn- valid-var-usage? [usage]
  (every? #(get usage %) [:to :name :from :row :col :end-row]))

(defn- test-namespace? [ns]
  (and (some? ns) (some? (re-find #"(-|_)test$" (name ns)))))

(defn- read-source-lines
  "Read the source code file at `path` as a vector of lines."
  [path]
  (with-open [r (-> path
                    io/file
                    io/reader)]
    (-> r
        line-seq
        vec)))

(defn analyze
  "Analyze the source file at `path` using clj-kondo, returning a map of
  [namespace, usages, source-lines].
  Assumes one namespace per file."
  [path]
  (let [{{:keys [var-definitions namespace-definitions var-usages]} :analysis}
        (clj-kondo/run! {:lint [path]
                         :config {:analysis true
                                  :skip-lint true}})
        var-definition-contexts (map (fn [{:keys [row end-row]}]
                                       [row end-row])
                                     var-definitions)
        var-usages-contexts (->> var-usages
                                 (filter valid-var-usage?)
                                 (map (fn [{:keys [row name-row end-row]}]
                                        [(or name-row row) end-row])))
        source-lines (read-source-lines path)
        namespace (-> namespace-definitions
                      first
                      :name)
        language (path->language path)
        usages(->> var-usages
                   (filter valid-var-usage?)
                   (map (fn [{:keys [to name from row col arity]}]
                          (let [[start-context end-context]
                                (or (usage-context row var-definition-contexts)
                                    (usage-context row var-usages-contexts))]
                            {:symbol (symbol (clojure.core/name to) (clojure.core/name name))
                             :arity arity
                             :used-in-ns from
                             :language language
                             :line-no row
                             :column-no col
                             :start-context start-context
                             :end-context end-context})))
                   ;; Parsing a .cljc file gives two entries for each usage,
                   ;; which are usually the same for non-core vars.
                   (distinct))]
    {:namespace namespace
     :language language
     :test-namespace (test-namespace? namespace)
     :usages usages
     :source-lines source-lines}))

(comment

  (read-source-lines "/home/pieter/projects/Clojure/frontpage/frontpage-re-frame/src/cljs/frontpage_re_frame/handlers/core.cljs")
  (analyze "/home/pieter/projects/Clojure/frontpage/frontpage-re-frame/src/cljs/frontpage_re_frame/handlers/core.cljs")
  (analyze "/home/pieter/projects/Clojure/kafka-streams/src/kafka_streams/aggregate.clj")
  (str 'clojure.core/inc)
  (def l(->> "src/kondoq/util.cljs"
             io/file
             io/reader
             line-seq
             vec))

  (clj-kondo/run! {:lint ["src/kondoq/client/views.cljs"]
                   :config {:analysis true
                            :skip-lint true}})

  (re-find #"(-|_)test\.(clj|cljc|cljs)$" "a-test.clj")
  (test-namespace? 'a-test)

  (path->language "abc/d")
  )
