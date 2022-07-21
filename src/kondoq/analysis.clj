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
                            ;; if multiple contexts match, use the largest
                            (sort (fn [[a-start a-end] [b-start b-end]]
                                    (- (- b-end b-start) (- a-end a-start))))
                            (first))]
    [from to (extract-lines lines from to)]))

;; some usages don't have a :to entry (e.g. in case of "js/.." vars)
(defn valid-var-usage? [usage]
  (every? (partial get usage) [:to :name :from :row :col]))

;; analyze the source file at path, returning a namespace / usages / source-lines tupple
(defn analyze [path]
  (let [{analysis :analysis} (clj-kondo/run! {:lint [path]
                                              :config {:analysis true
                                                       :skip-lint true}})
        var-definition-ranges (map (fn [{:keys [row end-row]}]
                                     [row end-row])
                                   (:var-definitions analysis))
        var-usages-ranges (->> (:var-usages analysis)
                               (filter valid-var-usage?)
                               (map (fn [{:keys [row name-row end-row]}]
                                      [(or name-row row) end-row])))
        lines (->> path
                   io/file
                   io/reader
                   line-seq
                   vec)
        namespace (-> (:namespace-definitions analysis)
                      first
                      :name)
        usages(->> (:var-usages analysis)
                   (filter valid-var-usage?)
                   (map (fn [{:keys [to name from row col arity]}]
                          (let [[start-context end-context _]
                                (or (extract-context lines row var-definition-ranges)
                                    (extract-context lines row var-usages-ranges))]
                            {:symbol (symbol (clojure.core/name to) (clojure.core/name name))
                             :arity arity
                             :used-in-ns from
                             :line-no row
                             :column-no col
                             :start-context start-context
                             :end-context end-context})))
                   ;; parsing a cljc file will give two entries for each usage,
                   ;; which are usually the same
                   (distinct))] 
    [namespace usages lines]))

(comment

  (analyze "/home/pieter/projects/Clojure/frontpage/frontpage-re-frame/src/cljs/frontpage_re_frame/handlers/core.cljs")
  (analyze "/home/pieter/projects/Clojure/kafka-streams/src/kafka_streams/aggregate.clj")
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
