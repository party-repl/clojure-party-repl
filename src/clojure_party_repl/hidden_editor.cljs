(ns clojure-party-repl.hidden-editor
  (:require [clojure.string :as string]
            [cljs.reader :refer [read-string]]
            [clojure-party-repl.strings :refer [hidden-editor-title]]
            [clojure-party-repl.common :as common :refer [repls
                                                          state
                                                          add-subscription
                                                          console-log]]))

(def initial-state (pr-str {:repl-history (list)}))

(def resize-handle-tag-name "ATOM-PANE-RESIZE-HANDLE")

(defn ^:private hidden-editor? [item]
  (contains? (get @state :hidden-editors) item))

(defn ^:private get-next-pane [pane]
  (let [panes (.getPanes (.getActivePaneContainer (.-workspace js/atom)))
        current-index (.indexOf panes pane)
        next-index (mod (inc current-index) (.-length panes))]
    (aget panes next-index)))

(defn ^:private add-listeners
  "This Pane should only contain our hidden buffers. When other editors
  accidently get placed in here, we want to move them to the next available
  Pane."
  [hidden-pane]
  (swap! state update :disposables concat
    [(.onDidAddItem hidden-pane (fn [event]
                                  (let [item (.-item event)]
                                    (when-not (hidden-editor? item)
                                      (console-log "Moving item to the next pane!" item)
                                      (.removeItem hidden-pane item true)
                                      (.addItem (get-next-pane hidden-pane) item (js-obj "moved" true))))))
     (.onDidActivate hidden-pane (fn [& args]
                                   (console-log "Activating next pane!")
                                   (.activateNextPane (.-workspace js/atom))))]))

(defn ^:private hide-resize-handle [pane-element]
  (let [handle-element (.-nextSibling pane-element)]
    (when (= resize-handle-tag-name (.-tagName handle-element))
      (set! (.-id handle-element) "hidden-resize-handle"))))

(defn open-in-hidden-pane [hidden-editor & {:keys [moved?]}]
  (let [hidden-pane (get @state :hidden-pane)]
    (swap! state update :hidden-editors #(conj % hidden-editor))
    (.addItem hidden-pane hidden-editor (js-obj "moved" moved?))))

(defn create-hidden-editor []
  (let [editor (.buildTextEditor (.-workspace js/atom)
                                 (js-obj "autoHeight" false))]
    (set! (.-isModified editor) (fn [] false))
    (set! (.-isModified (.getBuffer editor)) (fn [] false))
    (.setSoftWrapped editor false)
    (.add (.-classList (.-element editor)) "hidden-editor")
    (.setText editor initial-state)
    editor))

(defn remove-hidden-editor [project-name type]
  (when-let [hidden-editor (get-in @repls [project-name type])]
    (swap! state update :hidden-editors #(disj % hidden-editor))))

(defn read-hidden-editor
  "Returns the state in the edn format written on the hidden editor or nil."
  [hidden-editor]
  (when-not (and hidden-editor (.isDestroyed hidden-editor))
    (read-string (.getText hidden-editor))))

(defn update-hidden-state
  ""
  [project-name hidden-editor new-state]
  (when-let [hidden-state (read-hidden-editor hidden-editor)]
    (doseq [state-type (keys new-state)]
      (when-not (= (hidden-state state-type) (new-state state-type))
        (.setText hidden-editor (pr-str (assoc hidden-state state-type (new-state state-type))))))))

(defn update-local-state
  ""
  [project-name hidden-editor]
  (when-let [hidden-state (read-hidden-editor hidden-editor)]
    (doseq [state-type (keys hidden-state)]
      (when-not (= (hidden-state state-type) (get-in @repls [project-name state-type]))
        (swap! repls update project-name #(assoc % state-type (hidden-state state-type)))))))

(defn add-change-listener [project-name hidden-editor]
  (add-subscription project-name
                    (.onDidStopChanging hidden-editor
                                        (fn [event]
                                          (update-local-state project-name hidden-editor)))))

(defn destroy-hidden-pane
  "Destroys the Pane. If this is the last Pane, all the items inside it will be
  destroyed but the pane will not be destroyed. Would this ever happen?"
  []
  (when-let [hidden-pane (get @state :hidden-pane)]
    (.destroy hidden-pane)
    (swap! state assoc :hidden-pane nil)))

;; TODO: When Atom starts/reloads, it always opens the Project View to
;;       the left now. Investigate it!
(defn create-hidden-pane
  "Creates a new Pane to the very left of the PaneContainer. Since Atom will
  always have at least one Pane, even when you haven't opened a file,
  we can safely create it in all starting cases.

  We need to add a dummy editor, otherwise the pane will be destroyed
  when there're no editors in it."
  []
  (let [pane (.getActivePane (.-workspace js/atom))
        left-most-pane (.findLeftmostSibling pane)
        hidden-pane (.splitLeft left-most-pane)
        pane-element (.getElement hidden-pane)
        dummy-editor (create-hidden-editor)]
    (set! (.-id pane-element) "clojure-party-repl-hidden-pane")
    (.addItem hidden-pane dummy-editor)
    (hide-resize-handle pane-element)
    (swap! state assoc :hidden-pane hidden-pane)
    (add-listeners hidden-pane)
    (.activate pane)))
