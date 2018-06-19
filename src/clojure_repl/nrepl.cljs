(ns clojure-repl.nrepl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall]]
            [clojure-repl.common :refer [console-log repls state]]
            [clojure-repl.bencode :as bencode :refer [encode decode]]
            [clojure-repl.repl :as repl]
            [cljs.core.async :as async :refer [chan timeout close! <! >! alts!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def net (node/require "net"))

(defn timeout-chan [& [msecs]]
  (timeout (or msecs (.-MAX_SAFE_INTEGER js/Number))))

;; One cool aspect of alts!! is that you can give it a timeout channel,
;; which waits the specified number of milliseconds and then closes. It’s
;; an elegant mechanism for putting a time limit on concurrent operations.
(defn send
  [connection message callback]
  (let [id (or (get message "id")
               (.-uuid (random-uuid)))
        message-js (clj->js (assoc message "id" id))]
    (swap! (:queued-messages connection) assoc id [])
    (swap! (:callbacks connection) assoc id callback)
    (.write (:socket-connection connection) (encode message-js))))

(defn eval [connection code options callback]
  (send connection
        (assoc options "op" "eval"
                       "code" code)
        callback))

(defn clone-connection
  ([connection callback]
    (send connection {"op" "clone"} callback))
  ([connection session callback]
    (send connection {"op" "clone" "session" session} callback)))

(defn close [connection]
  (.end (:socket-connection connection)))

;; Use defmulti/defmethod on these
; (comment (send [])
;           (clone [])
;           (close [])
;           (describe [])
;           (eval [])
;           (intrupt [])
;           (loadFile [])
;           (stdin []))

(def connection-template
  {:socket-connection nil
   :output-channel nil
   :queued-messages nil
   :callbacks nil})

(defn ^:private has-done-message? [messages]
  (and (coll? messages)
       (last messages)
       (.-status (last messages))
       (some #(= % "done") (.-status (last messages)))))

(defn ^:private callback-with-queued-messages [connection id]
  (when-let [messages (get @(:queued-messages connection) id)]
    (when (has-done-message? messages)
      (when-let [callback (get @(:callbacks connection) id)]
        (callback nil messages)
        (swap! (:queued-messages connection) dissoc id)
        (swap! (:callbacks connection) dissoc id)))))

(defn ^:private consume-all-data
  "Keeps reading data from the socket until it's been depleated. All data is
  decoded and put into a channel. The channel is closed when no more data
  can be read from the socket."
  [socket-connection message-chan]
  (go-loop [chunk (.read socket-connection)]
    (console-log "Consuming data..." )
    (if (some? chunk)
      (let [messages (decode chunk)]
        (when-not (empty? messages)
          (console-log "Decoded data...")
          (>! message-chan messages)
          (recur (.read socket-connection))))
      (close! message-chan))))

(defn ^:private read-data
  "Called when socket dispatches readable event, having data available
  to be read. This consumes all available data on socket and put them in
  output-channel after converting them into messages. message-chan will
  eventually takes nil out and breaks out of loop-recur when it's been closed."
  [connection]
  (console-log "Reading data... ")
  (let [message-chan (chan)]
    (go-loop []
      (when-let [messages (<! message-chan)]
        (console-log "Messages: " messages)
        (>! (:output-channel connection) messages)
        (doseq [message-js messages]
          (let [id (.-id message-js)]
            (swap! (:queued-messages connection) update id conj message-js)
            (callback-with-queued-messages connection id)))
        (recur)))
    (consume-all-data (:socket-connection connection) message-chan)))

;; TODO: Support having multiple connections to REPL using bencode.
;; TODO: Support type ahead by creating a new session to send the code.

;; Messages can be one of these types:
;;  1. General REPL's messages, especially for printing the initial description of the REPL -> session-id
;;  2. Replies that come back from the message sent to the REPL -> message-id

;; TODO: Handle clone error
(defn ^:private get-new-session-message
  [connection]
  (let [message-chan (chan)]
    (go
      (when-let [messages (<! message-chan)]
        (doseq [message-js messages]
          (let [id (.-id message-js)]
            (console-log "Message: " message-js)
            (swap! (:queued-messages connection) update id conj message-js)
            (callback-with-queued-messages connection id)))))
    (consume-all-data (:socket-connection connection) message-chan)))

(defn connect
  "Makes a socket connection at the given host and port.

  When an error occurs on the connection, the 'close' event will be called
  directly following the error event."
  [{:keys [host port project-name]} callback]
  (let [socket-connection (net.Socket.)
        connection (assoc connection-template :socket-connection socket-connection
                                              :output-channel (chan)
                                              :queued-messages (atom {})
                                              :callbacks (atom {}))]
    (.on socket-connection "error" (fn [error]
                                     (console-log "Error: " error ". The socket will be closed.")))
    (.on socket-connection "close" (fn [had-error?]
                                     (if had-error?
                                       (console-log "The socket is closed due to a transmission error.")
                                       (console-log "The socket is fully closed."))))
    (.connect socket-connection
              port
              host
              (fn []
                (.once socket-connection "readable" #(get-new-session-message connection))
                (clone-connection connection
                                  (fn [err messages]
                                    (.on socket-connection "readable" #(read-data connection))
                                    (callback connection (oget (get messages 0) "new-session"))))))
    connection))

;; ---------------------------------------------------
;; Connection should be enstablished as below:
;; ---------------------------------------------------
(defn append-to-output-editor
  "Appends text at the end of the output editor."
  [project-name output & {:keys [add-newline?] :or {add-newline? true}}]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (common/append-to-editor output-editor text :add-newline? add-newline?)
    (console-log "OUTPUT: " project-name output)))

(defn ^:private namespace-not-found? [message]
  (when (.-status message)
    (some #(= "namespace-not-found" %) (.-status message))))

(defn ^:private wrap-to-catch-exception
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

(defmethod repl/execute-code :repl-type/nrepl
  "Sends code over to repl with current namespace, or optional namespace if
  specified. When a namespace-not-found message is received, resend the code
  to the current namespace."
  [project-name code & [namespace]]
  (let [wrapped-code (wrap-to-catch-exception code)
        {:keys [connection session current-ns]} (get @repls project-name)
        options {"session" session
                 "ns" (or namespace current-ns)}]
    (swap! state assoc :most-recent-repl-project-name project-name)
    (repl/append-to-output-editor project-name code)
    (common/add-repl-history project-name code)
    (eval connection
          wrapped-code
          options
          (fn [errors messages] ;; TODO: Do we need try-catch to resend code?
            (when (namespace-not-found? (last messages))
              (console-log "Resending code to the current namespace...")
              (send-to-nrepl project-name code))))))

(defn output-namespace [project-name]
  (append-to-output-editor project-name (str (get-in @repls [project-name :current-ns]) "=> ") :add-newline? false))

(defn output-message [project-name message-js]
  (when (.-ns message-js)
    (swap! repls update project-name #(assoc % :current-ns (.-ns message-js))))
  (if (.-out message-js)
    (append-to-output-editor project-name (string/trim (.-out message-js)))
    (if (.-value message-js)
      (append-to-output-editor project-name (.-value message-js))
      (when (.-err message)
        (append-to-output-editor project-name (.-err message-js))))))

(defn filter-by-session [session messages]
  (filter #(= (.-session %) session) messages))

(defn handle-messages
  ""
  [project-name connection]
  (console-log "Handler called...")
  (let [output-chan (:output-channel connection)]
    (go-loop []
      (when-let [messages (<! output-chan)]
        (console-log "Handleing messages..." messages)
        (let [session (get-in @repls [project-name :session])
              filtered-messages (filter-by-session session messages)]
          (when (not-any? namespace-not-found? filtered-messages)
            (doseq [message-js filtered-messages]
              (output-message project-name message-js))
            (when (has-done-message? filtered-messages)
              (output-namespace project-name))))
        (recur)))))

(defn update-most-recent-repl [project-name]
  (when (nil? (get @state :most-recent-repl-project-name))
    (swap! state assoc :most-recent-repl-project-name project-name)))

(defmethod repl/stop-process :repl-type/nrepl
  "Closes the nrepl connection and kills the lein process. This will also kill
  all the child processes created by the lein process."
  [project-name]
  (let [{:keys [connection lein-process]} (get @repls project-name)]
    (when connection
      (close connection))
    (when-not (contains? #{:remote nil} lein-process)
      (console-log "Killing process... " (.-pid lein-process))
      (.removeAllListeners lein-process)
      (.removeAllListeners (.-stdout lein-process))
      (.removeAllListeners (.-stderr lein-process))
      (.kill process (.-pid lein-process) "SIGKILL"))
    (swap! repls update project-name #(assoc % :lein-process nil))))

(defn connect-to-nrepl [project-name host port]
  (when (get @repls project-name)
    (repl/stop-process project-name))
  (connect {:project-name project-name
            :host host
            :port port}
           (fn [connection session]
             (console-log "Connection succeeded!! " connection)
             (when session
               (console-log "Session is here..." session)
               (swap! repls update project-name
                      #(assoc %
                              :repl-type :repl-type/nrepl
                              :connection connection
                              :session session))
               (update-most-recent-repl project-name)
               (handle-messages project-name connection)
               (when-let [init-code (get-in @repls [project-name :init-code])]
                 (repl/execute-code project-name init-code))))))
