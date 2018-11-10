(ns clojure-party-repl.guest
  (:require [clojure.string :as string :refer [starts-with? index-of]]
            [clojure-party-repl.strings :refer [output-editor-title
                                                input-editor-title
                                                hidden-editor-title
                                                execute-comment]]
            [clojure-party-repl.common :as common :refer [add-subscription
                                                          destroy-editor
                                                          dispose-project-if-empty
                                                          add-repl
                                                          repls
                                                          state
                                                          console-log]]
            [clojure-party-repl.hidden-editor :refer [open-in-hidden-pane
                                                      add-change-listener]]))

(defn ^:private find-project-name-from-title [editor subtitle]
  (let [title (.getTitle editor)
        i (index-of title subtitle)]
    (if (some? i)
      (subs title (+ i (count (str subtitle " "))))
      (console-log "ERROR: REPL Editor should contain " subtitle " in the title"))))

(defn ^:private split-right [editor]
  (let [current-pane (.paneForItem (.-workspace js/atom) editor)
        new-pane (.splitRight current-pane)]
    (.activate current-pane)
    (.moveItemToPane current-pane editor new-pane)
    (.activateItem new-pane editor)
    (.activate new-pane)))

(defn ^:private split-down [output-editor input-editor]
  (let [current-pane (.paneForItem (.-workspace js/atom) input-editor)
        output-pane (.paneForItem (.-workspace js/atom) output-editor)
        new-pane (.splitDown output-pane)]
    (.activate current-pane)
    (.moveItemToPane current-pane input-editor new-pane)
    (.activateItem new-pane input-editor)
    (.activate new-pane)))

(defn ^:private split-up [output-editor input-editor]
  (let [current-pane (.paneForItem (.-workspace js/atom) output-editor)
        input-pane (.paneForItem (.-workspace js/atom) input-editor)
        new-pane (.splitUp input-pane)]
    (.moveItemToPane current-pane output-editor new-pane)
    (.activateItem new-pane output-editor)
    (.activate new-pane)))

(defn ^:private link-output-editor
  "Keeps the reference to the output editor associated with the project name
  and moves the editor to the right pane. If the input editor is already found,
  moves it to the bottom of the output editor."
  [editor]
  (let [project-name (find-project-name-from-title editor output-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-output-editor editor))
    (if-let [input-editor (get-in @repls [project-name :guest-input-editor])]
      (split-up editor input-editor)
      (split-right editor))
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-output-editor nil))
                                      (dispose-project-if-empty project-name))))))

(defn ^:private link-input-editor
  "Keeps the reference to the input editor associated with the project name.
  If the output editor is already found, moves the input editor below the
  output editor."
  [editor]
  (let [project-name (find-project-name-from-title editor input-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-input-editor editor))
    (if-let [output-editor (get-in @repls [project-name :guest-output-editor])]
      (split-down output-editor editor)
      (split-right editor))
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-input-editor nil))
                                      (dispose-project-if-empty project-name))))))

(defn ^:private link-hidden-editor
  "Keeps the reference to the hidden editor associated with the project name
  and moves the editor to the hidden pane."
  [editor]
  (let [project-name (find-project-name-from-title editor hidden-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-hidden-editor editor))
    (open-in-hidden-pane editor :moved? true)
    (add-change-listener project-name editor)
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-hidden-editor nil))
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
                               hidden-editor-title (link-hidden-editor editor)
                               (console-log "No matching repl...")))))))

(defn destroy-editors
  "Destroys both output and input editors that opened through teletype."
  [project-name]
  (destroy-editor project-name :guest-output-editor)
  (destroy-editor project-name :guest-input-editor))

(def dispose destroy-editors)
