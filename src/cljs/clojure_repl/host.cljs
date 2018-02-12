(ns clojure-repl.host
  (:require [clojure.string :as string :refer [ends-with? trim trim-newline replace]]
            [clojure-repl.local-repl :as local-repl]
            [clojure-repl.common :as common :refer [output-editor-title
                                                    input-editor-title
                                                    execute-comment
                                                    stdout
                                                    state]]))

(defn add-subscription [sub]
  (.add (:subscriptions @state) sub))

(defn set-grammar [editor]
  (.setGrammar editor (.grammarForScopeName (.-grammars js/atom)) "source.clojure"))

(defn clear [editor]
  (.setText editor "")
  (.scrollToBottom editor))

(defn execute [code]
  (local-repl/execute-code code))

(defn create-output-editor []
  (-> (.-workspace js/atom)
      (.open output-editor-title (goog.object.create "split" "right"))
      (.then (fn [editor]
                ;(.isModified editor false)
                (.setSoftWrapped editor true)
                (.add (.-classList (.-editorElement editor)) "repl-history")
                (set-grammar editor)
                (swap! state assoc :host-output-editor editor)))))

(defn execute-entered-text []
  (let [input-editor (:host-input-editor @state)
        input-buffer (.getBuffer input-editor)
        last-text (.getLastLine input-buffer)]
    (when (ends-with? (trim last-text) execute-comment)
      (let [output-editor (:host-output-editor @state)
            input-text (replace (.getText input-buffer) execute-comment "")]
        (execute input-text)
        (clear input-editor)))))

(defn create-input-editor []
  (-> (.-workspace js/atom)
      (.open input-editor-title (goog.object.create "split" "bottom"))
      (.then (fn [editor]
                ;(.isModified editor false)
                ;(.setSoftWrapped editor true)
                (.add (.-classList (.-editorElement editor)) "repl-entry")
                (set-grammar editor)
                (swap! state assoc :host-input-editor editor)
                (add-subscription (.onDidStopChanging editor execute-entered-text))
                (add-subscription (.onDidDestroy editor local-repl/stop-process))))))

(defn execute-selected-text [])
(defn execute-block [& {:keys [top-level] :as options}])

(defn destroy-editors []
  (when (some? (:host-output-editor @state))
    (.destroyed (:host-output-editor @state))
    (swap! state assoc :host-output-editor nil))
  (when (some? (:host-input-editor @state))
    (.destroyed (:host-input-editor @state))
    (swap! state assoc :host-input-editor nil)))

(defn dispose []
  (.dispose (:subscriptions @state))
  (doseq [disposable (:disposables @state)]
    (.dispose disposable)))
