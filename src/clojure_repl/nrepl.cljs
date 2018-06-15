(ns clojure-repl.nrepl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall]]
            [clojure-repl.common :refer [console-log repls state repl-state]]
            [clojure-repl.bencode :as bencode :refer [encode decode]]
            [clojure-repl.repl :as repl]
            [cljs.core.async :as async :refer [chan timeout close! <! >! alts!]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(def net (node/require "net"))

(defn timeout-chan [& [msecs]]
  (timeout (or msecs (.-MAX_SAFE_INTEGER js/Number))))

;; One cool aspect of alts!! is that you can give it a timeout channel,
;; which waits the specified number of milliseconds and then closes. It’s
;; an elegant mechanism for putting a time limit on concurrent operations.
(defn send
  [connection message callback]
  (let [js-message (clj->js message)]
    (.write (:socket-connection connection) (encode js-message))))

(defn eval [connection code options callback]
    (let [id (or (get options "id")
                 (.-uuid (random-uuid)))]
      (swap! (:queued-messages connection) assoc id [])
      (swap! (:callbacks connection) assoc id callback)
      (send connection (assoc options "op" "eval"
                                      "code" code
                                      "id" id)
                       callback)))

(defn clone-connection
  ([connection callback] (send connection {"op" "clone"} callback))
  ([connection session callback] (send connection {"op" "clone" "session" session} callback)))

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
  (when-let [messages (aget @(:queued-messages connection) id)]
    (when-let [callback (aget @(:callbacks connection) id)]
      (when (has-done-message? messages)
        (callback nil messages)))))

(defn ^:private consume-all-data
  "Keeps reading data from the socket until it's been depleated. All data is
  decoded and put into a channel. The channel is closed when no more data
  can be read from the socket."
  [socket-connection]
  (let [message-chan (chan)]
    (go-loop [chunk (.read socket-connection)]
      (if (some? chunk)
        (let [messages (decode chunk)]
          (when-not (empty? messages)
            (>! message-chan messages)
            (recur (.read socket-connection))))
        (close! message-chan)))
    message-chan))

(defn ^:private read-data
  "Called when socket dispatches readable event, having data available
  to be read. This consumes all available data on socket and put them in
  output-channel after converting them into messages. message-chan will
  eventually takes nil out and breaks out of loop-recur when it's been closed."
  [connection]
  (console-log "Reading data...")
  (let [message-chan (consume-all-data (:socket-connection connection))]
    (go-loop []
      (when-let [messages (<! message-chan)]
        (>! (:output-channel connection) messages)
        (map (fn [{:keys [id] :as message}]
               (swap! (:queued-messages connection) update id conj message)
               (callback-with-queued-messages connection id))
             messages)
        (recur)))))

;; TODO: Support having multiple connections to REPL using bencode.
;; TODO: Support type ahead by creating a new session to send the code.

;; Messages can be one of these types:
;;  1. General REPL's messages, especially for printing the initial description of the REPL -> session-id
;;  2. Replies that come back from the message sent to the REPL -> message-id

(defn connect
  [{:keys [host port project-name]} callback]
  (let [socket-connection (net.Socket.)
        connection (assoc connection-template :socket-connection socket-connection
                                              :output-channel (chan)
                                              :queued-messages (atom {})
                                              :callbacks (atom {}))
        on-readable (partial read-data connection)]
    (.connect socket-connection
              port
              host
              (fn []
                (clone-connection socket-connection
                                  (fn [err message]
                                    (callback connection (oget message ["0" "new-session"]))))
                (.on socket-connection "readable" on-readable)))
    connection))


;; ---------------------------------------------------
;; Connection should be enstablished like this:
(defn send-to-nrepl [project-name code & [namespace]]
  (let [wrapped-code (repl/wrap-to-catch-exception code)
        {:keys [connection session current-ns]} (get @repls project-name)
        options {"session" session
                 "ns" (or namespace current-ns)}]
    (eval connection wrapped-code options (fn [errors messages]
                                            (when (repl/namespace-not-found? (last messages))
                                              (console-log "Resending code to the current namespace...")
                                              (send-to-nrepl project-name code))))))

(defn output-namespace [project-name]
  (repl/append-to-output-editor project-name (str (get-in @repls [project-name :current-ns]) "=> ") :add-newline? false))

(defn output-messages [project-name message]
  (when (.-ns message)
    (swap! repls update project-name #(assoc % :current-ns (.-ns message))))
  (if (.-out message)
    (repl/append-to-output-editor project-name (string/trim (.-out message)))
    (when (.-value message)
      (repl/append-to-output-editor project-name (.-value message)))))

(defn filter-by-session [session messages]
  (filter #(= (.-session %) session) messages))

(defn handle-messages [project-name connection]
  (let [session (get-in @repls [project-name :session])]
    (go-loop []
      (let [messages (filter-by-session session (<! (:output-channel connection)))]
        (when (not-any? repl/namespace-not-found? messages)
          (map #(output-messages project-name %) messages)
          (if (has-done-message? messages)
            (output-namespace project-name)
            (recur)))))))

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
                               (when session
                                 (swap! repls update
                                        project-name
                                        #(assoc repl-state
                                           :repl-type :repl-type/nrepl
                                           :connection connection
                                           :session session))
                                 (update-most-recent-repl project-name)
                                 (handle-messages project-name connection)))))
