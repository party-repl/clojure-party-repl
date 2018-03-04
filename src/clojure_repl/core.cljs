(ns clojure-repl.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [clojure-repl.common :as common :refer [state console-log]]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]
            [clojure-repl.remote-repl :as remote-repl]
            [clojure-repl.execution :as execution]))

(def ashell (node/require "atom"))
(def commands (.-commands js/atom))
(def CompositeDisposable (.-CompositeDisposable ashell))

;; TODO: Merge with the common/state
(def disposables (atom []))

(defn start-local-repl
  "Exported plugin command. Starts new processes to run the repl."
  []
  (console-log "clojure-repl started!")
  (host/create-editors)
  (local-repl/start))

;; TODO: Create a UI instead of hardcoding this.
(defn connect-to-nrepl
  "Exported plugin command. Connects to an existing nrepl by host and port."
  ([event]
   (console-log "connect-to-nrepl startup event:" event)
   (console-log "clojure-repl on the case!")
   (host/create-editors)
   (remote-repl/connect-to-remote-repl {:host "localhost" :port 12345})))

(defn send-to-repl
  "Exported plugin command. Grabs text from the appropriate editor, depending on
  the context and sends it to the repl."
  []
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (cond
      (= editor (:guest-input-editor @state)) (execution/prepare-to-execute editor)
      (= editor (:host-input-editor @state)) (execution/execute-entered-text editor)
      (.isEmpty (.getLastSelection editor)) (execution/execute-top-level-form editor)
      :else (execution/execute-selected-text editor))))

(defn show-older-repl-history
  "Exported plugin command. Replaces the content of the input-editor with an
  older history item."
  [event]
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (when (or (= editor (:guest-input-editor @state))
              (= editor (:host-input-editor @state)))
      (when (< (:current-history-index @state) (count (:repl-history @state)))
        (swap! state update :current-history-index inc))
      (when (> (count (:repl-history @state)) (:current-history-index @state))
        (common/show-current-history editor)))))

(defn show-newer-repl-history
  "Exported plugin command. Replaces the content of the input-editor with a
  newer history item."
  [event]
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (when (or (= editor (:guest-input-editor @state))
              (= editor (:host-input-editor @state)))
      (when (>= (:current-history-index @state) 0)
        (swap! state update :current-history-index dec))
      (if (> 0 (:current-history-index @state))
        (.setText editor "")
        (common/show-current-history editor)))))

(defn ^:private add-commands
  "Exports commands and makes them available in Atom. Exported commands also
  need to be added to shadow-cljs.edn."
  []
  (swap! disposables
         concat
         [(.add commands "atom-workspace" "clojure-repl:startRepl" start-local-repl)
          (.add commands "atom-workspace" "clojure-repl:connectToNrepl" connect-to-nrepl)
          (.add commands "atom-workspace" "clojure-repl:sendToRepl" send-to-repl)
          (.add commands "atom-text-editor.repl-entry" "clojure-repl:showNewerHistory" show-newer-repl-history)
          (.add commands "atom-text-editor.repl-entry" "clojure-repl:showOlderHistory" show-older-repl-history)]))

(defn consume-autosave
  "Consumes the Services API provided by Atom's autosave package to prevent
  our editors from getting autosaved into the project. The hook for this is
  defined in package.json."
  [info]
  (let [dont-save-if (get (js->clj info) "dontSaveIf")]
    (dont-save-if (fn [pane-item]
                    (some #(string/ends-with? (.getPath pane-item) %1)
                          [common/output-editor-title common/input-editor-title])))))

(defn activate
  "Initializes the plugin, called automatically by Atom, during startup or if
  the plugin was just installed or re-enabled."
  []
  (console-log "Activating clojure-repl...")
  (add-commands)
  (guest/look-for-teletyped-repls))

(defn deactivate
  "Shuts down the plugin, called automatically by Atom if the plugin is
  disabled or uninstalled."
  []
  (console-log "Deactivating clojure-repl...")
  (host/dispose)
  (guest/dispose)
  (doseq [disposable @disposables]
    (.dispose disposable))
  (reset! disposables []))

(def start
  "Activates the plugin, used for development."
  activate)

(def stop
  "Deactivates the plugin, used for development."
  deactivate)
