(ns clojure-repl.host
  (:require [clojure.string :as string :refer [ends-with? trim trim-newline replace]]
            [clojure-repl.repl :as repl]
            [clojure-repl.execution :as execution]
            [clojure-repl.common :as common :refer [output-editor-title
                                                    input-editor-title
                                                    execute-comment
                                                    add-subscription
                                                    close-editor
                                                    destroy-editor
                                                    state]]))

;; TODO: Combine the output editor and input editor into a single paneItem.

(defn set-grammar
  "Sets the grammer of the editor as Clojure."
  [editor]
  (.setGrammar editor (.grammarForScopeName (.-grammars js/atom) "source.clojure")))

(defn destroy-editors
  "Destroys both output and input editors."
  []
  (destroy-editor :host-output-editor)
  (destroy-editor :host-input-editor))

(defn dispose []
  (destroy-editors)
  (.dispose (:subscriptions @state))
  (doseq [disposable (:disposables @state)]
    (.dispose disposable)))

;; TODO: Ignore any key commands inside the output-editor
(defn create-output-editor
  "Opens a text editor for displaying repl outputs."
  []
  (-> (.-workspace js/atom)
      (.open output-editor-title (clj->js {"split" "right"}))
      (.then (fn [editor]
                (set! (.-isModified editor) (fn [] false))
                (set! (.-isModified (.getBuffer editor)) (fn [] false))
                (.setSoftWrapped editor true)
                (.add (.-classList (.-editorElement editor)) "repl-history")
                (set-grammar editor)
                (.moveToBottom editor)
                (swap! state assoc :host-output-editor editor)
                (add-subscription (.onDidDestroy editor (fn [event]
                                                          (swap! state assoc :host-output-editor nil)
                                                          (repl/stop-process)
                                                          (dispose))))))))

;; TODO: Set a placeholder text to notify user when repl is ready.
(defn create-input-editor
  "Opens a text editor for simulating repl's entry area. Adds a listener
  onDidStopChanging to look for execute-comment entered by guest side using
  teletype in the entry, so that it can detect when to execute the code."
  []
  (-> (.-workspace js/atom)
      (.open input-editor-title (clj->js {"split" "down"}))
      (.then (fn [editor]
                (set! (.-isModified editor) (fn [] false))
                (set! (.-isModified (.getBuffer editor)) (fn [] false))
                (.setSoftWrapped editor true)
                (.add (.-classList (.-editorElement editor)) "repl-entry")
                (set-grammar editor)
                (swap! state assoc :host-input-editor editor)
                (add-subscription (.onDidStopChanging editor (fn [event]
                                                               (let [buffer (.getBuffer editor)
                                                                     last-text (.getLastLine buffer)]
                                                                 (when (ends-with? (trim last-text) execute-comment)
                                                                   (execution/execute-entered-text editor))))))
                (add-subscription (.onDidDestroy editor (fn [event]
                                                          (swap! state assoc :host-input-editor nil)
                                                          (repl/stop-process)
                                                          (dispose))))))))

;; TODO: Make sure to create input editor after output editor has been created.
(defn create-editors []
  (create-output-editor)
  (create-input-editor))
