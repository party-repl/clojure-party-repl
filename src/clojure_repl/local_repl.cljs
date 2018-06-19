(ns clojure-repl.local-repl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oset!]]
            [clojure-repl.repl :as repl :refer [stop-process
                                                connect-to-nrepl
                                                append-to-output-editor]]
            [clojure-repl.common :as common :refer [console-log
                                                    get-project-path
                                                    get-project-name-from-path
                                                    repls
                                                    state]]))

;; TODO: Switch to unrepl
;; TODO: Support sending multiple messages to repl
;; TODO: Support exiting repl by Control+D or (exit) or (quit) just like the
;;       Leiningen doc says.

(def process (node/require "process"))
(def child-process (node/require "child_process"))

(def lein-exec (string/split "lein repl" #" "))

(defn look-for-port
  "Searches for a port that nRepl server started on."
  [project-name data-string]
  (if (nil? (get-in @repls [project-name :port]))
    (when-let [match (re-find #"nREPL server started on port (\d+)" data-string)]
      (console-log "Port found!!! " match " from " data-string)
      (swap! repls update project-name #(assoc % :port (second match)))
      (connect-to-nrepl project-name))))

(defn look-for-ns
  "Searches for a namespace that's currently set in the repl."
  [project-name data-string]
  (when-let [match (re-find #"(\S+)=>" data-string)]
    (console-log "Namespace found!!! " match " from " data-string)
    (swap! repls update project-name #(assoc % :current-ns (second match)))))

(defn look-for-repl-info [project-name data-string]
  (look-for-port project-name data-string)
  (look-for-ns project-name data-string))

(defn setup-process
  "Adding callbacks to all messages that lein process recieves."
  [project-name lein-process]
  (console-log "Setting up process...")
  (.on (.-stdout lein-process) "data" (fn [data]
                                        (let [data-string (.toString data)]
                                          (look-for-repl-info project-name data-string)
                                          (append-to-output-editor project-name data-string))))
  (.on (.-stderr lein-process) "data" (fn [data]
                                        (console-log "Stderr... " (.toString data))))
  (.on lein-process "error" (fn [error]
                              (append-to-output-editor project-name (str "Error starting repl: " error))))
  (.on lein-process "close" (fn [code]
                              (console-log "Closing process... " code)
                              (stop-process project-name)))
  (.on lein-process "exit" (fn [code signal]
                             (console-log "Exiting repl... " code " " signal)
                             (swap! repls update project-name #(assoc % :lein-process nil)))))

(defn start-lein-process
  "Starts a lein repl process on project-path."
  [env project-path & args]
  (console-log "Starting lein process...")
  (let [project-name (get-project-name-from-path project-path)]
    (stop-process project-name)
    (let [process-env (js-obj "cwd" project-path
                              "env" (goog.object.set env "PWD" project-path))
          lein-process (.spawn child-process (first lein-exec) (clj->js (next lein-exec)) process-env)]
      (swap! repls update project-name #(assoc % :current-working-directory project-path
                                                 :process-env process-env
                                                 :lein-process lein-process
                                                 :repl-type :repl-type/nrepl))
      (setup-process project-name lein-process))))

(defn get-env
  "Setup the environment that lein process will run in."
  []
  (let [env (goog.object.clone (.-env process))]
    (doseq [k ["PWD" "ATOM_HOME" "ATOM_SHELL_INTERNAL_RUN_AS_NODE" "GOOGLE_API_KEY" "NODE_ENV" "NODE_PATH" "userAgent" "taskPath"]]
      (goog.object.remove env k))
    (oset! env "PATH" (:lein-path @state))
    env))

(defn start-local-repl [project-path]
  (start-lein-process (get-env) project-path))
