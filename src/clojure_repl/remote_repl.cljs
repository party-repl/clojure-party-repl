(ns clojure-repl.remote-repl
  (:require [cljs.nodejs :as node]
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

(def ^:private ui-components (atom {:panel nil
                                    :host-input nil
                                    :port-input nil}))

(defn ^:private add-connection-input-listeners
  "Adds a keydown listener to intercept Atom's default behavior
  and switch between the inputs. Since there are only two
  inputs, we don't need to worry about behavior for shift-tab
  since it's identical.

  An altenative way to implement this (how the find-and-replace
  does it), would be to export this function as a command and
  create a keymap with a selector which specifically targets these
  inputs."
  [panel input-a input-b]
  (letfn [(handle-keydown [event]
            (case (.-key event)
              "Tab" (do
                      (if (.hasFocus input-a)
                        (.focus input-b)
                        (.focus input-a))
                      (.stopPropagation event)
                      (.preventDefault event))
              "Escape" (do
                         (.hide panel)
                         (.stopPropagation event)
                         (.preventDefault event))
              nil))]
    (.addEventListener input-a "keydown" handle-keydown)
    (.addEventListener input-b "keydown" handle-keydown)))

(defn create-connection-modal-panel []
  (let [default-host "localhost" ;; TODO: Add a configurable setting for this?
        default-port "" ;; TODO: Add a configurable setting for this?
        container (.createElement js/document "section")
        header (doto (.createElement js/document "h4")
                     (.setAttribute "class" "icon icon-clob"))
        host-container (doto (.createElement js/document "div")
                             (.setAttribute "class" "block"))
        host-label (.createElement js/document "div")
        host-subview (.createElement js/document "subview")
        host-input (doto (.createElement js/document "atom-text-editor")
                         (.setAttribute "mini" true)
                         (.setAttribute "placeholder-text" default-host)
                         (.setAttribute "tabindex" -1))
        port-container (doto (.createElement js/document "div")
                             (.setAttribute "class" "block"))
        port-label (.createElement js/document "div")
        port-subview (.createElement js/document "subview")
        port-input (doto (.createElement js/document "atom-text-editor")
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
    (.appendChild host-subview host-input)
    (.appendChild container port-container)
    (.appendChild port-container port-label)
    (.appendChild port-container port-subview)
    (.appendChild port-subview port-input)
    (let [panel (-> (.-workspace js/atom)
                    (.addModalPanel (clj->js {"item" container
                                              "visible" false})))]
      (add-connection-input-listeners panel host-input port-input)
      (swap! ui-components assoc :panel panel
                                 :host-input host-input
                                 :port-input port-input))))

(defn show-connection-modal-panel []
  (.show (get @ui-components :panel))
  (.focus (get @ui-components :host-input)))
