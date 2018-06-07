(ns clojure-repl.nrepl
  (:require [cljs.nodejs :as node]
            [oops.core :refer [oget+ oset! oset!+ ocall]]
            [clojure-repl.common :refer [console-log]]
            [clojure-repl.bencode :refer [decode]]
            [cljs.core.async :as async :refer [chan closed? <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def net (node/require "net"))

(defn send
  [connection options callback]
  (let [id (or (get options "id")
               (.-uuid (random-uuid)))
        message (assoc options "id" id)
        js-message (clj->js message)
        from-channel (chan [])
        out-channel (chan [])
        queued-messages []]
    (swap! (.-from-channel connection) assoc id from-channel)
    (swap! (.-out-channel connection) assoc id out-channel)
    (swap! (.-queued-messages connection) assoc id queued-messages)
    (swap! (.-callbacks connection) assoc id callback)
    (.write (.-socket-connection connection) (.encode bencode js-message) "binary")
    out-channel))

(defn eval ""
  ([connection code callback]
   (send connection {"op" "eval"
                     "code" (str code "\n")}
                    callback))
  ([connection code namespace callback]
   (send connection {"op" "eval"
                     "code" (str code "\n")
                     "ns" namespace}
                    callback))
  ([connection code namespace session callback]
   (send connection {"op" "eval"
                     "code" code
                     "ns" namespace
                     "session" session}
                    callback))
  ([connection code namespace session id callback]
   (send connection {"op" "eval"
                     "code" code
                     "ns" namespace
                     "session" session
                     "id" id}
                    callback))
  ([connection code namespace session id eval-function callback]))

(defn send-code [connection code]
  (eval connection code (fn [])))

(defn close [connection]
  (.end (.-socket-connection connection)))

;; Use defmulti/defmethod instead
(comment (defprotocol ConnectionProtocol
          (send [])
          (clone [])
          (close [])
          (describe [])
          (eval [])
          (intrupt [])
          (loadFile [])
          (stdin [])))

(defrecord Connection [socket-connection
                       from-channels
                       out-channels
                       queued-messages
                       callbacks])

(defn ^:private has-done-message? [messages]
  (and (last messages)
       (.-status (last messages))
       (some? #(= % "done") (.-status (last messages)))))

(defn ^:private collect-messages [id queued-messages callback]
  (loop []
    (cond
      (has-done-message? queued-messages) (callback nil queued-messages)
      (closed? from-channel) (callback "Error: timedout" nil)
      (recur))))

(defn ^:private  consume-all-data
  "Call recur on loop only when there's no error calling decode.next(). When
  error happens, it means there isn't enough information to fully decode the
  data. So we need to cache all the current data we got and wait for the
  remaining data to become available to be consumed."
  [connection]
  (when-let [decoded-data (decode connection data)]
    (loop []
     (console-log "Checking more... " (decoded-all?))
     (if (decoded-all?)
       decoded-data
       (recur)))))

(defn ^:private consume-messages-with-id [socket-connection id]
  (let [channel (chan [])]
    (loop [chunk (.read socket-connection)]
      (when (some? chunk)
        (conj channel (.decode bencode chunk "utf8"))
        (recur (.read socket-connection))))
    channel))

(defn ^:private received-all-messages? [connection id]
  (let [messages (oget!+ (.-queued-messages connection) id)]
    (has-done-message? messages)))

(defn ^:private read-data [connection]
  (console-log "Reading data... ")
  (consume-all-data connection))

(defn clone
  ([connection callback] (send connection {:op "clone"} callback))
  ([connection session callback] (send connection {:op "clone" :session session} callback)))

;;

(defn connect [{:keys [host port project-name]}]
  (let [socket-connection (net.Socket.)
        connection (Connection. socket-connection (atom {}) (atom {}) (atom {}) (atom {}))
        on-readable (partial read-data connection)]
    (.connect socket-connection
              host
              port
              (fn []
                (.on socket-connection "readable" on-readable)))
    connection))
