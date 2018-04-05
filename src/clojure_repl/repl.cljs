(ns clojure-repl.repl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [clojure-repl.common :as common :refer [repls
                                                    state
                                                    append-to-editor
                                                    add-repl-history
                                                    console-log]]))

;; TODO: Switch to unrepl
;; TODO: Support having multiple REPLs
;; TODO: Support sending multiple messages to repl

(def ashell (node/require "atom"))
(def process (node/require "process"))
(def nrepl (node/require "nrepl-client"))
(def net (node/require "net"))

(defn append-to-output-editor
  "Appends text at the end of the output editor. Returns true to notify
  handle-messages that the text got appended."
  [project-name text & {:keys [add-newline?] :or {add-newline? true}}]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (append-to-editor output-editor text :add-newline? add-newline?)
    true))

(defn close-connection
  "Closes the connection to the repl."
  [project-name]
  (console-log "Closing connection...")
  (when (get project-name @repls)
    (when-let [connection (get-in @repls [project-name :connection])]
      (.close connection (get-in @repls [project-name :session]) (fn []))
      (swap! repls update project-name #(assoc % :connection nil
                                                 :session nil
                                                 :port nil
                                                 :current-ns "user")))))

(defn namespace-not-found? [message]
  (when (.-status message)
    (some #(= "namespace-not-found" %) (.-status message))))

(defn handle-message
  "Append error message or results with matching session to the output editor."
  [project-name message]
  (console-log "Receiving result from repl... " (.-status message) " " (.-session message) " " (.-out message) " " (.-value message) " " (.-err message))
  (if (.-err message)
    (append-to-output-editor project-name (.-err message))
    (when (and (= (.-session message) (get-in @repls [project-name :session]))
               (or (.-out message) (.-value message)))
      (console-log "Result arrived with ns: " (.-ns message))
      (when (.-ns message)
        (swap! repls update project-name #(assoc % :current-ns (.-ns message))))
      (when (.-out message)
        (append-to-output-editor project-name (string/trim (.-out message))))
      (when (.-value message)
        (append-to-output-editor project-name (.-value message))))))

(defn handle-messages
  "Looks through messages received from repl. If any of the messages got
  appended to the editor, append the namespace prompt at the end."
  [project-name id messages]
  (console-log "Handling messages... " project-name " " id " " messages)
  (when (and (not-any? namespace-not-found? messages)
             (some some? (map #(handle-message project-name %) messages)))
    (append-to-output-editor project-name (str (get-in @repls [project-name :current-ns]) "=> ") :add-newline? false)))

(defn wrap-to-catch-exception
  "Wraps the code with try-catch in order to get stacktraces if error happened."
  [code]
  (str "(do
          (require '[clojure.repl :as repl])
          (try "
            code
            " (catch Throwable throwable
               (binding [*err* (new java.io.StringWriter)]
                 (repl/pst throwable)
                 (throw (Exception. (str *err*)))))))"))

(defn send-to-repl
  "Sends code over to repl with current namespace, or optional namespace if
  specified. When a namespace-not-found message is received, resend the code
  to the current namespace."
  [project-name code & [options]]
  (console-log "Sending code to repl... " code " with " options)
  (let [current-ns (or (:ns options) (get-in @repls [project-name :current-ns]))
        wrapped-code (wrap-to-catch-exception code)
        eval-options (clj->js {"op" "eval"
                               "code" wrapped-code
                               "ns" current-ns
                               "session" (get-in @repls [project-name :session])})
        connection (get-in @repls [project-name :connection])]
    (.send connection eval-options (fn [err messages]
                                      (try
                                        (console-log "Sent code through connection... " messages)
                                        (when (namespace-not-found? (last messages))
                                          (console-log "Resending code to the current namespace...")
                                          (send-to-repl project-name code))
                                        (catch js/Exception error
                                          (.error js/console error)
                                          (.addError (.-notifications js/atom) (str "Error sending to REPL: " error))))))))

(defn connect-to-nrepl
  "Connects to nrepl using already discovered host and port information."
  [project-name]
  (console-log "Connecting to nrepl...")
  (when (get project-name @repls)
    (close-connection project-name))
  (let [connection (.connect nrepl (clj->js {"host" (get-in @repls [project-name :host])
                                             "port" (get-in @repls [project-name :port])
                                             "verbose" false}))]
    (swap! repls update project-name #(assoc % :connection connection))
    (.on connection "error" (fn [err]
                              (console-log "clojure-repl: connection error " err)
                              (append-to-output-editor project-name (str "clojure-repl: connection error " err))
                              (swap! repls update project-name #(assoc % :connection nil))))
    (.once connection "connect" (fn []
                                  (console-log "!!!Connected to nrepl!!!")
                                  (.on connection "finish" (fn []
                                                             (console-log "Connection finished...")
                                                             (swap! repls update project-name #(assoc % :connection nil))))
                                  (.clone connection (fn [err message]
                                                       (console-log "Getting session from connection..." (js->clj message))
                                                       (swap! repls update project-name #(assoc % :session (get-in (js->clj message) [0 "new-session"])))
                                                       (when-let [init-code (get-in @repls [project-name :init-code])]
                                                         (send-to-repl project-name init-code {}))
                                                       (let [handler (partial handle-messages project-name)]
                                                         (.on (.-messageStream connection) "messageSequence" handler))))))))

(defn stop-process
  "Closes the nrepl connection and kills the lein process. This will also kill
  all the child processes created by the lein process."
  [project-name]
  (let [lein-process (get-in @repls [project-name :lein-process])]
    (when (get-in @repls [project-name :connection])
      (close-connection project-name))
    (when-not (contains? #{:remote nil} lein-process)
      (console-log "Killing process... " (.-pid lein-process))
      (.removeAllListeners lein-process)
      (.removeAllListeners (.-stdout lein-process))
      (.removeAllListeners (.-stderr lein-process))
      (.kill process (.-pid lein-process) "SIGKILL"))
    (swap! repls update project-name #(assoc % :lein-process nil))))

(defn interrupt-process [])

(defn execute-code
  "Appends the code to editor and sends it over to repl."
  [project-name code & [options]]
  (let [lein-process (get-in @repls [project-name :connection])
        connection (get-in @repls [project-name :connection])]
    (when (and lein-process connection)
      (swap! state assoc :most-recent-repl-project-name project-name)
      (append-to-output-editor project-name code)
      (add-repl-history project-name code)
      (send-to-repl project-name code options))))
