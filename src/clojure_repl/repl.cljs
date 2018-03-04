(ns clojure-repl.repl
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :as common :refer [state
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

;; TODO: Merge with the common/state
(def repl-state
  (atom {:current-working-directory ""
         :lein-path "/usr/local/bin" ;; TODO: Read this from Settings
         :process-env nil
         :lein-process nil
         :connection nil
         :session nil
         :host "localhost"
         :port nil
         :current-ns "user"
         :init-code nil}))

(defn append-to-output-editor
  "Appends text at the end of the output editor."
  [text & {:keys [add-newline?] :or {add-newline? true}}]
  (append-to-editor (:host-output-editor @state) text :add-newline? add-newline?))

(defn close-connection
  "Closes the connection to the repl."
  []
  (console-log "Closing connection...")
  (when-let [connection (:connection @repl-state)]
    (.close connection (:session @repl-state) (fn []))
    (swap! repl-state assoc :connection nil
                            :session nil
                            :port nil
                            :current-ns nil)))

(defn handle-messages
  "Looks through messages received from repl. All error messages and any results
  with namespace and matching session get appended to the editor."
  [id messages]
  (console-log "Handling messages...")
  (doseq [message messages]
    (console-log "Receiving result from repl... " id " " (.-out message) " " (.-value message) " " (.-err message))
    (if (.-err message)
      (do
        (append-to-output-editor (.-err message))
        (append-to-output-editor (str (:current-ns @repl-state) "=> ") :add-newline? false))
      (when (and (.-ns message) (= (.-session message) (:session @repl-state)))
        (console-log "Result arrived with ns: " (.-ns message))
        (swap! repl-state assoc :current-ns (.-ns message))
        (when (.-out message)
          (append-to-output-editor (.-out message)))
        (when (.-value message)
          (append-to-output-editor (.-value message)))
        (append-to-output-editor (str (:current-ns @repl-state) "=> ") :add-newline? false)))))

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
  specified. "
  [code & [options]]
  (console-log "Sending code to repl... " code " inside " (:ns options))
  (let [current-ns (or (:ns options) (:current-ns @repl-state))
        wrapped-code (wrap-to-catch-exception code)
        eval-options (clj->js {"op" "eval"
                               "code" wrapped-code
                               "ns" current-ns
                               "session" (:session @repl-state)})]
    (.send (:connection @repl-state) eval-options (fn [messages]
                                                      (try
                                                        (console-log "Sent code through connection...")
                                                        (doseq [message messages]
                                                          (console-log (js->clj message))
                                                          (cond
                                                            (.-value message) (append-to-output-editor (.-value message))
                                                            (.-err message) (append-to-output-editor (.-err message))
                                                            (.-out message) (append-to-output-editor (.-out message))))
                                                        (catch js/Exception error
                                                          (.error js/console error)
                                                          (.addError (.-notifications js/atom) (str "Error sending to REPL: " error))))))))

(defn connect-to-nrepl
  "Connects to nrepl using already discovered host and port information."
  []
  (console-log "Connecting to nrepl...")
  (when (:connection @repl-state)
    (close-connection))
  (let [connection (.connect nrepl (clj->js {"host" (:host @repl-state)
                                             "port" (:port @repl-state)
                                             "verbose" false}))]
    (swap! repl-state assoc :connection connection)
    (.on connection "error" (fn [err]
                              (console-log "clojure-repl: connection error " err)
                              (append-to-output-editor (str "clojure-repl: connection error " err))
                              (swap! repl-state assoc :connection nil)))
    (.once connection "connect" (fn []
                                  (console-log "!!!Connected to nrepl!!!")
                                  (.on connection "finish" (fn []
                                                             (console-log "Connection finished...")
                                                             (swap! repl-state assoc :connection nil)))
                                  (.clone connection (fn [err message]
                                                       (console-log "Getting session from connection..." (js->clj message))
                                                       (swap! repl-state assoc :session (get-in (js->clj message) [0 "new-session"]))
                                                       (when-let [init-code (:init-code @repl-state)]
                                                         (swap! repl-state assoc :init-code nil)
                                                         (send-to-repl init-code {}))
                                                       (.on (.-messageStream connection) "messageSequence" handle-messages)))))))

(defn stop-process
  "Closes the nrepl connection and kills the lein process. This will also kill
  all the child processes created by the lein process."
  []
  (let [lein-process (:lein-process @repl-state)
        connection (:connection @repl-state)]
    (when connection
      (close-connection))
    (when-not (contains? #{:remote nil} lein-process)
      (console-log "Killing process... " (.-pid lein-process))
      (.removeAllListeners lein-process)
      (.removeAllListeners (.-stdout lein-process))
      (.removeAllListeners (.-stderr lein-process))
      (.kill process (.-pid lein-process) "SIGKILL")
      (swap! repl-state assoc :current-working-directory nil
                              :process-env nil
                              :lein-process nil))))

(defn interrupt-process [])

(defn execute-code
  "Appends the code to editor and sends it over to repl."
  [code & [options]]
  (let [lein-process (:lein-process @repl-state)
        connection (:connection @repl-state)]
    (when (and lein-process connection)
      (append-to-output-editor code)
      (add-repl-history code)
      (send-to-repl code options))))
