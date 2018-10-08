(ns clojure-party-repl.unrepl
  (:require [cljs.nodejs :as node]
            [cljs.reader :refer [read]]
            [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
            [clojure.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall]]
            [clojure-party-repl.common :refer [console-log repls add-repl-history]]
            [clojure-party-repl.bencode :as bencode]
            [clojure-party-repl.repl :as repl]
            [cljs.core.async :as async :refer [chan timeout close! <! >! alts!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def process (node/require "process"))
(def net (node/require "net"))
(def fs (node/require "fs"))

(fs.readFile (str  "..../resources/unrepl/blob.clj")
   (fn [err data]
     (when err (console-log err (ocall process "cwd")))
     (def unrepl-blob data)))

(defmulti handle-unrepl-tuple
  (fn [project-name tuple]
    (if-not (vector? tuple)
      ::not-a-tuple
      (let [[tag payload group-id] tuple]
        tag))))

;; NOTE: Other tags to support are
;;        [:exception {:ex exception :phase {}} 0]
;;        [:read {:from [line col] :to [line col] :offset N :len N} 1]

(defmethod handle-unrepl-tuple ::not-a-tuple [project-name thing]
  (repl/append-to-output-editor (pr-str thing) :add-newline? true))

(defmethod handle-unrepl-tuple :default [project-name [tag payload group-id :as tuple]]
  (console-log "Unhandled unrepl tuple:" (pr-str tuple)))

(defmethod handle-unrepl-tuple :read [project-name [tag payload group-id :as tuple]]
  (console-log "Noop unrepl tuple:" (pr-str tuple)))

(defmethod handle-unrepl-tuple :started-eval [project-name [tag payload group-id :as tuple]]
  (console-log "Noop unrepl tuple:" (pr-str tuple)))

(defmethod handle-unrepl-tuple :eval [project-name [tag payload group-id]]
  (repl/append-to-output-editor project-name (pr-str payload) :add-newline? true))

(defmethod handle-unrepl-tuple :out [project-name [tag payload group-id]]
  (repl/append-to-output-editor project-name payload :add-newline? false))

(defmethod handle-unrepl-tuple :err [project-name [tag payload group-id]]
  (repl/append-to-output-editor project-name payload :add-newline? false))

(defmethod handle-unrepl-tuple :exception [project-name [tag payload :as tuple]]
  (console-log "Noop unrepl tuple:" (pr-str tuple)))

(defmethod handle-unrepl-tuple :prompt [project-name [tag payload]]
  (repl/append-to-output-editor project-name
                                (str (get payload 'clojure.core/*ns*)
                                     "> ")
                                :add-newline? false))

(defmethod handle-unrepl-tuple :unrepl.upgrade/failed [project-name [tag]]
  (repl/append-to-output-editor project-name "Unable to upgrade your REPL to Unrepl."))

(defmethod handle-unrepl-tuple :unrepl/hello [project-name [tag payload]]
  (let [{:keys [session actions]} payload]
    (console-log "Session is: " payload)))

(defn ^:private read-unrepl-stream
  "Read and process all the unrepl tuples in the given data string."
  [project-name data]
  (let [reader (string-push-back-reader (str data))]
    (loop []
      (let [msg (read {:eof ::eof
                       :readers {'unrepl/param identity
                                 'unrepl/ns identity
                                 'unrepl/string identity
                                 'unrepl/ratio identity
                                 'unrepl/meta identity
                                 'unrepl/pattern identity
                                 'unrepl/object identity
                                 'unrepl.java/class identity
                                 'unrepl/... #(if (nil? %) "nil" (identity %))
                                 'unrepl/lazy-error identity
                                 'clojure/var identity
                                 'error identity}}
                      reader)]
        (console-log (prn-str msg))
        (when (not= msg ::eof)
          (handle-unrepl-tuple project-name msg)
          (recur))))))

(defn send [connection code]
  (.write (:socket-connection connection) code))

(defn upgrade-connection-to-unrepl [project-name]
  (swap! repls update project-name #(assoc % :repl-type :repl-type/unrepl))
  (send (get-in @repls [project-name :connection]) unrepl-blob))

(defmethod repl/execute-code :repl-type/unrepl
  [project-name code & [namespace resent?]]
  (let [{:keys [connection session current-ns]} (get @repls project-name)]
    (repl/append-to-output-editor project-name code :add-newline? true)
    (add-repl-history project-name code)
    (send connection (str code "\n"))))

(defmethod repl/stop-process :repl-type/unrepl
  [project-name]
  (let [{:keys [connection lein-process]} (get @repls project-name)]
    (when connection
      (repl/close connection))))

(defn connect-to-remote-plain-repl [project-name host port]
  (let [conn (net.Socket.)]
    (.connect conn port host
              (fn []
                (swap! repls update project-name
                       #(assoc % :repl-type :repl-type/plain
                                 :connection {:socket-connection conn}))
                (when true ;; why wouldn't you want unrepl??
                  (upgrade-connection-to-unrepl project-name))))
    (.on conn "data"
         (fn [data]
           (if (= :repl-type/unrepl (get-in @repls [project-name :repl-type]))
             (read-unrepl-stream project-name data)
             (repl/append-to-output-editor project-name (str data) :add-newline? false))))

    (.on conn "close"
         (fn [] (console-log "connection closed")))))
