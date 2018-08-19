(ns clojure-repl.connection-panel
  "Creates a model connection panel for prompting the user when connecting
  to a remote repl."
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan <!] :as async]
            [clojure.string :as string]
            [clojure-repl.strings :as strings]
            [clojure-repl.common :as common]))

(def ^:private ui-components
  "A container for holding all of the ui components, both
  elements and Atom editor objects."
  (atom {}))

(def ^:private address-channel
  "A channel for asynchronously returning socket address to the caller
  that opens the panel."
  (chan))

(defn ^:private get-text
  "Reads the text value from an element. If the element is
  an atom-text-editor, then it returns the editor's content
  or the placeholder attribute text if editor is blank."
  [element]
  (if (= (string/lower-case (.-tagName element))
         "atom-text-editor")
    (let [text (-> (.getModel element)
                   (.getText)
                   (string/trim))]
      (if-not (string/blank? text)
        text
        (.getAttribute element "placeholder-text")))
    (.-innerText element)))

(defn ^:private get-option-value
  "Returns the selected value in the select element."
  [select-element]
  (.-value (aget (.-options select-element) (.-selectedIndex select-element))))

(defn ^:private set-text
  "Sets the text value for an element or an atom-text-editor."
  [element text]
  (if (= (string/lower-case (.-tagName element))
         "atom-text-editor")
    (.setText (.getModel element) text)
    (set! (.-innerText element) text)))

(defn ^:private set-text-color
  "Sets the text color style on an element"
  [element value]
  (set! (.-color (.-style element)) value))

(defn ^:private create-connection-panel-dom
  "Builds the DOM for the modal panel and returns a map of UI components to be
  stored in the ui-components atom."
  []
  (let [default-host "localhost"
        default-port ""
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
                          (.setAttribute "tabindex" -1))
        project-container (doto (.createElement js/document "div")
                                (.setAttribute "class" "block"))
        project-label (.createElement js/document "div")
        project-subview (.createElement js/document "subview")
        project-select (doto (.createElement js/document "select")
                             (.setAttribute "tabindex" -1)
                             (set-text-color "black"))]
    (set-text host-label strings/connection-panel-host)
    (set-text port-label strings/connection-panel-port)
    (set-text project-label strings/connection-panel-project)
    (.appendChild container header)
    (.appendChild container host-container)
    (.appendChild host-container host-label)
    (.appendChild host-container host-subview)
    (.appendChild host-subview host-editor)
    (.appendChild container port-container)
    (.appendChild port-container port-label)
    (.appendChild port-container port-subview)
    (.appendChild port-subview port-editor)
    (.appendChild container project-container)
    (.appendChild project-container project-label)
    (.appendChild project-container project-subview)
    (.appendChild project-subview project-select)
    {:container container
     :header header
     :host-editor host-editor
     :port-editor port-editor
     :project-select project-select}))

(defn ^:private update-project-select
  "Clears all of the children from the project-select dropdown and fills it with
  all of the currently open projects, selecting the project for the currently
  active editor."
  [project-select]
  (set! (.-innerHTML project-select) "") ; The most performant method for removing all children
  (let [active-project-name (common/get-active-project-name)]
    (doseq [project-name (common/get-all-project-names)]
      (let [option (doto (.createElement js/document "option")
                         (.setAttribute "value" project-name)
                         (set-text project-name))]
        (when (= project-name active-project-name)
          (.setAttribute option "selected" true))
        (.appendChild project-select option)))))

;; TODO: Check for duplicate project names and raise an error
(defn ^:private add-connection-panel-commands
  "Adds Atom commands to listen to the enter and escape keys.

  When enter is pressed, reads the host and port values and
  writes them to the async channel for the caller to get."
  [components]
  (let [{:keys [panel container host-editor port-editor project-select]} components
        confirm (fn [event]
                  (.hide panel)
                  (async/put! address-channel
                              {:host (get-text host-editor)
                               :port (int (get-text port-editor))
                               :project-name (get-option-value project-select)}))
        cancel (fn [event]
                 (.hide panel)
                 (async/put! address-channel false))]
    (-> (.-commands js/atom)
        (.add container "core:confirm" confirm))
    (-> (.-commands js/atom)
        (.add container "core:cancel" cancel))))

(defn ^:private add-connection-panel-tab-listeners
  "Adds a keydown listener to intercept Atom's default behavior
  and switch between the inputs. Since there are only two
  inputs, we don't need to worry about behavior for shift-tab
  since it's identical.

  An altenative way to implement this (how the find-and-replace package
  does it), would be to export new functions and create a keymap with a
  selector which specifically targets these inputs."
  [components]
  (let [{:keys [host-editor port-editor]} components
        keydown (fn [event]
                  (when (= (.-key event) "Tab")
                    (if (.hasFocus host-editor)
                      (.focus port-editor)
                      (.focus host-editor))
                    (.stopPropagation event)
                    (.preventDefault event)))]
    (.addEventListener host-editor "keydown" keydown)
    (.addEventListener port-editor "keydown" keydown)))

(defn create-connection-panel
  "Creates the connection panel and leaves it hidden until
  the user is prompted."
  []
  (let [{:keys [container] :as components} (create-connection-panel-dom)
        panel (-> (.-workspace js/atom)
                  (.addModalPanel (js-obj "item" container
                                          "visible" false)))
        components (assoc components :panel panel)]
    (add-connection-panel-commands components)
    (add-connection-panel-tab-listeners components)
    (reset! ui-components components)))

;; TODO: Read the port from a .nrepl-port file in the current project if it exists
(defn prompt-connection-panel
  "Interupts the user with a modal connection panel asking for
  a socket address to connect to, returning the result through
  an async chanel.

  Returns false if the user cancels the prompt."
  [message]
  (let [{:keys [panel header host-editor
                port-editor project-select]} @ui-components]
    (go
      (if-not (.-visible panel)
        (do
          (set-text header message)
          (set-text host-editor "")
          (set-text port-editor "")
          (update-project-select project-select)
          (.show panel)
          (.focus host-editor)
          (<! address-channel))
        (do
          (.focus host-editor)
          false)))))
