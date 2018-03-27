(ns clojure-repl.remote-repl
  (:require [cljs.nodejs :as node]
            [clojure-repl.common :refer [console-log]]
            [clojure-repl.repl :as repl :refer [add-repl
                                                stop-process
                                                connect-to-nrepl
                                                show-error]]))

(defn connect-to-remote-repl [address]
  (let [{:keys [host port]} address]
    (if-not (contains? :remote-repl repls)
      (do
        (add-repl :remote-repl
                  :host host
                  :port port
                  :lein-process :remote
                  :init-code "(.name *ns*)"
                  :type :nrepl
                  :subscriptions (CompositeDisposable.))
        (connect-to-nrepl :remote-repl))
      (show-error "There's already a remote REPL connected! Only one remote REPL is allowed."))))
