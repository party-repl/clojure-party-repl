(ns clojure-party-repl.host
  (:require [clojure.string :refer [ends-with? trim replace]]
            [clojure-party-repl.repl :as repl]
            [clojure-party-repl.execution :as execution]
            [clojure-party-repl.strings :refer [output-editor-title
                                                input-editor-title
                                                hidden-editor-title
                                                execute-comment
                                                output-editor-placeholder]]
            [clojure-party-repl.common :as common :refer [add-subscription
                                                          destroy-editor
                                                          dispose-project-if-empty
                                                          console-log
                                                          repls
                                                          state]]
            [clojure-party-repl.hidden-editor :as hidden-editor]))

;; TODO: Combine the output editor and input editor into a single paneItem.

(defn ^:private set-grammar
  "Sets the grammer of the editor as Clojure."
  [editor]
  (.setGrammar editor (.grammarForScopeName (.-grammars js/atom) "source.clojure")))

(defn destroy-editors
  "Destroys both output and input editors."
  [project-name]
  (destroy-editor project-name :host-output-editor)
  (destroy-editor project-name :host-input-editor)
  (destroy-editor project-name :host-hidden-editor)
  (hidden-editor/remove-hidden-editor project-name :host-hidden-editor))

(defn dispose [project-name]
  (destroy-editors project-name)
  (.dispose (get-in @repls [project-name :subscriptions]))
  (swap! repls update project-name #(assoc % :subscriptions nil)))

(defn activate-editor [project-name editor-key]
  (when-let [editor (get-in @repls [project-name editor-key])]
    (when-let [pane (.paneForItem (.-workspace js/atom) editor)]
      (.activateItem pane editor)
      (.activate pane))))

;; TODO: Ignore any key commands inside the output-editor
(defn ^:private create-output-editor
  "Opens a text editor for displaying repl outputs."
  [project-name]
  (-> (.-workspace js/atom)
      (.open (str output-editor-title " " project-name) (js-obj "split" "right"))
      (.then (fn [editor]
                (set! (.-isModified editor) (fn [] false))
                (set! (.-isModified (.getBuffer editor)) (fn [] false))
                (.setSoftWrapped editor true)
                (.add (.-classList (.-element editor)) "repl-history")
                (set-grammar editor)
                (.moveToBottom editor)
                (swap! repls update project-name #(assoc % :host-output-editor editor))
                (.setPlaceholderText editor output-editor-placeholder)
                (add-subscription project-name
                                  (.onDidDestroy editor (fn [event]
                                                          (swap! repls update project-name #(assoc % :host-output-editor nil))
                                                          (repl/stop-process project-name)
                                                          (dispose project-name)
                                                          (dispose-project-if-empty project-name))))))))

(defn find-non-blank-last-row [buffer]
  (let [last-row (.getLastRow buffer)]
    (if (.isRowBlank buffer last-row)
      (.previousNonBlankRow buffer last-row)
      last-row)))

;; TODO: Set a placeholder text to notify user when repl is ready.
(defn ^:private create-input-editor
  "Opens a text editor for simulating repl's entry area. Adds a listener
  onDidStopChanging to look for execute-comment entered by guest side using
  teletype in the entry, so that it can detect when to execute the code."
  [project-name]
  (-> (.-workspace js/atom)
      (.open (str input-editor-title " " project-name) (js-obj "split" "down"))
      (.then (fn [editor]
                (set! (.-isModified editor) (fn [] false))
                (set! (.-isModified (.getBuffer editor)) (fn [] false))
                (.setSoftWrapped editor true)
                (.add (.-classList (.-element editor)) "repl-entry")
                (set-grammar editor)
                (swap! repls update project-name #(assoc % :host-input-editor editor))
                (add-subscription project-name
                                  (.onDidChangeActiveTextEditor (.-workspace js/atom)
                                                                (fn [active-editor]
                                                                  (when (and (= active-editor editor)
                                                                             (common/new-guest-detected? project-name))
                                                                    (activate-editor project-name :host-hidden-editor)
                                                                    (activate-editor project-name :host-input-editor)))))
                (add-subscription project-name
                                  (.onDidStopChanging editor (fn [event]
                                                               (let [buffer (.getBuffer editor)
                                                                     non-blank-row (find-non-blank-last-row buffer)
                                                                     last-text (.lineForRow buffer non-blank-row)]
                                                                  (when (ends-with? (trim last-text) execute-comment)
                                                                    (.deleteRows buffer (inc non-blank-row) (inc (.getLastRow buffer)))
                                                                    (execution/execute-entered-text project-name editor))))))
                (add-subscription project-name
                                  (.onDidDestroy editor (fn [event]
                                                          (swap! repls update project-name #(assoc % :host-input-editor nil))
                                                          (repl/stop-process project-name)
                                                          (dispose project-name)
                                                          (dispose-project-if-empty project-name))))))))

(defn ^:private create-hidden-editor
  "Adds a text editor in the hidden pane. We need to keep the reference to
  the hidden buffer in the state, so that the hidden pane knows that
  text editor is a hidden buffer."
  [project-name]
  (let [hidden-editor (hidden-editor/create-hidden-editor)
        title (str hidden-editor-title " " project-name)
        path (.resolvePath (.-project js/atom) title)]
    (console-log "Creating hidden editor:" title path)
    (swap! repls update project-name #(assoc % :host-hidden-editor hidden-editor))
    (.setPath (.getBuffer hidden-editor) path)
    (hidden-editor/open-in-hidden-pane hidden-editor)
    (hidden-editor/add-change-listener project-name hidden-editor)))

;; TODO: Make sure to create input editor after output editor has been created.
(defn create-editors [project-name]
  (create-output-editor project-name)
  (create-input-editor project-name)
  (create-hidden-editor project-name)
  )
