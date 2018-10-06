(ns clojure-repl.local-repl-panel
  "Creates a model panel for prompting the user when starting a new local repl."
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [chan <!] :as async]
            [clojure.string :as string]
            [clojure-repl.strings :as strings]
            [clojure-repl.common :as common]))

;; TODO: Refactor and merge this with the connection-panel.

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

(defn ^:private create-local-repl-panel-dom
  "Builds the DOM for the modal panel and returns a map of UI components to be
  stored in the ui-components atom."
  []
  (let [default-host "localhost"
        default-port ""
        container (.createElement js/document "section")
        header (doto (.createElement js/document "h4")
                     (.setAttribute "class" "clojure-repl-h4"))
        project-container (doto (.createElement js/document "div")
                                (.setAttribute "class" "clojure-repl-container control-group"))
        project-label (doto (.createElement js/document "label")
                            (.setAttribute "class" "control-label"))
        project-title (doto (.createElement js/document "div")
                            (.setAttribute "class" "setting-title"))
        project-select (doto (.createElement js/document "select")
                             (.setAttribute "tabindex" -1)
                             (.setAttribute "class" "form-control")
                             (set-text-color "black"))
        repl-type-container (doto (.createElement js/document "div")
                                  (.setAttribute "class" "clojure-repl-container control-group"))
        repl-type-label (doto (.createElement js/document "label")
                              (.setAttribute "class" "control-label"))
        repl-type-title (doto (.createElement js/document "div")
                              (.setAttribute "class" "setting-title"))
        repl-type-select (doto (.createElement js/document "select")
                               (.setAttribute "tabindex" -1)
                               (.setAttribute "class" "form-control")
                               (set-text-color "black"))
        button-container (doto (.createElement js/document "div")
                               (.setAttribute "class" "clojure-repl-container clojure-repl-button-container"))
        cancel-button (doto (.createElement js/document "button")
                            (.setAttribute "class" "btn clojure-repl-cancel-button"))
        proceed-button (doto (.createElement js/document "button")
                            (.setAttribute "class" "btn btn-primary clojure-repl-proceed-button"))]
    (set-text project-title strings/local-repl-panel-project)
    (set-text repl-type-title strings/local-repl-type-label)
    (set-text cancel-button strings/cancel-button)
    (set-text proceed-button strings/create-local-repl-button)
    (.appendChild container header)
    (.appendChild container project-container)
    (.appendChild project-container project-label)
    (.appendChild project-label project-title)
    (.appendChild project-container project-select)
    (.appendChild container repl-type-container)
    (.appendChild repl-type-container repl-type-label)
    (.appendChild repl-type-label repl-type-title)
    (.appendChild repl-type-container repl-type-select)
    (.appendChild container button-container)
    (.appendChild button-container cancel-button)
    (.appendChild button-container proceed-button)
    {:container container
     :header header
     :repl-type-select repl-type-select
     :project-select project-select
     :cancel-button cancel-button
     :proceed-button proceed-button}))

(defn ^:private update-repl-type-select
  [repl-type-select]
  (set! (.-innerHTML repl-type-select) "") ; The most performant method for removing all children
  (.appendChild repl-type-select (doto (.createElement js/document "option")
                                       (.setAttribute "value" "lein")
                                       (set-text strings/leiningen-name))))

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
(defn ^:private add-local-repl-panel-commands
  "Adds Atom commands to listen to the enter and escape keys.

  When enter is pressed, reads the host and port values and
  writes them to the async channel for the caller to get."
  [components]
  (let [{:keys [panel container repl-type-select project-select]} components
        confirm (fn [event]
                  (.hide panel)
                  (async/put! address-channel
                              {:repl-type (get-option-value repl-type-select)
                               :project-name (get-option-value project-select)}))
        cancel (fn [event]
                 (.hide panel)
                 (async/put! address-channel false))]
    (-> (.-commands js/atom)
        (.add container "core:confirm" confirm))
    (-> (.-commands js/atom)
        (.add container "core:cancel" cancel))))

(defn ^:private add-local-repl-panel-listeners
  [components]
  (let [{:keys [cancel-button proceed-button]} components]
    (.addEventListener cancel-button "click" (fn [] (println "Hey")))
    (.addEventListener proceed-button "click" (fn [] (println "Ho")))))

(defn create-local-repl-panel
  "Creates the panel and leaves it hidden until the user is prompted."
  []
  (let [{:keys [container] :as components} (create-local-repl-panel-dom)
        panel (-> (.-workspace js/atom)
                  (.addModalPanel (js-obj "item" container
                                          "visible" false)))
        components (assoc components :panel panel)]
    (add-local-repl-panel-commands components)
    (add-local-repl-panel-listeners components)
    (reset! ui-components components)))

(defn prompt-local-repl-panel
  "Interupts the user with a modal panel, returning the result through an async
  channel.

  Returns false if the user cancels the prompt."
  [message]
  (let [{:keys [panel header repl-type-select project-select proceed-button]} @ui-components]
    (go
      (if-not (.-visible panel)
        (do
          (set-text header message)
          (update-repl-type-select repl-type-select)
          (update-project-select project-select)
          (.show panel)
          (.focus proceed-button)
          (<! address-channel))
        (do
          (.focus proceed-button)
          false)))))
