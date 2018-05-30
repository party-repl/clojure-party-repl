(ns clojure-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :refer [repls show-error]]
            [clojure-repl.repl :as repl :refer [connect-to-nrepl]]))

;; Support different remote repls
;; nrepl, unrepl, plain-repl

(defn connect-to-remote-repl [project-name]
  (if-not (contains? project-name @repls)
    (connect-to-nrepl project-name)
    (show-error "There's already a remote REPL connected! Only one remote REPL is allowed.")))
