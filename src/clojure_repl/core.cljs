(ns clojure-repl.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [clojure-repl.common :as common :refer [state]]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]
            [clojure-repl.execution :as execution]))

;; TODO: Prevent Atom from auto-saving the two REPL tabs inside project directory

(def ashell (node/require "atom"))

(def commands (.-commands js/atom))

(def CompositeDisposable (.-CompositeDisposable ashell))

;; TODO: Merge with the common/state
(def disposables (atom []))

(def subscriptions (CompositeDisposable.))

(defn start-repl []
  (.log js/console "clojure-repl started!")
  (host/create-editors)
  (local-repl/start))

(defn send-to-repl []
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (cond
      (= editor (:guest-input-editor @state)) (execution/prepare-to-execute editor)
      (= editor (:host-input-editor @state)) (execution/execute-entered-text editor)
      (.isEmpty (.getLastSelection editor)) (execution/execute-top-level-form editor)
      :else (execution/execute-selected-text editor))))

(defn add-commands []
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:startRepl" start-repl))
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:sendToRepl" send-to-repl)))

(defn activate []
  (.log js/console "Activating clojure-repl...")
  (add-commands)
  (guest/look-for-teletyped-repls))

(defn deactivate []
    (.log js/console "Deactivating clojure-repl...")
    (host/dispose)
    (guest/dispose)
    (doseq [disposable @disposables]
      (.dispose disposable)))

(defn start []
  (activate))

(defn stop []
  (deactivate))
