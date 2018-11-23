(ns clojure-party-repl.repl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget ocall]]
            [clojure-party-repl.common :as common :refer [repls state
                                                          console-log]]))


;; TODO: Support sending multiple messages to repl

(defn get-most-recent-repl []
  (get @state :most-recent-repl-project-name))

(defn update-most-recent-repl [project-name]
  (swap! state assoc :most-recent-repl-project-name project-name))

(defn remove-placeholder-text
  "Removes the text from the placeholder element directly. We can't just
  call editor.setPlaceholderText because the editor is read-only and doesn't
  update the DOM."
  [project-name]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (let [placeholder-element (-> (.getElement output-editor)
                                  (.getElementsByClassName "placeholder-text")
                                  (aget 0))]
      (set! (.-innerText placeholder-element) ""))))

(defn set-output-editor-read-only [project-name]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (set! (.-readOnly output-editor) true)))

(defn append-to-output-editor
  "Appends text at the end of the output editor."
  [project-name output & {:keys [add-newline?] :or {add-newline? true}}]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (common/append-to-editor output-editor output :add-newline? add-newline?)
    (console-log "OUTPUT: " project-name output)))

(defn append-to-output-editor-at
  "Appends text at the range inside the output editor."
  [project-name output range & {:keys [add-newline?] :or {add-newline? true}}]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (.setTextInBufferRange output-editor range output (js-obj "bypassReadOnly" true))
    (console-log "OUTPUT: " project-name output)))

(defn close [connection]
  (.end (:socket-connection connection)))

(defmulti stop-process
  "Kills the local repl process, if any, and disconnects from the repl server."
  (fn [project-name]
    (get-in @repls [project-name :repl-type])))

(defmethod stop-process :default [_]
  (fn []))

(defmulti interrupt-process
  "Interrupts the most recent code execution."
  (fn [project-name]
    (get-in @repls [project-name :repl-type])))

(defmulti execute-code
  "Appends the code to editor and sends it over to repl."
  (fn [project-name code & [options]]
    (get-in @repls [project-name :repl-type])))
