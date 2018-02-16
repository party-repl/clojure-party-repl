(ns clojure-repl.core
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :as common]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]))

;; TODO: Clean up state when auto-save and auto-reload happens during development.
;;       Auto-reloading is kinda broken right now. Ideally, when shadow.devtools
;;       notices changes to the ClojureScript code and recompiles them, it would
;;       auto-reload the code inside the Atom plugin. However, this isn't working
;;       yet, so in order to actually load the changes, Atom needs to be reloaded
;;       with the ctrl-option-command-l shortcut.
;; TODO: Prevent Atom from auto-saving the two REPL tabs inside project directory
;; TODO: It would be great to be able to run an nREPL server from inside the
;;       plugin itself so we can REPL into our plugin that we're developing,
;;       from the plugin that we're developing :-)

(def ashell (node/require "atom"))

(def commands (.-commands js/atom))

(def CompositeDisposable (.-CompositeDisposable ashell))

;; TODO: Merge with the common/state
(def disposables (atom []))

(def subscriptions (CompositeDisposable.))

;; Dispose all disposables
(defn deactivate []
    (.log js/console "Deactivating clojure-repl...")
    (host/dispose)
    (guest/dispose)
    (doseq [disposable @disposables]
      (.dispose disposable)))

(defn start-repl []
  (.log js/console "clojure-repl started!")
  (host/create-output-editor)
  (host/create-input-editor)
  (local-repl/start))

(defn execute-entered-text []
  (common/prepare-to-execute))

(defn add-commands []
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:startRepl" start-repl))
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:executeEnteredText" execute-entered-text)))

(defn activate [state]
  (.log js/console "Activating clojure-repl...")
  (add-commands)
  (guest/look-for-teletyped-repls))

(defn serialize []
  nil)

;; live-reload
;; calls stop before hotswapping code
;; then start after all code is loaded
;; the return value of stop will be the argument to start
(defn stop []
  (let [state (serialize)]
    (deactivate)
    state))

(defn start [state]
  (activate state))
