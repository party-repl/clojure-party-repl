(ns clojure-party-repl.hidden-editor
  (:require [clojure.string :refer [trim-newline]]
            [goog.crypt.base64 :as base64]
            [cljs.nodejs :as node]
            [cljs.reader :refer [read-string]]
            [clojure-party-repl.strings :refer [hidden-editor-title]]
            [clojure-party-repl.common :as common :refer [repls
                                                          state
                                                          close-editor
                                                          add-subscription
                                                          console-log]]))

(def node-atom (node/require "atom"))
(def Point (.-Point node-atom))

(def resize-handle-tag-name "ATOM-PANE-RESIZE-HANDLE")

(defn encode-base64 [text]
  (base64/encodeString text))

(defn decode-base64 [text]
  (base64/decodeString (trim-newline text)))

(def state-types #{:repl-history
                   :execution-code})

(defn find-state-type-for-row
  "Returns the state type for the buffer change. "
  [hidden-editor row-number]
  (when (< 0 row-number)
    (loop [row (dec row-number)]
      (let [text-in-line (read-string (trim-newline (.lineTextForBufferRow hidden-editor row)))]
        (console-log "Seaching for state at row" row text-in-line)
        (if (contains? state-types text-in-line)
          text-in-line
          (when (< 0 row)
            (recur (dec row))))))))

(defn find-first-row-for-state-type
  "Returns the row number of the first item for the state type."
  [hidden-editor state-type]
  (let [end-row (.-row (.getEndPosition (.getBuffer hidden-editor)))]
    (loop [row 0]
      (let [text-in-line (read-string (trim-newline (.lineTextForBufferRow hidden-editor row)))]
        (console-log "Searching for state" state-type text-in-line)
        (if (= state-type text-in-line)
          (inc row)
          (when (< row end-row)
            (recur (inc row))))))))

(defn find-last-row-for-state-type
  "Returns the row number of the last item for the state type."
  [hidden-editor state-type])

(defn ^:private hidden-editor? [item]
  (contains? (get @state :hidden-editors) item))

(defn ^:private get-next-pane [pane]
  (let [panes (.getPanes (.getActivePaneContainer (.-workspace js/atom)))
        current-index (.indexOf panes pane)
        next-index (mod (inc current-index) (inc (.-length panes)))]
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
                                      (.moveItemToPane hidden-pane item (get-next-pane hidden-pane))))))]))

(defn ^:private hide-resize-handle [pane-element]
  (let [handle-element (.-nextSibling pane-element)]
    (when (= resize-handle-tag-name (.-tagName handle-element))
      (set! (.-id handle-element) "hidden-resize-handle"))))

(defn ^:private initialize-hidden-state [hidden-editor]
  (doseq [state-type state-types]
    (.insertText hidden-editor (str state-type "\n\n"))))

(defn get-all-repl-history [hidden-editor]
  )

(defn ^:private add-change-when-repl-history
  "Adds the code to the repl history stored in the local state if the change is
  related to it."
  [project-name hidden-editor change]
  (when (and (.-newRange change) (.-newText change))
    (let [row (.-row (.-start (.-newRange change)))
          state-type (find-state-type-for-row hidden-editor row)]
      (console-log "Change is for" state-type (.-newText change))
      (when (= state-type :repl-history)
        (let [code (decode-base64 (.-newText change))]
          (swap! repls update-in [project-name :repl-history] #(conj % code))
          (swap! repls update project-name #(assoc % :current-history-index -1)))))))

(defn ^:private update-local-history-state
  ""
  [project-name hidden-editor changes]
  (console-log "Hidden editor changes" changes)
  (doseq [change changes]
    (add-change-when-repl-history project-name hidden-editor change)))

(defn add-change-listener
  "Watches for any changes and takes action accordingly."
  [project-name hidden-editor]
  (add-subscription project-name
                    (.onDidStopChanging hidden-editor
                                        (fn [event]
                                          (update-local-history-state project-name hidden-editor (.-changes event))))))

(defn open-in-hidden-pane [hidden-editor & {:keys [moved?]}]
  (let [hidden-pane (get @state :hidden-pane)]
    (swap! state update :hidden-editors #(conj % hidden-editor))
    (if moved?
      (let [current-pane (.paneForItem (.-workspace js/atom) hidden-editor)]
        (.moveItemToPane current-pane hidden-editor hidden-pane)
        (.activateItem hidden-pane hidden-editor)
        (.activate hidden-pane))
      (do
        (.addItem hidden-pane hidden-editor (js-obj "moved" moved?))
        (.setActiveItem hidden-pane hidden-editor)))))

(defn create-hidden-editor []
  (let [editor (.buildTextEditor (.-workspace js/atom)
                                 (js-obj "autoHeight" false))]
    (set! (.-isModified editor) (fn [] false))
    (set! (.-isModified (.getBuffer editor)) (fn [] false))
    (.setSoftWrapped editor false)
    (.add (.-classList (.-element editor)) "hidden-editor")
    (initialize-hidden-state editor)
    editor))

(defn remove-hidden-editor [project-name type]
  (when-let [hidden-editor (get-in @repls [project-name type])]
    (swap! state update :hidden-editors #(disj % hidden-editor))))

(defn delete-row [hidden-editor row]
  (.deleteRow (.getBuffer hidden-editor) row))

(defn insert-text-at-row
  "Inserts text in the hidden editor at the row by moving the cursor to the row
  and then inserting text."
  [hidden-editor text row]
  (.insert (.getBuffer hidden-editor)
           (Point. row 0)
           (str (encode-base64 text) "\n")
           (js-obj "bypassReadOnly" true)))
  ;(doto hidden-editor
  ;  (.setCursorBufferPosition (array row 0) (js-obj "autoscroll" false))
  ;  (.insertText text (js-obj "bypassReadOnly" true))))

(defn update-hidden-state [hidden-editor state-type text]
  (let [row (find-first-row-for-state-type hidden-editor state-type)]
    (console-log "Inserting in hidden state at row" row)
    (insert-text-at-row hidden-editor text row)))

(defn add-repl-history [project-name code]
  (let [hidden-editor (get-in @repls [project-name :host-hidden-editor])]
    (update-hidden-state hidden-editor :repl-history code)))

(defn ^:private add-dummy-editor [hidden-pane]
  (let [dummy-editor (create-hidden-editor)]
    (swap! state assoc :hidden-dummy-editor dummy-editor)
    (.addItem hidden-pane dummy-editor)))

(defn destroy-hidden-pane
  "Destroys the Pane. If this is the last Pane, all the items inside it will be
  destroyed but the pane will not be destroyed. Would this ever happen?"
  []
  (when-let [hidden-pane (get @state :hidden-pane)]
    (when-let [dummy-editor (get @state :hidden-dummy-editor)]
      (close-editor dummy-editor)
      (swap! state assoc :hidden-dummy-editor nil))
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
        pane-element (.getElement hidden-pane)]
    (set! (.-id pane-element) "clojure-party-repl-hidden-pane")
    (add-dummy-editor hidden-pane)
    (hide-resize-handle pane-element)
    (swap! state assoc :hidden-pane hidden-pane)
    (add-listeners hidden-pane)
    (.activate pane)))
