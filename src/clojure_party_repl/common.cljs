(ns clojure-party-repl.common
  (:require [cljs.nodejs :as node]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.set :as set]
            [goog.crypt.base64 :as base64]))

(def node-atom (node/require "atom"))
(def fs (node/require "fs"))
(def CompositeDisposable (.-CompositeDisposable node-atom))

(def max-history-count 100)

;; A map with project-name to TextEditor
(def repls (atom {}))

;; Template for repl's state
(def repl-state
  {:current-working-directory ""
   :process-env nil
   :repl-process nil
   :connection nil
   :session nil
   :host "localhost"
   :port nil
   :current-ns "user"
   :init-code nil
   :repl-type nil
   :subscriptions nil
   :process nil
   :host-input-editor nil
   :host-output-editor nil
   :host-hidden-editor nil
   :guest-input-editor nil
   :guest-output-editor nil
   :guest-hidden-editor nil
   :connected-guests #{}
   :repl-history (list)
   :current-history-index -1})

(def state
  (atom {:disposables []
         :lein-path ""
         :most-recent-repl-project-name nil
         :hidden-pane nil
         :hidde-dummy-editor nil
         :hidden-editors #{}}))

;; NOTE: When this is true, all output will be printed to the Console. In order
;; to turn this on, change it to true and recompile. You can also change it
;; through the ClojureScript REPL for the plugin.
(def in-dev-mode? (atom true))

;; TODO: Add a :force-print true arg? to output even in development mode
(defn console-log
  "Used for development. The output can be viewed in the Atom's Console when in
  Dev Mode."
  [& output]
  (when (true? @in-dev-mode?)
    (apply (.-log js/console) output)))

(defn show-error [& error]
  (apply (.-error js/console) error)
  (.addError (.-notifications js/atom) (apply str error)))

(defn add-subscription
  "This should be wrapped whenever adding any subscriptions in order to dispose
  them later."
  [project-name disposable]
  (.add (get-in @repls [project-name :subscriptions]) disposable))

(defn add-buttons [project-name editor buttons]
  (console-log "Adding action buttons:" buttons)
  (let [editor-element (.-element editor)
        button-container (doto (.createElement js/document "div")
                               (.setAttribute "class" "button-container"))]
    (doseq [[title callback] buttons]
      (let [button (doto (.createElement js/document "button")
                         (.setAttribute "class" (str (if (= title "execute")
                                                          "btn btn-primary "
                                                          "btn ")
                                                      title)))]
        (set! (.-innerText button) title)
        (.appendChild button-container button)
        (.addEventListener button "click" (partial callback project-name editor))))
    (.appendChild editor-element button-container)))

(defn ^:private get-site-id [site-positions-component-site]
  (let [class-name (.-className site-positions-component-site)]
    (when-let [site-id (re-find #"\d+" class-name)]
      site-id)))

(defn ^:private get-connected-guests
  "Returns a set of guests' id's connected by Teletype or nil. Teletype injects
  a DOM element with a classname SitePositionsComponent inside the TextEditor
  when it's active or focused.

  TODO: There may be up to three of the components at the top, middle, and
        bottom, so we need to count them from all.
        Also, each component might only show up to three avatars and if there's
        more people connected, we will be missing them."
  [project-name]
  (let [{:keys [host-input-editor host-output-editor]} (get @repls project-name)
        active-pane-item (.getActivePaneItem (.-workspace js/atom))
        active-editor (some #(when (= active-pane-item %) %) [host-input-editor host-output-editor])]
    (when active-editor
      (let [children (.-children (.-element active-editor))
            site-positions-component (some #(when (.contains (.-classList %) "SitePositionsComponent") %) (array-seq children))]
        (console-log "Checking new guests:" site-positions-component)
        (when site-positions-component
          (console-log "Avatars:" site-positions-component)
          (set (map get-site-id (array-seq (.-children site-positions-component)))))))))

(defn new-guest-detected?
  "Returns true if connected guests contain a site-id that doesn't exist in the current
  guests."
  [project-name]
  (if-let [connected-guests (get-connected-guests project-name)]
    (let [current-guests (get-in @repls [project-name :connected-guests])
          detected-new-guests (set/difference connected-guests current-guests)]
      (console-log "Guest Count:" current-guests "=>" connected-guests)
      (when-not (empty? detected-new-guests)
        (swap! repls update-in [project-name :connected-guests] #(set/union % detected-new-guests))
        true))
    false))

(defn encode-base64 [text]
  (base64/encodeString text))

(defn decode-base64 [text]
  (try
    (base64/decodeString (string/trim-newline text))
    (catch js/Error e
      (console-log "ERROR Could not decode because not encoded in base64" text))))

(defn show-current-history
  "Replaces the content of the input-editor with one of the executed commands in
  the history at the current history index."
  [project-name input-editor]
  (let [current-history-index (get-in @repls [project-name :current-history-index])]
    (if (> 0 current-history-index)
      (.setText input-editor "")
      (when (> (count (get-in @repls [project-name :repl-history]))
               current-history-index)
        (.setText input-editor
                  (nth (get-in @repls [project-name :repl-history])
                       current-history-index))))))

(defn update-current-history-index
  "Updates the current history index to the new value locally. When on host,
  replaces the content of the input editor with the code in the repl history
  at the index."
  [project-name hidden-editor change]
  (let [new-index (js/parseInt (decode-base64 (string/trim-newline (.-newText change))))]
    (console-log "Updating local current history index to" new-index)
    (swap! repls assoc-in [project-name :current-history-index] new-index)
    (when-let [host-input-editor (get-in @repls [project-name :host-input-editor])]
      (show-current-history project-name host-input-editor))))

(defn update-repl-history
  "Adds the code to the repl history in the local state."
  [project-name hidden-editor change]
  (let [code (decode-base64 (string/trim-newline (.-newText change)))]
    (console-log "Adding into local history" code)
    (swap! repls update-in [project-name :repl-history] #(conj % code))))

;; TODO: Warn user when project.clj doesn't exist in the project.
(defn get-project-clj [project-path]
  (let [project-clj-path (str project-path "/project.clj")]
    (console-log "Looking for project.clj at " project-clj-path " - " (.existsSync fs project-clj-path))
    (.existsSync fs project-clj-path)))

(defn get-project-directory-from-path [root-project-path file-path]
  (loop [directories (butlast (string/split file-path #"/"))
         project-path root-project-path]
    (if (get-project-clj project-path)
      project-path
      (when (coll? directories)
        (recur (next directories) (str project-path "/" (first directories)))))))

;; TODO: Support having nested project folders. Right now it assumes that each
;;       project folder that's opened in Atom is an independent project. It
;;       should, however, allow user to open one big folder that contains
;;       multiple projects.
(defn ^:private get-project-path
  "Returns the project path of the given editor or nil if there is no project
  associated with the editor."
  ([]
   (get-project-path (.getActiveTextEditor (.-workspace js/atom))))
  ([text-editor]
   (when text-editor
     (let [path (.getPath (.getBuffer text-editor))
           [directory-path, relative-path] (.relativizePath (.-project js/atom) path)]
     (when directory-path
       (console-log "----Project---->" directory-path " - " relative-path)
       (get-project-directory-from-path directory-path relative-path))))))

(defn get-active-project-path
  "Returns the path of the project that corresponds to the active editor or nil
  if no project is associated with the active text editor."
  []
  (get-project-path))

(defn get-project-name-from-path
  "Returns the project name from the given path or nil."
  [project-path]
  (when project-path
    (last (string/split project-path #"/"))))

(defn get-active-project-name
  []
  (get-project-name-from-path (get-active-project-path)))

(defn get-project-name-from-text-editor
  "Returns the project name from the path of the text editor."
  [editor]
  (when-let [project-path (get-project-path editor)]
    (get-project-name-from-path project-path)))

(defn get-project-name-from-input-editor
  "Returns the project name if there's a reference to the input editor."
  [editor]
  (some (fn [project-name]
          (console-log "Checking if repl exists for the project: " project-name)
          (when (or (= editor (get-in @repls [project-name :guest-input-editor]))
                    (= editor (get-in @repls [project-name :host-input-editor])))
            project-name))
        (keys @repls)))

(defn get-project-name-from-most-recent-repl
  "Returns a project name for the most recently used repl if it still exists."
  []
  (when-let [project-name (get @state :most-recent-repl-project-name)]
    (when (or (get-in @repls [project-name :host-input-editor])
              (get-in @repls [project-name :guest-input-editor]))
      project-name)))

(defn visible-repl? [text-editor]
  (when (and text-editor (.-element text-editor))
    (not= "none" (.-display (.-style (.-element text-editor))))))

(defn get-project-name-from-visible-repl []
  (some #(when (or (visible-repl? (get-in @repls [% :host-input-editor]))
                   (visible-repl? (get-in @repls [% :guest-input-editor])))
            %)
        (keys @repls)))

(defn add-repl [project-name & options]
  (swap! repls assoc project-name (-> (apply assoc repl-state options)
                                      (assoc :subscriptions (CompositeDisposable.)))))

(defn close-editor
  "Searches through all the panes for the editor and destroys it."
  [editor]
  (when-let [pane (.paneForItem (.-workspace js/atom) editor)]
    (.destroyItem pane editor true)))

(defn destroy-editor
  "Destroys an editor defined in the state."
  [project-name editor-keyword]
  (when-let [editor (get-in @repls [project-name editor-keyword])]
    (close-editor editor)
    (swap! repls update project-name #(assoc % editor-keyword nil))))

(defn dispose-project-if-empty
  "Remove the project state if there's no running repls for the project name."
  [project-name]
  (when-not (or (get-in @repls [project-name :host-input-editor])
                (get-in @repls [project-name :guest-input-editor]))
    (swap! repls dissoc project-name)
    (when (= project-name (get @state :most-recent-repl-project-name))
      (swap! state assoc :most-recent-repl-project-name nil))))

;; TODO: Pretty print results
(defn append-to-editor
  "Appends text at the end of the editor. Always append a newline following the
  text unless specified not to. If the last line only contains whitespaces,
  delete all whitespaces of the line in order to ignore them. Otherwise, any
  text appended after that gets indented to the right."
  [editor output & {:keys [add-newline?] :or {add-newline? true}}]
  (when editor
    (let [text (if add-newline?
                 (str output "\n")
                 output)]
      (.moveToBottom editor)
      (when (re-find #"^\s+$" (.getLastLine (.getBuffer editor)))
        (.deleteToBeginningOfLine editor (js-obj "bypassReadOnly" true)))
      (.insertText editor text (js-obj "bypassReadOnly" true))
      (.scrollToBottom (.-element editor))
      (.moveToBottom editor))))
