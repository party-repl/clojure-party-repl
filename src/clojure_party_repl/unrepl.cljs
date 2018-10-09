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

;; TODO: Make it readable from the Atom package
(fs.readFile (str  "/clojure-party-repl/resources/unrepl/blob.clj")
   (fn [err data]
     (when err (console-log err (ocall process "cwd")))
     (def unrepl-blob data)))

(def ^:private ellipsis "...")
(def ^:private elision-key :get)

(defn ^:private has-elision?
  "Elision is expressed as either `#unrepl/... nil` or
  `#unrepl/... {:get continuation-fn}` at the end of a collection. Elision allows
  us to have control over how much and when we want to show the details.

  There are a few places it could appear:
    1. Lazy sequence
    2. Long string
    3. Stacktraces

  Elision can either be at the end of a collection"
  [coll]
  (if (and (coll? coll)
           (or (and (satisfies? PersistentArrayMap coll)
                    (contains? coll elision-key))
               (last coll)))
    true
    false))

(defmulti handle-unrepl-tuple
  (fn [project-name tuple]
    (if-not (vector? tuple)
      ::not-a-tuple
      (let [[tag payload group-id] tuple]
        tag))))

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

;; The exception has a shape of:
;; {:ex {:cause String
;;       :via [{:type Exception
;;              :message String
;;              :at [StackTrace, String, at, {...get}]}, ...]
;;       :trace [[], [], ..., {...get}]}}
;;  :phase :eval/}
(defmethod handle-unrepl-tuple :exception [project-name [tag payload :as tuple]]
  (let [{:keys [ex phase]} payload
        {:keys [cause via trace]} ex
        [{:keys [type _ _]} _] via]
    (repl/append-to-output-editor project-name
                                  (string/join " " [type cause (-> trace
                                                                   (get 0 [])
                                                                   (get 2 ""))])
                                  :add-newline? true)
    (doseq [[_ _ at] (vec (butlast (next trace)))] ;; TODO: The last item is elisions map. Show "..."?
      (when-not (coll? at)
        (repl/append-to-output-editor project-name
                                      (str \tab at)
                                      :add-newline? true)))))

(defmethod handle-unrepl-tuple :prompt [project-name [tag payload]]
  (repl/append-to-output-editor project-name
                                (str (get payload 'clojure.core/*ns*)
                                     "> ")
                                :add-newline? false))

(defmethod handle-unrepl-tuple :unrepl.upgrade/failed [project-name [tag]]
  (repl/append-to-output-editor project-name "Unable to upgrade your REPL to Unrepl."))

(defmethod handle-unrepl-tuple :unrepl/hello [project-name [tag payload]]
  (let [{:keys [session actions]} payload]
    (console-log "Session is: " session)))

(defn read-string [string]
  (cond
    (string? string) (identity string)
    (coll? string) (let [[prefix elisions] string]
                     (str prefix " " ellipsis))))

(defn read-ratio [[a b]]
  (symbol (str a "/" b)))

(defn read-object [[class id representation :as object]]
  (identity object))

(defn read-elision
  "Elision can either be nil or a map with the continuation function associated
  to the :get key."
  [elisions]
  (if (nil? elisions)
    ellipsis
    (identity elisions)))

(defn read-lazy-error
  "An exception #unrepl/lazy-error is inlined in a sequence when
  realization of a lazy sequence throws.

  For example, as stated in unrepl spec:
  (map #(/ %) (iterate dec 3))
  will return
  (#unrepl/ratio [1 3] #unrepl/ratio [1 2] 1 #unrepl/lazy-error #error {:cause \"Divide by zero\", :via [{:type #unrepl.java/class java.lang.ArithmeticException, :message \"Divide by zero\", :at #unrepl/object [#unrepl.java/class java.lang.StackTraceElement \"0x272298a\" \"clojure.lang.Numbers.divide(Numbers.java:158)\"]}], :trace [#unrepl/... nil]})

  TODO: Also show a stacktrace below the result?"
  [{:keys [cause via trace]}]
  (let [[{:keys [type message _]} _] via]
    (apply console-log cause)
    (string/join " " [type message (-> trace
                                       (get 0 [])
                                       (get 2 ""))])))

(defn ^:private read-unrepl-stream
  "Read and process all the unrepl tuples in the given data string.

  For each supported tagged literal, we need to provide a reader function. If
  not provided, \"No reader function for tag error.\" will be thrown."
  [project-name data]
  (let [reader (string-push-back-reader (str data))]
    (loop []
      (let [msg (read {:eof ::eof
                       :readers {'clojure/var identity
                                 'unrepl/param identity
                                 'unrepl/ns identity
                                 'unrepl/string read-string
                                 'unrepl/ratio read-ratio
                                 'unrepl/meta identity
                                 'unrepl/pattern identity
                                 'unrepl/object read-object
                                 'unrepl/mime identity
                                 'unrepl.java/class identity
                                 'unrepl/... read-elision
                                 'unrepl/lazy-error read-lazy-error
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
