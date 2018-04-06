(ns clojure-repl.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!] :as async]
            [clojure-repl.common :as common :refer [state repls console-log
                                                    show-error]]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]
            [clojure-repl.remote-repl :as remote-repl]
            [clojure-repl.execution :as execution]
            [clojure-repl.connection-panel :as panel]
            [clojure-repl.strings :as strings]))

(def ashell (node/require "atom"))
(def commands (.-commands js/atom))
(def CompositeDisposable (.-CompositeDisposable ashell))

(defn start-local-repl
  "Exported plugin command. Starts new processes to run the repl."
  []
  (console-log "clojure-repl is whipping up a new local repl!")
  (if-let [project-path (common/get-project-path)]
    (let [project-name (common/get-project-name-from-path project-path)]
      (common/add-repl project-name)
      (host/create-editors project-name)
      (local-repl/start-local-repl project-path))
    (show-error "Current file is not located inside one of projects")))

(defn connect-to-nrepl
  "Exported plugin command. Connects to an existing nrepl by host and port."
  [event]
  (console-log "clojure-repl on the case!")
  (go
    (when-let [{:keys [host port]} (<! (panel/prompt-connection-panel strings/nrepl-connection-message))]
      (common/add-repl :remote-repl
                       :host host
                       :port port
                       :lein-process :remote
                       :init-code "(.name *ns*)"
                       :type :nrepl)
      (host/create-editors :remote-repl)
      (remote-repl/connect-to-remote-repl :remote-repl))))

(defn get-project-name-from-input-editor [editor]
  (some (fn [project-name]
          (console-log "Checking if repl exists for the project: " project-name)
          (when (or (= editor (get-in @repls [project-name :guest-input-editor]))
                    (= editor (get-in @repls [project-name :host-input-editor])))
            project-name))
        (keys @repls)))

(defn get-project-name-from-most-recent-repl
  "Returns a project name for the most recently used repl if it still exists."
  []
  (when-let [project-name (get @state :most-recent-repl-project-name)]
    (when (or (get-in @repls [project-name :host-input-editor])
              (get-in @repls [project-name :guest-input-editor]))
      project-name)))

(defn get-project-name-from-repls
  "Returns a project name for either the most recently used repl or any one in
  the repls that exist."
  []
  (or (get-project-name-from-most-recent-repl)
      (some #(when (or (get-in @repls [% :host-input-editor])
                       (get-in @repls [% :guest-input-editor]))
               %)
            (keys @repls))))

(defn send-to-repl
  "Exported plugin command. Grabs text from the appropriate editor, depending on
  the context and sends it to the repl."
  []
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (if-let [project-name (get-project-name-from-input-editor editor)]
      (cond
        (= editor (get-in @repls [project-name :guest-input-editor])) (execution/prepare-to-execute editor)
        (= editor (get-in @repls [project-name :host-input-editor])) (execution/execute-entered-text project-name editor))
      (if-let [project-name (or (common/get-project-name-from-editor editor)
                                (get-project-name-from-repls))]
        (cond
          (.isEmpty (.getLastSelection editor)) (execution/execute-top-level-form project-name editor)
          :else (execution/execute-selected-text project-name editor))
        (console-log "No matching project-name for the editor")))))

(defn show-current-history [project-name editor]
  (.setText editor
            (nth (get-in @repls [project-name :repl-history])
                 (get-in @repls [project-name :current-history-index]))))

(defn show-older-repl-history
  "Exported plugin command. Replaces the content of the input-editor with an
  older history item."
  [event]
  (let [editor (.getActiveTextEditor (.-workspace js/atom))
        project-name (get-project-name-from-input-editor editor)]
    (when (and project-name
              (or (= editor (get-in @repls [project-name :guest-input-editor]))
                  (= editor (get-in @repls [project-name :host-input-editor]))))
      (when (< (get-in @repls [project-name :current-history-index])
               (count (get-in @repls [project-name :repl-history])))
        (swap! repls update project-name #(update % :current-history-index inc)))
      (when (> (count (get-in @repls [project-name :repl-history]))
               (get-in @repls [project-name :current-history-index]))
        (show-current-history project-name editor)))))

(defn show-newer-repl-history
  "Exported plugin command. Replaces the content of the input-editor with a
  newer history item."
  [event]
  (let [editor (.getActiveTextEditor (.-workspace js/atom))
        project-name (get-project-name-from-input-editor editor)]
    (when (and project-name
              (or (= editor (get-in @repls [project-name :guest-input-editor]))
                  (= editor (get-in @repls [project-name :host-input-editor]))))
      (when (>= (get-in @repls [project-name :current-history-index]) 0)
        (swap! repls update project-name #(update % :current-history-index dec)))
      (if (> 0 (get-in @repls [project-name :current-history-index]))
        (.setText editor "")
        (show-current-history project-name editor)))))

(defn ^:private add-commands
  "Exports commands and makes them available in Atom. Exported commands also
  need to be added to shadow-cljs.edn."
  []
  (swap! state update :disposables
         concat
         [(.add commands "atom-workspace" "clojure-repl:startRepl" start-local-repl) ;; TODO: Rename this command to startLocalRepl
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
                    (some #(string/includes? (.getPath pane-item) %1)
                          [common/output-editor-title common/input-editor-title])))))

(defn dispose-repls []
  (doseq [project-name (keys @repls)]
    (guest/dispose project-name)
    (host/dispose project-name)))

(defn activate
  "Initializes the plugin, called automatically by Atom, during startup or if
  the plugin was just installed or re-enabled."
  []
  (console-log "Activating clojure-repl...")
  (add-commands)
  (panel/create-connection-panel)
  (guest/look-for-teletyped-repls))

(defn deactivate
  "Shuts down the plugin, called automatically by Atom if the plugin is
  disabled or uninstalled."
  []
  (console-log "Deactivating clojure-repl...")
  (dispose-repls)
  (reset! repls {})
  (doseq [disposable (get @state :disposables)]
    (.dispose disposable))
  (swap! state assoc :disposables []))

(def start
  "Activates the plugin, used for development."
  activate)

(def stop
  "Deactivates the plugin, used for development."
  deactivate)
