(ns clojure-repl.execution
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [clojure-repl.repl :as repl]
            [clojure-repl.common :as common :refer [execute-comment
                                                    append-to-editor
                                                    console-log
                                                    show-error
                                                    repls
                                                    visible-repl?]]
            [cljs.core.async :as async :refer [timeout <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def node-atom (node/require "atom"))

(defn execute [project-name code & [options]]
  (repl/execute-code project-name code options))

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
  (let [ranges (transient [])
        regex (js/RegExp. "\\s*\\(\\s*ns\\s*([A-Za-z\\*\\+\\!\\-\\_\\'\\?]?[A-Za-z0-9\\.\\*\\+\\!\\-\\_\\'\\?\\:]*)" "gm")]
    (.backwardsScanInBufferRange editor
                                 regex
                                 range (fn [result]
                                         (when-not (inside-string-or-comment? editor (.-start (.-range result)))
                                           (let [match-string (str (second (.-match result)))]
                                             (conj! ranges [(.-start (.-range result)) match-string])))))
    (console-log "Namespaces " ranges)
    (persistent! ranges)))

;; TODO: Warn user if the namespace isn't declared in the repl. Currently,
;;       repl simply won't return any results when we send code to undeclared
;;       namespaces.
(defn find-namespace-for-range
  "Finds a namespace where the code range is declared at."
  [editor range]
  (let [search-range ((.-Range node-atom) 0 (.-start range))
        namespaces (find-all-namespace-declarations editor search-range)]
    (some (fn [[point namespace]]
            (console-log "Namespace " namespace " " (.isGreaterThan (.-start range) point))
            (when (.isGreaterThan (.-start range) point)
              namespace))
          namespaces)))

;; TODO: When both host and guest input editors exist and are visible,
;;       ask the user with a pop up which one to use.
(defn execute-on-host-or-guest
  "Execute code on the editor that exists and is visible when there're both
  host and guest REPLs, otherwise, execute code on the editor that exists."
  [project-name code namespace]
  (let [find-repl (if (and (get-in @repls [project-name :host-input-editor])
                           (get-in @repls [project-name :guest-input-editor]))
                    #(and (some? (get-in @repls [%2 %1]))
                          (visible-repl? (get-in @repls [%2 %1])))
                    #(some? (get-in @repls [%2 %1])))]
    (condp find-repl project-name
      :host-input-editor (execute project-name code namespace)
      :guest-input-editor (append-to-editor (get-in @repls [project-name :guest-input-editor])
                                            (str code execute-comment)
                                            :add-newline? false)
      (show-error "No running REPL or the REPL isn't visible for the project: " project-name))))

(defn flash-range
  "Temporary highlight the range to provide visual feedback for users, so
  they can see what code has been executed in the file."
  [editor range]
  (let [marker (.markBufferRange editor range)]
    (.decorateMarker editor marker (js-obj "type" "highlight"
                                           "class" "executed-top-level-form"))
    (go
      (<! (timeout 200))
      (.destroy marker))))

(defn execute-selected-text
  "Gets the selected text in the editor and sends it over to repl."
  [project-name editor]
  (let [selected-range (.getSelectedBufferRange editor)
        namespace (find-namespace-for-range editor selected-range)
        code (.getSelectedText editor)]
    (flash-range editor selected-range)
    (execute-on-host-or-guest project-name code namespace)))

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
    (.scan editor
           regex
           (fn [result]
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
    (swap! ranges (partial map #(apply (.-Range node-atom) %)))
    @ranges))

(defn execute-top-level-form
  "Gets the range of the top level form where the cursor is located at and sends
  the text over to repl."
  [project-name editor]
  (let [ranges (get-all-top-level-ranges editor)
        cursor (.getCursorBufferPosition editor)]
    (when-let [range (find-range-with-cursor ranges cursor)]
      (let [namespace (find-namespace-for-range editor range)
            code (string/trim (.getTextInBufferRange editor range))]
        (flash-range editor range)
        (execute-on-host-or-guest project-name code namespace)))))

(defn execute-entered-text
  "Gets the text in the input editor and sends it over to repl."
  [project-name editor]
  (let [buffer (.getBuffer editor)
        text (string/trim (.getText buffer))
        code (if (string/ends-with? text execute-comment)
               (string/trim (subs text 0 (- (count text) (count execute-comment))))
               text)]
    (execute project-name code)
    (.setText editor "")))

(defn prepare-to-execute
  "The execute-comment is entered, only by the guest side, to signal the host
  side to execute the code."
  [guest-input-editor]
  (append-to-editor guest-input-editor execute-comment :add-newline? false))

(defn send-to-repl
  "Grabs text from the appropriate editor, depending on the context and sends
  it to the repl. The decision making is as follows:
    1. Get the project name if the active editor is one of the input editors,
       and then
      a) Prepare to execute if the editor is a guest input editor for the
         project name
      b) Execute entered text if the editor is a host input editor for the
         project name
    2. Get the project name either from the active text editor's title,
       the most recently used repl or the visible repl, and then
      a) Execute top level form if there's no selection on the editor
      b) Execute selected text if there's selection"
  []
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (if-let [project-name (common/get-project-name-from-input-editor editor)]
      (cond
        (= editor (get-in @repls [project-name :guest-input-editor])) (prepare-to-execute editor)
        (= editor (get-in @repls [project-name :host-input-editor])) (execute-entered-text project-name editor)
        :else (show-error "There's no running repl for the project: " project-name))
      (if-let [project-name (or (common/get-project-name-from-text-editor editor)
                                (common/get-project-name-from-most-recent-repl)
                                (common/get-project-name-from-visible-repl))]
        (if (get @repls project-name)
          (cond
            (.isEmpty (.getLastSelection editor)) (execute-top-level-form project-name editor)
            :else (execute-selected-text project-name editor))
          (show-error "There's no running repl for the project: " project-name))
        (console-log "No matching project-name for the editor")))))
