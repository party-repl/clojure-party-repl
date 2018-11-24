(ns clojure-party-repl.guest
  (:require [clojure.string :as string :refer [starts-with? index-of]]
            [clojure-party-repl.strings :refer [output-editor-title
                                                input-editor-title
                                                hidden-editor-title
                                                execute-comment]]
            [clojure-party-repl.common :as common :refer [add-subscription
                                                          destroy-editor
                                                          dispose-project-if-empty
                                                          decode-base64
                                                          add-repl
                                                          add-buttons
                                                          repls
                                                          state
                                                          console-log]]
            [clojure-party-repl.hidden-editor :as hidden-editor]
            [clojure-party-repl.execution :as execution]))

(defn ^:private find-project-name-from-title [editor subtitle]
  (let [title (.getTitle editor)
        i (index-of title subtitle)]
    (if (some? i)
      (subs title (+ i (count (str subtitle " - "))))
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
    (set! (.-readOnly editor) true)
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-output-editor nil))
                                      (dispose-project-if-empty project-name))))))

(def action-buttons {"execute" execution/prepare-to-execute
                     "‹" hidden-editor/prepare-older-repl-history
                     "›" hidden-editor/prepare-newer-repl-history})

(defn ^:private link-input-editor
  "Keeps the reference to the input editor associated with the project name.
  If the output editor is already found, moves the input editor below the
  output editor."
  [editor]
  (let [project-name (find-project-name-from-title editor input-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-input-editor editor))
    (.add (.-classList (.-element editor)) "repl-entry")
    (add-buttons project-name editor action-buttons)
    (add-subscription project-name
                      (.onDidDestroy editor
                                    (fn [event]
                                      (swap! repls update project-name #(assoc % :guest-input-editor nil))
                                      (dispose-project-if-empty project-name))))))

(def change-callbacks {:repl-history common/update-repl-history
                       :current-history-index common/update-current-history-index})

(defn get-all-repl-history [hidden-editor first-row]
  (loop [row first-row
         reversed-history (list)]
    (let [text (string/trim-newline (.lineTextForBufferRow hidden-editor row))]
      (if-not (or (string/starts-with? text ":")
                  (empty? text))
        (recur (inc row) (conj reversed-history (decode-base64 text)))
        reversed-history))))

(defn sync-all-repl-history [project-name hidden-editor]
  (let [first-row (hidden-editor/find-first-row-for-state-type hidden-editor :repl-history)
        reversed-history (get-all-repl-history hidden-editor first-row)]
    (console-log "Syncing repl history:" reversed-history)
    (doseq [code reversed-history]
      (swap! repls update-in [project-name :repl-history] #(conj % code)))))

(defn ^:private link-hidden-editor
  "Keeps the reference to the hidden editor associated with the project name
  and moves the editor to the hidden pane."
  [editor]
  (let [project-name (find-project-name-from-title editor hidden-editor-title)]
    (when-not (get @repls project-name)
      (add-repl project-name))
    (swap! repls update project-name #(assoc % :guest-hidden-editor editor))
    (hidden-editor/open-in-hidden-pane editor :moved? true)
    (sync-all-repl-history project-name editor)
    (add-subscription project-name
                      (.onDidChange (.getBuffer editor)
                                    (fn [event]
                                      (hidden-editor/update-local-state project-name
                                                                        editor
                                                                        (.-changes event)
                                                                        change-callbacks))))
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
