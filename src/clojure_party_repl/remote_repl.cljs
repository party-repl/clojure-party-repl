(ns clojure-party-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-party-repl.common :refer [repls show-error]]
            [clojure-party-repl.nrepl :as nrepl]))

(defn connect-to-remote-repl [project-name host port]
  (nrepl/connect-to-nrepl project-name host port))
