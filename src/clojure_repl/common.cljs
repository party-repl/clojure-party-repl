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

(defn add-subscription [disposable]
  (.add (:subscriptions @state) disposable))

(defn stdout [editor text & [without-newline]]
  (when editor
    (.moveToBottom editor)
    (.insertText editor text)
    (when-not without-newline
      (.insertNewlineBelow editor))
    (.scrollToBottom editor)
    (.moveToBottom editor)))

(defn insert-execute-comment [editor]
  (stdout editor execute-comment true))

(defn prepare-to-execute []
  (when-let [input-editor (.getActiveTextEditor (.-workspace js/atom))]
    (when (= input-editor (:guest-input-editor @state))
      (insert-execute-comment input-editor))))
