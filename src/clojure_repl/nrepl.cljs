(ns clojure-repl.nrepl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall]]
            [clojure-repl.common :refer [console-log repls state repl-state]]
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
    (console-log "Callback on " messages)
    (when-let [callback (get @(:callbacks connection) id)]
      (console-log "Callback is " callback)
      (when (has-done-message? messages)
        (callback nil messages)))))

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
  (console-log "Reading data... " connection)
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
  [{:keys [host port project-name]} callback]
  (let [socket-connection (net.Socket.)
        connection (assoc connection-template :socket-connection socket-connection
                                              :output-channel (chan)
                                              :queued-messages (atom {})
                                              :callbacks (atom {}))]
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
;; Connection should be enstablished like this:
(defn append-to-output-editor [project-name output & {:keys [add-newline?] :or {add-newline? true}}]
  (console-log "OUTPUT: " project-name output))

(defn send-to-nrepl [project-name code & [namespace]]
  (let [wrapped-code (repl/wrap-to-catch-exception code)
        {:keys [connection session current-ns]} (get @repls project-name)
        options {"session" session
                 "ns" (or namespace current-ns)}]
    (eval connection
          wrapped-code
          options
          (fn [errors messages]
            (when (repl/namespace-not-found? (last messages))
              (console-log "Resending code to the current namespace...")
              (send-to-nrepl project-name code))))))

(defn output-namespace [project-name]
  (append-to-output-editor project-name (str (get-in @repls [project-name :current-ns]) "=> ") :add-newline? false))

(defn output-message [project-name message-js]
  (when (.-ns message-js)
    (swap! repls update project-name #(assoc % :current-ns (.-ns message-js))))
  (if (.-out message-js)
    (append-to-output-editor project-name (string/trim (.-out message-js)))
    (when (.-value message-js)
      (append-to-output-editor project-name (.-value message-js)))))

(defn filter-by-session [session messages]
  (filter #(= (.-session %) session) messages))

(defn handle-messages [project-name connection]
  (console-log "Handler called...")
  (let [output-chan (:output-channel connection)]
    (go-loop []
      (when-let [messages (<! output-chan)]
        (console-log "Handleing messages..." messages)
        (let [session (get-in @repls [project-name :session])
              filtered-messages (filter-by-session session messages)]
          (when (not-any? repl/namespace-not-found? filtered-messages)
            (doseq [message-js filtered-messages]
              (output-message project-name message-js))
            (when (has-done-message? filtered-messages)
              (output-namespace project-name))))
        (recur)))))

(defn update-most-recent-repl [project-name]
  (when (nil? (get @state :most-recent-repl-project-name))
    (swap! state assoc :most-recent-repl-project-name project-name)))

(defn close-connection [project-name]
  (when-let [connection (get-in @repls [project-name :connection])]
    (close connection)))

(defn connect-to-nrepl [project-name host port]
  (when (get @repls project-name)
    (close-connection project-name))
  (connect {:project-name project-name
            :host host
            :port port}
           (fn [connection session]
             (console-log "Connection succeeded!! " connection)
             (when session
               (console-log "Session is here..." session)
               (swap! repls update
                     project-name
                     #(assoc repl-state
                       :repl-type :repl-type/nrepl
                       :connection connection
                       :session session))
               (update-most-recent-repl project-name)
               (handle-messages project-name connection)))))
