(ns clojure-party-repl.nrepl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall]]
            [clojure-party-repl.common :refer [console-log repls add-repl-history]]
            [clojure-party-repl.bencode :as bencode]
            [clojure-party-repl.repl :as repl]
            [cljs.core.async :as async :refer [chan timeout close! <! >! alts!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def net (node/require "net"))
(def process (node/require "process"))

(def timeout-msec 5000)

(defn timeout-chan []
  (timeout (or timeout-msec (.-MAX_SAFE_INTEGER js/Number))))

(defn ^:private apply-decode-data [connection]
  (bencode/apply-decode-data @(:decode connection)))

(defn ^:private cache-decode-data [connection]
  (let [{:keys [data position encoding]} (bencode/get-decode-data)]
    (when-not (bencode/decoded-all?)
      (swap! (:decode connection) assoc :data data
                                        :position position
                                        :encoding encoding))))

(defn ^:private swap-decode-data [project-name connection]
  (let [previous-project-name (repl/get-most-recent-repl)]
    (when (and previous-project-name
              (not= previous-project-name project-name))
      (cache-decode-data (get-in @repls [previous-project-name :connection]))
      (apply-decode-data connection))))

;; TODO: Manage decode data with message id.
(defn send
  "Writes a message onto the socket."
  [connection message callback]
  (let [id (or (get message "id")
               (.-uuid (random-uuid)))
        message-js (clj->js (assoc message "id" id))]
    (swap! (:queued-messages connection) assoc id [])
    (swap! (:callbacks connection) assoc id callback)
    (apply-decode-data connection)
    (.write (:socket-connection connection) (bencode/encode message-js))))

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
   :output-chan nil
   :queued-messages nil
   :callbacks nil
   :decode {:data nil
            :encoding nil
            :position 0}})

