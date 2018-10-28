(ns clojure-party-repl.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <!] :as async]
            [oops.core :refer [oget]]
            [clojure-party-repl.strings :refer [output-editor-title
                                          input-editor-title]]
            [clojure-party-repl.common :as common :refer [state repls console-log
                                                    show-error visible-repl?
                                                    get-project-name-from-input-editor]]
            [clojure-party-repl.host :as host]
            [clojure-party-repl.guest :as guest]
            [clojure-party-repl.local-repl :as local-repl]
            [clojure-party-repl.remote-repl :as remote-repl]
            [clojure-party-repl.execution :as execution]
            [clojure-party-repl.hidden-editor :as hidden-editor]
            [clojure-party-repl.connection-panel :as panel]
            [clojure-party-repl.strings :as strings]))

(def commands (.-commands js/atom))

(def ^:private package-namespace "clojure-party-repl")

(defn start-local-repl
  "Exported plugin command. Starts new processes to run the repl."
  []
  (console-log "clojure-party-repl is whipping up a new local repl!")
  (if-let [project-path (common/get-project-path)]
    (let [project-name (common/get-project-name-from-path project-path)]
      (if (get-in @repls [project-name :connection])
        (show-error "There's already a running REPL for the project " project-name)
        (do
          (common/add-repl project-name)
          (host/create-editors project-name)
          (local-repl/start-local-repl project-path))))
    (show-error "Cannot start a REPL. Current file is not located inside a project directory or the project directory doesn't have project.clj file.")))

(defn connect-to-remote-repl
  "Exported plugin command. Connects to an existing nrepl by host and port."
  [event]
  (go
    (when-let [{:keys [project-name repl-type host port] :as repl-options} (<! (panel/prompt-connection-panel strings/nrepl-connection-message))]
      (if (get-in @repls [project-name :connection])
        (show-error "There's already a REPL for a project " project-name " "
                    "connected to " host ":" port)
        (do
          (common/add-repl project-name
                           :host host
                           :port port
                           :repl-process :remote)
          (host/create-editors project-name)
          (remote-repl/connect-to-remote-repl repl-options))))))

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
  (swap! state update :disposables concat
    [(.add commands "atom-workspace" (str package-namespace ":startLocalRepl") start-local-repl)
     (.add commands "atom-workspace" (str package-namespace ":connectToRemoteRepl") connect-to-remote-repl)
     (.add commands "atom-workspace" (str package-namespace ":sendToRepl") send-to-repl)
     (.add commands "atom-text-editor.repl-entry" (str package-namespace ":showNewerHistory") show-newer-repl-history)
     (.add commands "atom-text-editor.repl-entry" (str package-namespace ":showOlderHistory") show-older-repl-history)]))

(defn ^:private observe-setting
  [name callback]
  (.observe (.-config js/atom) name callback))

(defn ^:private observe-settings-changes
  []
  (swap! state update :disposables concat
    [(observe-setting (str package-namespace ".lein-path") #(cond
                                                              (= % "")
                                                                (swap! state assoc :lein-path "")
                                                              (string/ends-with? % "/")
                                                                (swap! state assoc :lein-path %)
                                                              :else
                                                                (swap! state assoc :lein-path (str % "/"))))]))

(defn ^:private dispose-repls
  "Disposes all the existing guest and host REPLs."
  []
  (doseq [project-name (keys @repls)]
    (guest/dispose project-name)
    (host/dispose project-name)))

(defn consume-autosave
  "Consumes the Services API provided by Atom's autosave package to prevent
  our editors from getting autosaved into the project. The hook for this is
  defined in package.json."
  [info]
  (let [dont-save-if (oget info "dontSaveIf")]
    (dont-save-if (fn [pane-item]
                    (some #(string/includes? (.getPath pane-item) %1)
                          [output-editor-title input-editor-title])))))

(def config
  "Config Settings for Atom. This will be shown in the Settings section along with the Readme."
  (clj->js
    {:lein-path
      {:title "Path to Leiningen"
       :description "If your Leiningen is not placed in one of your System's $PATH, specify where your `lein` is installed."
       :type "string"
       :default ""}}))

(defn activate
  "Initializes the plugin, called automatically by Atom, during startup or if
  the plugin was just installed or re-enabled."
  []
  (console-log "Activating clojure-party-repl...")
  (add-commands)
  (observe-settings-changes)
  (panel/create-connection-panel)
  (hidden-editor/create-hidden-pane)
  (guest/look-for-teletyped-repls))

(defn deactivate
  "Shuts down the plugin, called automatically by Atom if the plugin is
  disabled or uninstalled."
  []
  (console-log "Deactivating clojure-party-repl...")
  (dispose-repls)
  (reset! repls {})
  (hidden-editor/destroy-hidden-pane)
  (doseq [disposable (get @state :disposables)]
    (.dispose disposable))
  (swap! state assoc :disposables [])
  (swap! state assoc :hidden-editors #{}))

(def start
  "Activates the plugin, used for development."
  activate)

(def stop
  "Deactivates the plugin, used for development."
  deactivate)
