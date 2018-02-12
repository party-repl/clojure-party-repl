(ns clojure-repl.common
  (:require [cljs.nodejs :as node]
            [cljs.pprint :refer [pprint]]))

(def ashell (node/require "atom"))
(def CompositeDisposable (.-CompositeDisposable ashell))

(def output-editor-title "Clojure REPL History")
(def input-editor-title "Clojure REPL Entry")
(def execute-comment ";execute")

(def state
  (atom {:subscriptions (CompositeDisposable.)
         :process nil
         :host-input-editor nil
         :host-output-editor nil
         :guest-input-editor nil
         :guest-output-editor nil
         :history []}))

;; TODO: Use pprint like this (with-out-str (pprint text))
(defn stdout [editor text & without-newline]
  (let [buffer (.getBuffer editor)]
    (when without-newline
      (.append buffer "\n" (goog.object.create "undo" "skip")))
    (.append buffer text (goog.object.create "undo" "skip")))
  (.scrollToBottom editor))

(defn insert-execute-comment [editor]
  (let [last-row (.getLastBufferRow editor)]
    (stdout editor execute-comment)
    (.scrollToBottom editor)))

(defn prepare-to-execute []
  (when-let [input-editor (.getActiveTextEditor (.-workspace js/atom))]
    (when (or (= input-editor (:host-input-editor @state))
              (= input-editor (:guest-input-editor @state)))
      (insert-execute-comment input-editor))))
