(ns clojure-repl.nrepl
  (:require [cljs.nodejs :as node]
            [oops.core :refer [oget+ oset!+ ocall]]
            [clojure-repl.common :refer [console-log]]
            [cljs.core.async :as async :refer [chan timeout <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def bencode (node/require "bencode"))
(def net (node/require "net"))
(def stream (node/require "stream"))

(defn send
  [connection options callback]
  (let [message (if (nil? (get options "id"))
                  (assoc options "id" (.-uuid (random-uuid)))
                  options)
        js-message (clj->js message)]
    (.write (.-socket-connection connection) (.encode bencode js-message) "binary")))

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

(defrecord SocketMessages [bytes-left chunk-left messages])

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

(defrecord Connection [socket-connection sessions])

(defn ^:private consume-messages-with-id [socket-connection id]
  (let [channel (chan [])]
    (loop [chunk (.read socket-connection)]
      (when (some? chunk)
        (conj channel chunk)
        (recur (.read socket-connection))))
    channel))

(defn ^:private read-data-2 [socket-connection]
  (console-log "Reading data...")
  (let [data (.read socket-connection)]
    (while (some? data)
     (console-log "Processing data...")
     (let [decoded-data (.decode bencode data "utf8")]
       (console-log "DATA arrived as stream!" decoded-data)))))

(defn consume-all-data [data]
  (let [decoded-data (.decode bencode data "utf8")
        decode (.-decode bencode)]
    (console-log "Data: " decoded-data)
    (loop []
     (console-log "Checking more... " (.-length (.-data decode)) (.-position decode))
     (when (> (.-length (.-data decode)) (.-position decode))
       (do
         (console-log "More data: " (.next decode))
         (recur))))))

(defn ^:private read-data [socket-connection]
  (console-log "Reading data...")
  (loop [data (.read socket-connection)
         try-counter 0]
    (if (some? data)
     (do
       (console-log "DATA arrived as stream!" data)
       (consume-all-data data)
       (recur (.read socket-connection) try-counter))
     (if (> 50 try-counter)
       (recur (.read socket-connection) (inc try-counter))
       (console-log "Tried polling enough...")))))

(defn connect [options]
  (let [socket-connection (.connect net (clj->js options))
        sessions []
        connection (Connection. socket-connection sessions)
        messages []
        on-readable (partial read-data socket-connection)]
    (.on socket-connection "readable" on-readable)
    connection))