(defn ^:private has-done-message? [messages]
  (and (coll? messages)
       (last messages)
       (.-status (last messages))
       (some #(= % "done") (.-status (last messages)))))

(defn ^:private callback-with-queued-messages
  "Calls a callback associated with messages with the id. The messages should
  include a 'done' message, indicating the end of the messages."
  [connection id]
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
  [project-name connection message-chan]
  (go-loop []
    (console-log "Consuming data...")
    (if-let [chunk (.read (:socket-connection connection))]
      (do
        (swap-decode-data project-name connection)
        (let [messages (bencode/decode chunk)]
          (when-not (empty? messages)
            (console-log "Decoded data...")
            (>! message-chan messages)
          (recur))))
      (close! message-chan))))

(defn ^:private read-data
  "Called when socket dispatches readable event, having data available
  to be read. This consumes all available data on socket and put them in
  output-channel after converting them into messages. message-chan will
  eventually takes nil out and breaks out of loop-recur when it's been closed."
  [project-name connection]
  (console-log "Reading data... ")
  (let [message-chan (chan)]
    (go-loop []
      (if-let [messages (<! message-chan)]
        (do
          (console-log "Messages: " messages)
          (>! (:output-chan connection) messages)
          (doseq [message-js messages]
            (let [id (.-id message-js)]
              (swap! (:queued-messages connection) update id conj message-js)
              (callback-with-queued-messages connection id)))
          (recur))
        (cache-decode-data connection)))
    (consume-all-data project-name connection message-chan)))

;; TODO: Support type ahead by creating a new session to send the code.

;; Messages can be one of these types:
;;  1. General REPL's messages, especially for printing the initial description of the REPL -> session-id
;;  2. Replies that come back from the message sent to the REPL -> message-id

;; TODO: Handle clone error
(defn ^:private get-new-session-message
  "Reads the first message containing session id received after socket
  connection has been established."
  [project-name connection]
  (let [message-chan (chan)]
    (go
      (when-let [messages (<! message-chan)]
        (doseq [message-js messages]
          (let [id (.-id message-js)]
            (console-log "Message: " message-js)
            (swap! (:queued-messages connection) update id conj message-js)
            (callback-with-queued-messages connection id)))))
    (consume-all-data project-name connection message-chan)))

(defn connect
  "Makes a socket connection at the given host and port.

  When an error occurs on the connection, the 'close' event will be called
  directly following the error event."
  [{:keys [host port project-name]} callback]
  (let [socket-connection (net.Socket.)
        connection (assoc connection-template :socket-connection socket-connection
                                              :output-chan (chan)
                                              :queued-messages (atom {})
                                              :callbacks (atom {})
                                              :decode (atom {:position 0
                                                             :data nil
                                                             :encoding "utf8"}))]
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
                (.once socket-connection "readable" #(get-new-session-message project-name connection))
                (clone-connection connection
                                  (fn [err messages]
                                    (.on socket-connection "readable" #(read-data project-name connection))
                                    (callback connection (oget (get messages 0) "new-session"))))))
    connection))

;; ---------------------------------------------------
;; Connection should be enstablished as below:
;; ---------------------------------------------------
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

(comment "Sends code over to repl with current namespace, or optional namespace if
specified. When a namespace-not-found message is received, resend the code
to the current namespace.")
(defmethod repl/execute-code :repl-type/nrepl
  [project-name code & [{:keys [namespace resent?]}]]
  (let [wrapped-code (wrap-to-catch-exception code)
        {:keys [connection session current-ns]} (get @repls project-name)
        options {"session" session
                 "ns" (or namespace current-ns)}]
    (repl/update-most-recent-repl project-name)
    (when-not resent?
      (repl/append-to-output-editor project-name code))
    (add-repl-history project-name code)
    (eval connection
          wrapped-code
          options
          (fn [errors messages] ;; TODO: Do we need try-catch to resend code?
            (when (namespace-not-found? (last messages))
              (console-log "Resending code to the current namespace...")
              (repl/execute-code project-name code {:resent? true}))))))

(defn ^:private output-namespace
  "Outputs the current namespace for the project onto the output editor."
  [project-name]
  (repl/append-to-output-editor project-name (str (get-in @repls [project-name :current-ns]) "=> ") :add-newline? false))

(defn ^:private output-message
  "Outputs the message contents onto the output editor. If it contains a
  namespace, updates the current namespace for the project."
  [project-name message-js]
  (when (.-ns message-js)
    (swap! repls update project-name #(assoc % :current-ns (.-ns message-js))))
  (if (.-out message-js)
    (repl/append-to-output-editor project-name (string/trim (.-out message-js)))
    (if (.-value message-js)
      (repl/append-to-output-editor project-name (.-value message-js))
      (when (.-err message-js)
        (repl/append-to-output-editor project-name (.-err message-js))))))

(defn ^:private filter-by-session [session messages]
  (filter #(= (.-session %) session) messages))

(defn ^:private handle-messages
  "Outputs messages as they come into the output channel."
  [project-name connection]
  (console-log "Handler called...")
  (let [output-chan (:output-chan connection)]
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

(comment "Closes the nrepl connection and kills the lein process. This will also kill
all the child processes created by the lein process.")
(defmethod repl/stop-process :repl-type/nrepl
  [project-name]
  (let [{:keys [connection lein-process]} (get @repls project-name)]
    (when connection
      (repl/close connection))
    (when (= (repl/get-most-recent-repl) project-name)
      (bencode/reset-decode-data))
    (when (= :remote lein-process)
      (console-log "Remote repl connection closed!"))
    (when-not (contains? #{:remote nil} lein-process)
      (console-log "Killing process... " (.-pid lein-process))
      (.removeAllListeners lein-process)
      (.removeAllListeners (.-stdout lein-process))
      (.removeAllListeners (.-stderr lein-process))
      (when-let [pid (.-pid lein-process)]
        (.kill process pid "SIGKILL")))
    (swap! repls update project-name #(assoc % :lein-process nil))))

(defn connect-to-nrepl [project-name host port]
  (connect {:project-name project-name
            :host host
            :port port}
           (fn [connection session]
             (console-log "Connection succeeded!! " connection)
             (when session
               (swap! repls update project-name
                      #(assoc % :repl-type :repl-type/nrepl
                                :connection connection
                                :session session))
               (repl/update-most-recent-repl project-name)
               (handle-messages project-name connection)
               (repl/remove-placeholder-text project-name)
               (when-let [init-code (get-in @repls [project-name :init-code])]
                 (repl/execute-code project-name init-code))))))
