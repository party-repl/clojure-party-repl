(ns clojure-repl.remote-repl
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!] :as async]
            [clojure.string :as string]
            [clojure-repl.common :refer [console-log]]
            [clojure-repl.repl :as repl :refer [repl-state
                                                stop-process
                                                connect-to-nrepl]]
            [clojure-repl.strings :as strings]))

(def ashell (node/require "atom"))

(defn connect-to-remote-repl [{:keys [host port]}]
  (stop-process)
  (swap! repl-state assoc :host host
                          :port port
                          :lein-process :remote
                          :init-code "(.name *ns*)")
  (connect-to-nrepl))

(def ui-components (atom {:panel nil
                          :host-editor nil
                          :port-editor nil}))

(def ^:private panel-channel (chan))

(defn create-connection-modal-panel []
  (let [default-host "localhost" ;; TODO: Add a configurable setting for this
        default-port "" ;; TODO: Add a configurable setting for this
        container (.createElement js/document "section")
        header (doto (.createElement js/document "h4")
                     (.setAttribute "class" "icon icon-clob"))
        host-container (doto (.createElement js/document "div")
                             (.setAttribute "class" "block"))
        host-label (.createElement js/document "div")
        host-subview (.createElement js/document "subview")
        host-editor (doto (.createElement js/document "atom-text-editor")
                         (.setAttribute "mini" true)
                         (.setAttribute "placeholder-text" default-host)
                         (.setAttribute "tabindex" -1))
        port-container (doto (.createElement js/document "div")
                             (.setAttribute "class" "block"))
        port-label (.createElement js/document "div")
        port-subview (.createElement js/document "subview")
        port-editor (doto (.createElement js/document "atom-text-editor")
                         (.setAttribute "mini" true)
                         (.setAttribute "placeholder-text" default-port)
                         (.setAttribute "tabindex" -1))]
    (set! (.-innerText header) strings/remote-repl-header)
    (set! (.-innerText host-label) strings/remote-repl-host)
    (set! (.-innerText port-label) strings/remote-repl-port)
    (.appendChild container header)
    (.appendChild container host-container)
    (.appendChild host-container host-label)
    (.appendChild host-container host-subview)
    (.appendChild host-subview host-editor)
    (.appendChild container port-container)
    (.appendChild port-container port-label)
    (.appendChild port-container port-subview)
    (.appendChild port-subview port-editor)
    (let [panel (-> (.-workspace js/atom)
                    (.addModalPanel (clj->js {"item" container
                                              "visible" false})))]
      (-> (.-commands js/atom)
          (.add container
                "core:confirm"
                (fn [event] ; todo include default values here
                  (let [host (-> (.getModel host-editor)
                                 (.getText))
                        port (-> (.getModel port-editor)
                                 (.getText)
                                 (int))]
                    (when (and (pos? (count host))
                               (pos? port))
                      (.hide panel)
                      (async/put! panel-channel [host port]))))))
      (-> (.-commands js/atom)
          (.add container
                "core:cancel"
                (fn [event]
                  (println "here")
                  (async/put! panel-channel false))))
      (swap! ui-components assoc :panel panel
                                 :host-editor host-editor
                                 :port-editor port-editor))))

(defn show-connection-modal-panel []
  (let [{:keys [:panel :host-editor :port-editor]} @ui-components]
    (go
      (if-not (.-visible panel)
        (do
          (.setText (.getModel host-editor) "")
          (.setText (.getModel port-editor) "")
          (.show panel)
          (.focus host-editor)
          (<! panel-channel))
        (do
          (.focus host-editor)
          false)))))
