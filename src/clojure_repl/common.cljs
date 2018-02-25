(ns clojure-repl.common
  (:require [cljs.nodejs :as node]
            [cljs.pprint :refer [pprint]]))

(def ashell (node/require "atom"))
(def CompositeDisposable (.-CompositeDisposable ashell))

(def output-editor-title "Clojure REPL History")
(def input-editor-title "Clojure REPL Entry")
(def execute-comment ";execute")

;; TODO: Support repl history

(def state
  (atom {:subscriptions (CompositeDisposable.)
         :process nil
         :host-input-editor nil
         :host-output-editor nil
         :guest-input-editor nil
         :guest-output-editor nil
         :history []}))

(defn console-log [& texts]
  (.log js/console (apply str texts)))

(defn add-subscription [disposable]
  (.add (:subscriptions @state) disposable))

;; TODO: Support destroying multiple editors with a shared buffer.
(defn close-editor [editor]
  (doseq [pane (.getPanes (.-workspace js/atom))]
    (when (some #(= editor %) (.getItems pane))
      (.destroyItem pane editor))))

(defn destroy-editor [editor-keyword]
  (when (some? (editor-keyword @state))
    (close-editor (editor-keyword @state))
    (swap! state assoc editor-keyword nil)))

(defn stdout [editor text & [without-newline]]
  (when editor
    (.moveToBottom editor)
    (.insertText editor text)
    (when-not without-newline
      (.insertNewlineBelow editor))
    (.scrollToBottom editor)
    (.moveToBottom editor)))
