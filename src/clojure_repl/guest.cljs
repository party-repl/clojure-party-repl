(ns clojure-repl.guest
  (:require [clojure.string :as string :refer [starts-with?]]
            [clojure-repl.common :as common :refer [output-editor-title
                                                    input-editor-title
                                                    execute-comment
                                                    add-subscription
                                                    destroy-editor
                                                    repls
                                                    console-log]]))

;; TODO: Fix the problem of attaching more than one subscription on
;;       the workspace inside `look-for-teletyped-repls`. When textEditors get
;;       disposed, the subscription doesn't seem to get cleaned up right.
;;       In the Atom's Console, the number of "Guest Repl? " getting printed out
;;       over time as hot code reload happens.

(defn link-output-editor
  "Keep the reference to the output editor in the state."
  [project-name editor]
  (when-not (get-in @repls [project-name :guest-output-editor])
    (swap! repls update project-name #(assoc % :guest-output-editor editor))
    (add-subscription project-name
                      (.onDidDestroy editor
                                     (fn [event]
                                       (swap! repls update project-name #(assoc % :guest-output-editor nil)))))))

(defn link-input-editor
  "Keep the reference to the input editor in the state."
  [project-name editor]
  (when-not (get-in @repls [project-name :guest-input-editor])
    (swap! repls update project-name #(assoc % :guest-input-editor editor))
    (add-subscription project-name
                      (.onDidDestroy editor
                                     (fn [event]
                                       (swap! repls update project-name #(assoc % :guest-input-editor nil)))))))

; TODO: Properly re-find the guest repls
;; TODO: Allow multiple guest repls by parsing the editor titles
(defn look-for-teletyped-repls
  "Whenever a new text editor opens in Atom, check the title and look for repl
  editors that opened through teletype."
  []
  (-> (.-workspace js/atom)
      (.onDidAddTextEditor (fn [event]
                             (let [editor (.-textEditor event)
                                   title (.getTitle editor)]
                               (console-log "Guest Repl? " title)
                               (condp #(and (string/includes? %2 %1) (not (starts-with? %2 %1))) title
                                 output-editor-title (link-output-editor :guest editor)
                                 input-editor-title (link-input-editor :guest editor)
                                 (console-log "No matching repl...")))))
      (partial add-subscription :guest)))

(defn destroy-editors
  "Destroys both output and input editors that opened through teletype."
  [project-name]
  (destroy-editor project-name :guest-output-editor)
  (destroy-editor project-name :guest-input-editor))

(def dispose destroy-editors)
