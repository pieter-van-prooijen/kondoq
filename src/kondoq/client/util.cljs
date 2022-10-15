(ns kondoq.client.util
  (:require [clojure.string :as string]
            [re-frame.core]))

;; Various utility functions for the client.

(def ^:private intl-number-format (js/Intl.NumberFormat.))
(defn format-number [x]
  (.format intl-number-format x))

;; Shortcuts from the re-frame documentation.
(def <sub (comp deref re-frame.core/subscribe))

(def >evt re-frame.core/dispatch)

;; Generate a unique key for a var usage.
(defn usage-key [{:keys [ns line-no column-no]}]
  (str ns "-" line-no "-" column-no))

;; Add (zero based) line numbers and the highlight the current line.
;; Assumes highlight.js leaves the line structure intact after processing.
(defn add-line-markup [highlighted-html begin current]
  (->> (string/split highlighted-html #"[\n\r]")
       (map  (fn [line-no line]
               (->> (if (= line-no current)
                      (str "<span class=\"current-line\">" line "</span>")
                      line)
                    (str "<span class=\"line-number\">" line-no " </span>")))
             (range begin js/Number.MAX_SAFE_INTEGER))
       (string/join "\n")))

(defn split-with-substr
  "Split string `s` in three parts: before `substr`, `substr` itself  and
  after `substr`."
  [s substr]
  (let [re (re-pattern (str "^(.*)(" substr ")(.*)$"))
        [all before middle after] (re-find re s)]
    (if all
      [before middle after]
      [s "" ""]))) ; no match

(defn text-selected?
  "Answer if there is a text selection active in the main window."
  []
  (not (string/blank? (.toString (.getSelection js/window)))))

(comment

  (add-line-markup "one\ntwo\n" 1 2)

  (split-with-substr "abc" "d")

  )
