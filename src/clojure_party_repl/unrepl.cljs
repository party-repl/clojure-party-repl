(ns clojure-party-repl.unrepl
  (:require [cljs.nodejs :as node]
            [cljs.reader :refer [read]]
            [cljs.tools.reader.reader-types :refer [string-push-back-reader]]
            [clojure.string :as string]
            [oops.core :refer [oget oset! oset!+ ocall]]
            [clojure-party-repl.common :refer [console-log show-error repls
                                               add-repl-history]]
            [clojure-party-repl.bencode :as bencode]
            [clojure-party-repl.repl :as repl]
            [cljs.core.async :as async :refer [chan timeout close! <! >! alts!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def process (node/require "process"))
(def net (node/require "net"))
(def fs (node/require "fs"))

(def atom-home-directory (oget process ["env" "ATOM_HOME"]))

(fs.readFile (str atom-home-directory "/packages/clojure-party-repl/resources/unrepl/blob.clj")
   (fn [err data]
     (when err (console-log err))
     (def unrepl-blob data)))

(def ^:private ellipsis "...")
(def ^:private elision-key :get)

(defn ^:private has-elisions?
  "Elision is expressed as either `#unrepl/... nil` or
  `#unrepl/... {:get continuation-fn}` at the end of a collection. Elision allows
  us to have control over how much and when we want to show the details.

  There are a few places it could appear:
    1. Lazy sequence (0 1 2 3 4 5 6 7 8 9 {:get (continuation-fn :id)})
    2. Long string [prefix {:get (continuation-fn :id)}]
    3. Stacktraces [[], [], [], {:get (continuation-fn :id)}]

  The cutoff lengths are set as defaults inside unrepl.printer."
  [coll]
  (if (and (coll? coll)
           (or (and (satisfies? PersistentArrayMap coll)
                    (contains? coll elision-key))
               (contains? (last coll) elision-key)))
    true
    false))

(declare read-unrepl-stream)

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
  (let [{{:keys [interrupt background]} :actions} payload]
    (console-log "Noop unrepl tuple:" (pr-str tuple))))

(defmethod handle-unrepl-tuple :eval [project-name [tag payload group-id]]
  (repl/append-to-output-editor project-name (pr-str payload) :add-newline? true))

(defmethod handle-unrepl-tuple :bye [project-name [tag payload]]
  (let [{:keys [reason outs actions]} payload]
    (console-log "Bye for now because of " reason)))

(defmethod handle-unrepl-tuple :out [project-name [tag payload group-id]]
  (repl/append-to-output-editor project-name payload :add-newline? false))

(defmethod handle-unrepl-tuple :err [project-name [tag payload group-id]]
  (repl/append-to-output-editor project-name payload :add-newline? false))

;; The exception has a shape of:
;; {:ex {:cause String
;;       :via [{:type Exception
;;              :message String
;;              :at [StackTrace, String, at, {:get (continuation-fn)}]},
;;             ...,
;;             {:get (continuation-fn)}]
;;       :trace [[], [], ..., {:get (continuation-fn)}]}}
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
  (let [{:keys [session actions]} payload
        {:keys [start-aux exit set-source :unrepl.jvm/start-side-loader]} actions
        {:keys [connection port host]} (get @repls project-name)]
    (console-log "Upgraded to Unrepl: " session)
    (console-log "Available commands are: " (string/join " " [start-aux exit set-source start-side-loader]))
    (swap! repls update-in [project-name :connection]
           #(assoc % :actions {:exit (str exit)
                               :set-source (str set-source)}))))

(defn read-clojure-var [v]
  (symbol (str "#'" v)))

(defn read-string
  "When string is too long, it's represented as a tuple containing the prefix
  and elisions.

  TODO: Show elisions with continuation."
  [string]
  (cond
    (string? string) (identity string)
    (vector? string) (let [[prefix elisions] string]
                       (str prefix " " ellipsis " " elisions))))

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
                       :readers {'clojure/var read-clojure-var
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
  (.write connection code))

(defn upgrade-connection-to-unrepl [project-name]
  (swap! repls update project-name #(assoc % :repl-type :repl-type/unrepl))
  (send (get-in @repls [project-name :connection :socket-connection]) unrepl-blob))

(defn wrap-code-with-namespace
  "This removes the need to send a separate blob to check if the namespace
  exists or not.

  TODO: Use this for nrepl too?"
  [code namespace]
  (str "(do "
          "(when (clojure.core/find-ns '" namespace ")"
            "(ns " namespace "))"
          code ")\n"))

(defmethod repl/execute-code :repl-type/unrepl
  [project-name code & [{:keys [namespace line column]}]]
  (let [{:keys [connection session current-ns]} (get @repls project-name)
        {:keys [socket-connection actions]} connection
        {:keys [set-source exit]} actions]
    (repl/append-to-output-editor project-name code :add-newline? true)
    (add-repl-history project-name code)
    (when (and namespace set-source)
      (send socket-connection (string/replace set-source
                                              #":unrepl/sourcename|:unrepl/line|:unrepl/column"
                                              {":unrepl/sourcename" (str "\"" namespace "\"")
                                               ":unrepl/line" line
                                               ":unrepl/column" column})))
    (send socket-connection (if namespace
                              (wrap-code-with-namespace code namespace)
                              (str code "\n")))))

(defmethod repl/stop-process :repl-type/unrepl
  [project-name]
  (let [{:keys [connection lein-process]} (get @repls project-name)
        {:keys [socket-connection]} connection]
    (when socket-connection
      (.end socket-connection))))

(defn connect-to-remote-plain-repl [project-name host port]
  (let [conn (net.Socket.)]
    (.connect conn port host
              (fn []
                (swap! repls update project-name
                       #(assoc % :repl-type :repl-type/plain
                                 :connection {:socket-connection conn}
                                 :host host
                                 :port port))
                (upgrade-connection-to-unrepl project-name)))
    (.on conn "data"
         (fn [data]
           (if (= :repl-type/unrepl (get-in @repls [project-name :repl-type]))
             (read-unrepl-stream project-name data)
             (repl/append-to-output-editor project-name (str data) :add-newline? false))))
    (.on conn "error"
         (fn [error]
           (show-error error " Cannot connect to Socket Repl. Please make sure Socket Repl is running at port " port ".")))
    (.on conn "close"
         (fn [] (console-log "Socket connection closed")))))
