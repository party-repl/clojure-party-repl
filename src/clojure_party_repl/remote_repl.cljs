(ns clojure-party-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-party-repl.common :refer [repls show-error]]
            [clojure-party-repl.nrepl :as nrepl]
            [clojure-party-repl.unrepl :as unrepl]))

(defn connect-to-nrepl [repl-options]
  (nrepl/connect-to-nrepl repl-options))

(defn connect-to-unrepl [repl-options]
  (unrepl/connect-to-remote-plain-repl repl-options))

(defn connect-to-remote-repl [{:keys [repl-type] :as repl-options}]
  (condp = repl-type
    :lein (connect-to-nrepl repl-options)
    :unrepl (connect-to-unrepl repl-options)
    :else (show-error "Unknown repl type:" repl-type)))
