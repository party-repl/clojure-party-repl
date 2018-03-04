(ns clojure-repl.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [clojure-repl.common :as common :refer [state console-log]]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]
            [clojure-repl.execution :as execution]))

(def ashell (node/require "atom"))
(def commands (.-commands js/atom))
(def CompositeDisposable (.-CompositeDisposable ashell))

;; TODO: Merge with the common/state
(def disposables (atom []))

(defn start-repl
  "This is exported as one of the plugin commands."
  []
  (console-log "clojure-repl started!")
  (host/create-editors)
  (local-repl/start))

(defn connect-to-nrepl
  "This is exported as one of the plugin commands. Connect to an existing nrepl
  by host and port."
  ([event]
   (.log js/console "connect-to-nrepl startup event:" event)
   (console-log "clojure-repl on the case!")
   (host/create-editors)
   (local-repl/connect-existing {:host "localhost" :port 12345})))

(defn send-to-repl
  "This is exported as one of the plugin commands.
  When the command is triggered, it grabs appropriate text to be sent to a repl
  depending on the context."
  []
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (cond
      (= editor (:guest-input-editor @state)) (execution/prepare-to-execute editor)
      (= editor (:host-input-editor @state)) (execution/execute-entered-text editor)
      (.isEmpty (.getLastSelection editor)) (execution/execute-top-level-form editor)
      :else (execution/execute-selected-text editor))))

(defn show-older-repl-history [event]
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (when (or (= editor (:guest-input-editor @state)) (= editor (:host-input-editor @state)))
      (when (< (:current-history-index @state) (count (:repl-history @state)))
        (swap! state update :current-history-index inc))
      (when (> (count (:repl-history @state)) (:current-history-index @state))
        (common/show-current-history editor)))))

(defn show-newer-repl-history [event]
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (when (or (= editor (:guest-input-editor @state)) (= editor (:host-input-editor @state)))
      (when (>= (:current-history-index @state) 0)
        (swap! state update :current-history-index dec))
      (if (> 0 (:current-history-index @state))
        (.setText editor "")
        (common/show-current-history editor)))))

(defn add-commands []
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:startRepl" start-repl))
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:connectToNrepl" connect-to-nrepl))
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:sendToRepl" send-to-repl))
  (swap! disposables conj (.add commands "atom-text-editor.repl-entry" "clojure-repl:showNewerHistory" show-newer-repl-history))
  (swap! disposables conj (.add commands "atom-text-editor.repl-entry" "clojure-repl:showOlderHistory" show-older-repl-history)))

(defn consume-autosave
  "This consumes Services API provided by Atom's Autosave package to prevent
  certain items from getting autosaved into project."
  [m]
  (let [dont-save-if (get (js->clj m) "dontSaveIf")]
    (dont-save-if (fn [pane-item]
                    (condp #(string/ends-with? %2 %1) (.getPath pane-item)
                      common/output-editor-title true
                      common/input-editor-title true
                      false)))))

(defn activate []
  (console-log "Activating clojure-repl...")
  (add-commands)
  (guest/look-for-teletyped-repls))

(defn deactivate []
  (console-log "Deactivating clojure-repl...")
  (host/dispose)
  (guest/dispose)
  (doseq [disposable @disposables]
    (.dispose disposable)))

(defn start
  "Used for development."
  []
  (activate))

(defn stop
  "Used for development."
  []
  (deactivate))
