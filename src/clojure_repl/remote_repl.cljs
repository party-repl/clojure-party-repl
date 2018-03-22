(ns clojure-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :refer [console-log]]
            [clojure-repl.repl :as repl :refer [repl-state
                                                stop-process
                                                connect-to-nrepl]]))

(defn connect-to-remote-repl [address]
  (let [{:keys [host port]} address]
    (stop-process)
    (swap! repl-state assoc :host host
                            :port port
                            :lein-process :remote
                            :init-code "(.name *ns*)")
    (connect-to-nrepl)))
