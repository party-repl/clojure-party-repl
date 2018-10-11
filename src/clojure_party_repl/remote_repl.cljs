(ns clojure-party-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-party-repl.common :refer [repls show-error]]
            [clojure-party-repl.nrepl :as nrepl]
            [clojure-party-repl.unrepl :as unrepl]))

(defn connect-to-nrepl [project-name host port]
  (nrepl/connect-to-nrepl project-name host port))

(defn connect-to-unrepl [project-name host port]
  (unrepl/connect-to-remote-plain-repl project-name host port))

(defn connect-to-remote-repl [project-name host port]
  (connect-to-unrepl project-name host port))
