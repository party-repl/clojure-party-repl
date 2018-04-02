(ns clojure-repl.guest
  (:require [clojure.string :as string :refer [ends-with?]]
            [clojure-repl.common :as common :refer [output-editor-title
                                                    input-editor-title
                                                    execute-comment
                                                    add-subscription
                                                    destroy-editor
                                                    state
                                                    console-log]]))

;; TODO: Fix the problem of attaching more than one subscription on
;;       the workspace inside `look-for-teletyped-repls`. When textEditors get
;;       disposed, the subscription doesn't seem to get cleaned up right.
;;       In the Atom's Console, the number of "Guest Repl? " getting printed out
;;       over time as hot code reload happens.

(defn link-output-editor
  "Keep the reference to the output editor in the state."
  [editor]
  (when-not (:guest-output-editor @state)
    (swap! state assoc :guest-output-editor editor)
    (add-subscription (.onDidChange editor #(.scrollToBottom (.-element editor))))
    (add-subscription (.onDidDestroy editor #(swap! state assoc :guest-output-editor nil)))))

(defn link-input-editor
  "Keep the reference to the input editor in the state."
  [editor]
  (when-not (:guest-input-editor @state)
    (swap! state assoc :guest-input-editor editor)
    (add-subscription (.onDidDestroy editor #(swap! state assoc :guest-input-editor nil)))))

(defn look-for-teletyped-repls
  "Whenever a new text editor opens in Atom, check the title and look for repl
  editors that opened through teletype."
  []
  (-> (.-workspace js/atom)
      (.onDidAddTextEditor (fn [event]
                             (let [editor (.-textEditor event)
                                   title (.getTitle editor)]
                               (console-log "Guest Repl? " title)
                               (condp #(and (not= %2 %1) (ends-with? %2 %1)) title
                                 output-editor-title (link-output-editor editor)
                                 input-editor-title (link-input-editor editor)
                                 (console-log "No matching repl...")))))
      (add-subscription)))

(defn destroy-editors
  "Destroys both output and input editors that opened through teletype."
  []
  (destroy-editor :guest-output-editor)
  (destroy-editor :guest-input-editor))

(defn dispose []
  (destroy-editors))
