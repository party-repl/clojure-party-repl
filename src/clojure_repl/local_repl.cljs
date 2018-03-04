(ns clojure-repl.local-repl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [clojure-repl.repl :as repl :refer [repl-state
                                                stop-process
                                                connect-to-nrepl
                                                append-to-output-editor]]
            [clojure-repl.common :as common :refer [console-log]]))

;; TODO: Switch to unrepl
;; TODO: Support having multiple REPLs
;; TODO: Support sending multiple messages to repl

(def ashell (node/require "atom"))
(def fs (node/require "fs"))
(def process (node/require "process"))
(def child-process (node/require "child_process"))

(def lein-exec (string/split "lein repl" #" "))

(defn look-for-port
  "Searches for a port that nRepl server started on."
  [data-string]
  (if (nil? (:port @repl-state))
    (when-let [match (re-find #"nREPL server started on port (\d+)" data-string)]
      (console-log "Port found!!! " match " from " data-string)
      (swap! repl-state assoc :port (second match))
      (connect-to-nrepl))))

(defn look-for-ns
  "Searches for a namespace that's currently set in the repl."
  [data-string]
  (when-let [match (re-find #"(\S+)=>" data-string)]
    (console-log "Namespace found!!! " match " from " data-string)
    (swap! repl-state assoc :current-ns (second match))))

(defn look-for-repl-info [data-string]
  (look-for-port data-string)
  (look-for-ns data-string))

(defn setup-process
  "Adding callbacks to all messages that lein process recieves."
  [lein-process]
  (console-log "Setting up process...")
  (.on (.-stdout lein-process) "data" (fn [data]
                                        (let [data-string (.toString data)]
                                          (look-for-repl-info data-string)
                                          (append-to-output-editor data-string))))
  (.on (.-stderr lein-process) "data" (fn [data]
                                        (console-log "Stderr... " (.toString data))))
  (.on lein-process "error" (fn [error]
                              (append-to-output-editor (str "Error starting repl: " error))))
  (.on lein-process "close" (fn [code]
                              (console-log "Closing process... " code)
                              (stop-process)))
  (.on lein-process "exit" (fn [code signal]
                             (console-log "Exiting repl... " code " " signal)
                             (swap! repl-state assoc :lein-process nil))))

;; TODO: Look for the project.clj file and decide which path to use.
;; TODO: Warn user when project.clj doesn't exist in the project.
(defn get-project-path []
  (first (.getPaths (.-project js/atom))))

(defn get-project-clj [project-path]
  (console-log "Looking for project.clj at " project-path "/project.clj")
  (.existsSync fs (str project-path + "/project.clj")))

(defn start-lein-process
  "Starts a lein repl process on project-path."
  [env & args]
  (console-log "Starting lein process...")
  (let [project-path (get-project-path)
        process-env (clj->js {"cwd" project-path
                              "env" (goog.object.set env "PWD" project-path)})
        lein-process (.spawn child-process (first lein-exec) (clj->js (rest lein-exec)) process-env)]
    (swap! repl-state assoc :current-working-directory project-path)
    (swap! repl-state assoc :process-env process-env)
    (swap! repl-state assoc :lein-process lein-process)
    (setup-process lein-process)))

(defn get-env
  "Setup the environment that lein process will run in."
  []
  (let [env (goog.object.clone (.-env process))]
    (doseq [k ["PWD" "ATOM_HOME" "ATOM_SHELL_INTERNAL_RUN_AS_NODE" "GOOGLE_API_KEY" "NODE_ENV" "NODE_PATH" "userAgent" "taskPath"]]
      (goog.object.remove env k))
    (goog.object.set env "PATH" (:lein-path @repl-state))
    env))

(defn start []
  (stop-process)
  (start-lein-process (get-env)))
