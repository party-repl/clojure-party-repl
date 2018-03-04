(ns clojure-repl.execution
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [clojure-repl.repl :as repl]
            [clojure-repl.common :as common :refer [execute-comment
                                                    append-to-editor
                                                    console-log]]))

(def ashell (node/require "atom"))

(defn execute [code & [options]]
  (repl/execute-code code options))

(defn inside-string-or-comment?
  "Checks if the buffer position is inside the scope of string, comment, or
  regex."
  [editor position]
  (let [scopes (.-scopes (.scopeDescriptorForBufferPosition editor position))]
    (or (>= (.indexOf scopes "string.quoted.double.clojure") 0)
        (>= (.indexOf scopes "comment.line.semicolon.clojure") 0)
        (>= (.indexOf scopes "string.regexp.clojure") 0))))

(defn find-all-namespace-declarations
  "Searches through the entire buffer for all namespace declarations and
  collects the ranges."
  [editor range]
  (let [ranges (atom [])
        regex (js/RegExp. "\\s*\\(\\s*ns\\s*([A-Za-z\\*\\+\\!\\-\\_\\'\\?]?[A-Za-z0-9\\.\\*\\+\\!\\-\\_\\'\\?\\:]*)" "gm")]
    (.backwardsScanInBufferRange editor
                                 regex
                                 range (fn [result]
                                         (when-not (inside-string-or-comment? editor (.-start (.-range result)))
                                           (let [match-string (str (second (.-match result)))]
                                             (swap! ranges conj [(.-start (.-range result)) match-string])))))
    (console-log "Namespaces " @ranges)
    @ranges))

;; TODO: Warn user if the namespace isn't declared in the repl. Currently,
;;       repl simply won't return any results when we send code to undeclared
;;       namespaces.
(defn find-namespace-for-range
  "Finds a namespace where the code range is declared at."
  [editor range]
  (let [search-range ((.-Range ashell) 0 (.-start range))
        namespaces (find-all-namespace-declarations editor search-range)]
    (some (fn [[point namespace]]
            (console-log "Namespace " namespace " " (.isGreaterThan (.-start range) point))
            (when (.isGreaterThan (.-start range) point)
              namespace))
          namespaces)))

(defn execute-selected-text
  "Gets the selected text in the editor and sends it over to repl."
  [editor]
  (let [selected-range (.getSelectedBufferRange editor)
        namespace (find-namespace-for-range editor selected-range)]
    (execute (.getSelectedText editor) (when namespace {:ns namespace}))))

(defn find-range-with-cursor
  "Searches for a range that cursor is located at."
  [ranges cursor]
  (some #(when (.containsPoint % cursor)
           %)
        ranges))

(def open-parans #{"(" "[" "{"})
(def close-parans #{")" "]" "}"})

(defn get-all-top-level-ranges
  "Collects all the ranges of top level forms in the editor."
  [editor]
  (let [ranges (atom [])
        paran-count (atom 0)
        regex (js/RegExp. "[\\{\\}\\[\\]\\(\\)]" "gm")]
    (.scan editor regex (fn [result]
                          (when-not (inside-string-or-comment? editor (.-start (.-range result)))
                            (let [match-string (str (first (.-match result)))]
                              (if (contains? open-parans match-string)
                                (do
                                  (when (= @paran-count 0)
                                    (swap! ranges conj [(.-start (.-range result))]))
                                  (swap! paran-count inc))
                                (when (contains? close-parans match-string)
                                  (swap! paran-count dec)
                                  (when (= @paran-count 0)
                                    (swap! ranges update (dec (count @ranges)) #(conj % (.-end (.-range result)))))))))))

    (swap! ranges (partial filter #(= 2 (count %))))
    (swap! ranges (partial map #(apply (.-Range ashell) %)))
    @ranges))

(defn execute-top-level-form
  "Gets the range of the top level form where the cursor is located at and sends
  the text over to repl."
  [editor]
  (let [ranges (get-all-top-level-ranges editor)
        cursor (.getCursorBufferPosition editor)]
    (when-let [range (find-range-with-cursor ranges cursor)]
      (let [namespace (find-namespace-for-range editor range)
            code (string/trim (.getTextInBufferRange editor range))]
        (execute code (when namespace {:ns namespace}))))))

(defn execute-entered-text
  "Gets the text in the input editor and sends it over to repl."
  [editor]
  (let [buffer (.getBuffer editor)
        code (string/replace (.getText buffer) execute-comment "")]
    (execute code)
    (.setText editor "")))

(defn prepare-to-execute
  "The execute-comment is entered, only by the guest side, to signal the host
  side to execute the code."
  [editor]
  (append-to-editor editor execute-comment :add-newline? false))
