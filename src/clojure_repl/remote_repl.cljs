(ns clojure-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :refer [repls show-error]]
            [clojure-repl.nrepl :as nrepl :refer [connect-to-nrepl]]))

(defn connect-to-remote-repl [project-name host port]
  (connect-to-nrepl project-name host port))
