(ns kondoq.analysis
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.java.io :as io]
            [clojure.string :as string]))

;; inclusive range, 1- based (as is clj-kondo)
(defn extract-lines [lines from to]
  (->> lines
       (drop (dec from))
       (take (inc (- to from)))
       (string/join "\n")))

(defn extract-context [lines line ranges]
  (when-let [[from to] (->> ranges
                            (filter (fn [[start end]] (<= start line end)))
                            (first))]
    [from (extract-lines lines from to)]))

;; some usages don't have a :to entry (e.g. in case of "js/.." vars)
(defn valid-var-usage? [usage]
  (every? (partial get usage) [:to :name :from :row]))

;; analyze the source file at path, returning a namespace / occurrences tupple
(defn analyze [path]
  (let [{analysis :analysis} (clj-kondo/run! {:lint [path]
                                              :config {:analysis true
                                                       :skip-lint true}})
        var-definition-ranges (map (fn [{:keys [row end-row]}]
                                     [row end-row])
                                   (:var-definitions analysis))
        lines (->> path
                   io/file
                   io/reader
                   line-seq
                   vec)
        namespace (-> (:namespace-definitions analysis)
                      first
                      :name)
        occurrences (->> (:var-usages analysis)
                         (filter valid-var-usage?)
                         (map (fn [{:keys [:to :name :from :row]}]
                                (let [[start-context context]
                                      (extract-context lines row var-definition-ranges)]
                                  {:symbol (symbol (clojure.core/name to) (clojure.core/name name))
                                   :ns from
                                   :line-no row
                                   :line (extract-lines lines row row)
                                   :start-context start-context
                                   :context context}))))]
    [namespace occurrences]))

(comment

  (analyze "src/kondoq/views.cljs")

  (str 'clojure.core/inc)
  (def l(->> "src/kondoq/util.cljs"
             io/file
             io/reader
             line-seq
             vec))

  (extract-lines l 6 8)
  (clj-kondo/run! {:lint ["src/kondoq/views.cljs"]
                   :config {:analysis true
                            :skip-lint true}})
  )
