(ns clojure-repl.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!] :as async]
            [oops.core :refer [oget]]
            [clojure-repl.common :as common :refer [state repls console-log
                                                    show-error visible-repl?
                                                    get-project-name-from-input-editor]]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]
            [clojure-repl.remote-repl :as remote-repl]
            [clojure-repl.execution :as execution]
            [clojure-repl.connection-panel :as panel]
            [clojure-repl.strings :as strings]))

(def commands (.-commands js/atom))

(defn start-local-repl
  "Exported plugin command. Starts new processes to run the repl."
  []
  (console-log "clojure-repl is whipping up a new local repl!")
  (if-let [project-path (common/get-project-path)]
    (let [project-name (common/get-project-name-from-path project-path)]
      (if (get-in @repls [project-name :host-input-editor])
        (show-error "There's already a running REPL for the project " project-name)
        (do
          (common/add-repl project-name)
          (host/create-editors project-name)
          (local-repl/start-local-repl project-path))))
    (show-error "Current file is not located inside one of projects")))

(defn connect-to-nrepl
  "Exported plugin command. Connects to an existing nrepl by host and port."
  [event]
  (console-log "clojure-repl on the case!")
  (go
    (when-let [{:keys [host port project-name]} (<! (panel/prompt-connection-panel strings/nrepl-connection-message))]
      (if (get-in @repls [project-name :host-input-editor])
        (show-error "There's already a REPL for a project " project-name " "
                    "connected to " host ":" port)
        (do
          (common/add-repl project-name
                           :host host
                           :port port
                           :lein-process :remote
                           :init-code "(.name *ns*)"
                           :type :nrepl)
          (host/create-editors project-name)
          (remote-repl/connect-to-remote-repl project-name))))))

(def send-to-repl
  "Exported plugin command. Grabs text from the appropriate editor, depending on
  the context and sends it to the repl."
  execution/send-to-repl)

(defn ^:private show-current-history
  "Replaces the content of the input-editor with one of the executed commands in
  the history at the current history index."
  [project-name editor]
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
         [(.add commands "atom-workspace" "clojure-repl:startLocalRepl" start-local-repl)
          (.add commands "atom-workspace" "clojure-repl:connectToNrepl" connect-to-nrepl)
          (.add commands "atom-workspace" "clojure-repl:sendToRepl" send-to-repl)
          (.add commands "atom-text-editor.repl-entry" "clojure-repl:showNewerHistory" show-newer-repl-history)
          (.add commands "atom-text-editor.repl-entry" "clojure-repl:showOlderHistory" show-older-repl-history)]))

(defn consume-autosave
  "Consumes the Services API provided by Atom's autosave package to prevent
  our editors from getting autosaved into the project. The hook for this is
  defined in package.json."
  [info]
  (let [dont-save-if (oget info "dontSaveIf")]
    (dont-save-if (fn [pane-item]
                    (some #(string/includes? (.getPath pane-item) %1)
                          [common/output-editor-title common/input-editor-title])))))

(defn ^:private dispose-repls
  "Disposes all the existing guest and host REPLs."
  []
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
