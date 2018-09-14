(ns clojure-repl.guest
  (:require [clojure.string :as string :refer [starts-with? index-of]]
            [clojure-repl.strings :refer [output-editor-title
                                          input-editor-title
                                          execute-comment]]
            [clojure-repl.common :as common :refer [add-subscription
                                                    destroy-editor
                                                    dispose-project-if-empty
                                                    add-repl
                                                    repls
                                                    state
                                                    console-log
                                                    show-error]]))

(defn find-project-name-from-title [editor subtitle]
  (let [title (.getTitle editor)
        i (index-of title subtitle)]
    (if (some? i)
      (subs title (+ i (count (str subtitle " "))))
      (show-error "REPL Editor should contain " subtitle " in the title"))))

(defn link-output-editor
  "Keep the reference to the output editor associated with the project name
  in the repls."
  [editor]
  (let [project-name (find-project-name-from-title editor output-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-output-editor editor))
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-input-editor nil))
                                      (dispose-project-if-empty project-name))))))

(defn link-input-editor
  "Keep the reference to the input editor associated with the project name
  in the repls."
  [editor]
  (let [project-name (find-project-name-from-title editor input-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-input-editor editor))
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-input-editor nil))
                                      (dispose-project-if-empty project-name))))))

(defn look-for-teletyped-repls
  "Whenever a new text editor opens in Atom, check the title and look for repl
  editors that opened through teletype."
  []
  (swap! state update :disposables
    conj
    (.onDidAddTextEditor (.-workspace js/atom)
                         (fn [event]
                           (let [editor (.-textEditor event)
                                 title (.getTitle editor)]
                             (console-log "Guest Repl? " title)
                             (condp #(and (string/includes? %2 %1) (not (starts-with? %2 %1))) title
                               output-editor-title (link-output-editor editor)
                               input-editor-title (link-input-editor editor)
                               (console-log "No matching repl...")))))))

(defn destroy-editors
  "Destroys both output and input editors that opened through teletype."
  [project-name]
  (destroy-editor project-name :guest-output-editor)
  (destroy-editor project-name :guest-input-editor))

(def dispose destroy-editors)
