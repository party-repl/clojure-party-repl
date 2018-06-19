(ns clojure-repl.repl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget ocall]]
            [clojure-repl.common :as common :refer [repls
                                                    console-log]]))


;; TODO: Support having multiple REPLs
;; TODO: Support sending multiple messages to repl

(defmulti stop-process
  "Kills the local repl process, if any, and disconnects from the repl server.
  Until we support multiple simultaneous repls, starting a repl will call this
  first."
  (fn [project-name]
    (get-in @repls [project-name :repl-type])))

(defn interrupt-process [])

(defmulti execute-code
  "Appends the code to editor and sends it over to repl."
  (fn [project-name code & [options]]
    (get-in @repls [project-name :repl-type])))
