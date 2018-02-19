(ns clojure-repl.core
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :as common]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]))

;; TODO: Prevent Atom from auto-saving the two REPL tabs inside project directory

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
  (host/create-editors)
  (local-repl/start))

(defn execute-entered-text []
  (common/prepare-to-execute))

(defn add-commands []
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:startRepl" start-repl))
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:executeEnteredText" execute-entered-text)))

(defn activate []
  (.log js/console "Activating clojure-repl...")
  (add-commands)
  (guest/look-for-teletyped-repls))

;; NOTE: Used to hot code reload. Calls stop before hotswapping code,
;;       then start after all code is loaded.
(defn stop []
  (deactivate))

(defn start []
  (activate))
