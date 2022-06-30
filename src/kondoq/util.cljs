(ns kondoq.util
  (:require [clojure.string :as string]
            [re-frame.core]))

;; Various utility functions for re-frame

(def intl-number-format (js/Intl.NumberFormat.))
(defn format-number [x]
  (.format intl-number-format x))

;; Shortcuts from the re-frame documentation
(def <sub (comp deref re-frame.core/subscribe))

(def >evt re-frame.core/dispatch)

(defn occurrence-key [{:keys [ns line-no]}]
  (str ns "-" line-no))

;; Add (zero based) line numbers and the highlight the current line
;; Assumes highlight.js leaves the line structure intact after processing
(defn add-line-markup [highlighted-html begin current]
  (->> (string/split highlighted-html #"[\n\r]")
       (map  (fn [line-no line]
               (->> (if (= line-no current)
                      (str "<span class=\"current-line\">" line "</span>")
                      line)
                    (str "<span class=\"line-number\">" line-no " </span>")))
             (range begin js/Number.MAX_SAFE_INTEGER))
       (string/join "\n")))

;; Split a string in three parts using a substring: before, substring and after
(defn split-with-substr [s substr]
  (let [re (re-pattern (str "^(.*)(" substr ")(.*)$"))
        [all before middle after] (re-find re s)]
    (if all
      [before middle after]
      [s "" ""]))) ; no match

(comment

  (add-line-markup "one\ntwo\n" 1 2)

  (split-with-substr "abc" "d")

  )
