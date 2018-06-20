(ns clojure-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :refer [repls show-error]]
            [clojure-repl.nrepl :as nrepl :refer [connect-to-nrepl]]))

;; Support different remote repls
;; nrepl, unrepl, plain-repl

(defn connect-to-remote-repl [project-name host port]
  (if-not (contains? project-name @repls)
    (connect-to-nrepl project-name host port)
    (show-error "There's already a remote REPL connected! Only one remote REPL is allowed.")))
