(ns clojure-repl.guest
  (:require [clojure.string :as string :refer [ends-with?]]
            [clojure-repl.common :as common :refer [output-editor-title
                                                    input-editor-title
                                                    execute-comment
                                                    add-subscription
                                                    state]]))

(defn link-output-editor [editor]
  (when-not (:guest-output-editor @state)
    (swap! state assoc :guest-output-editor editor)
    (add-subscription (.onDidDestroy editor #(swap! state assoc :guest-output-editor nil)))))

(defn link-input-editor [editor]
  (when-not (:guest-input-editor @state)
    (swap! state assoc :guest-input-editor editor)
    (add-subscription (.onDidDestroy editor #(swap! state assoc :guest-input-editor nil)))))

(defn look-for-teletyped-repls []
  (add-subscription (.onDidAddTextEditor (.-workspace js/atom) (fn [event]
                                                                 (let [editor (.-textEditor event)
                                                                       title (.getTitle editor)]
                                                                   (.log js/console (str "Guest Repl? " title))
                                                                   (condp #(ends-with? %2 %1) title
                                                                     output-editor-title (link-output-editor editor)
                                                                     input-editor-title (link-input-editor editor)
                                                                     (.log js/console "No matching repl...")))))))

(defn dispose []
  (.dispose (:subscriptions @state))
  (doseq [disposable (:disposables @state)]
    (.dispose disposable)))
