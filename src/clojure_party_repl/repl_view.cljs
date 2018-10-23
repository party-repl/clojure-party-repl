(ns clojure-party-repl.repl-view
  "This is based on: https://github.com/atom/image-view/blob/master/lib/image-editor.js"
  (:require-macros [cljs.core :refer [exists?]])
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oset!]]
            [clojure-party-repl.strings :as strings]))

(def node-atom (node/require "atom"))
(def Emitter (.-Emitter node-atom))
(def CompositeDisposable (.-CompositeDisposable node-atom))

(defn create-editors []
  (let [output-editor (.buildTextEditor (.-workspace js/atom)
                                        (js-obj "autoHeight" false
                                                "readOnly" true))
        input-editor (.buildTextEditor (.-workspace js/atom)
                                       (js-obj "autoHeight" false))]
    [output-editor input-editor]))

; TODO: Enable resizing using flex-grow like: PaneResizeHandleElement does
(defn create-dom [output-editor-model input-editor-model]
  (let [output-editor (.-element output-editor-model)
        input-editor (.-element input-editor-model)
        output-editor-container (doto (.createElement js/document "div")
                                      (.setAttribute "class" "output-editor-container")
                                      (.appendChild output-editor))
        input-editor-container (doto (.createElement js/document "div")
                                     (.setAttribute "class" "input-editor-container")
                                     (.appendChild input-editor))
        menu-container (doto (.createElement js/document "div")
                             (.setAttribute "class" "menu-container"))]
    (.setAttribute output-editor "class" "output-editor")
    (.setAttribute input-editor "class" "input-editor")
    (doto (.createElement js/document "div")
          (.setAttribute "class" "clojure-party-repl-view")
          (.appendChild output-editor-container)
          (.appendChild menu-container)
          (.appendChild input-editor-container))))

;; TODO: should we use an object here or will this state stored in a closure
;;       get garbage collected correctly?
;;          => We actually need to return a Model object that has been
;;             associated with a View object through ViewRegistery.
;;       Since Teletype works on TextEditor Model/View, we need to make sure
;;       when our unified model opens, it triggers callbacks for
;;       workspace.onDidAddTextEditor(). It also needs to handle all TextEditor
;;       functionalities been mapped down to our model.
(defn create-repl-view [uri]
  (let [[output-editor input-editor] (create-editors)
        element (create-dom output-editor input-editor)
        emitter (Emitter.)
        subscriptions (CompositeDisposable.)]
    (letfn [(get-allowed-locations []
              (clj->js ["center"]))
            (get-title []
              (println "Getting my title")
              "Party REPL")
            (get-path []
              (println "Getting my path")
              uri) ; TODO: This might be wrong!
            (get-uri []
              uri)
            (get-encoded-uri []
              uri) ; TODO: Will this ever contain anything that needs to be uri encoded?
            (is-equal [other]
              (println "Checking equality with" uri " and " other)
              (when (aget other "getURI")
                (= uri (.getURI other))))
            (get-buffer []
              (.getBuffer output-editor))
            (copy []
              (println "Copying me")
              (create-repl-view uri))
            (destroy [] ; TODO: Does this need to remove itself from the dom?
              (println "Disposing me!!")
              (.dispose subscriptions)
              (.emit emitter "did-destroy"))
            (on-did-destroy [callback]
              (println "Registering a destroy callback with me")
              (.on emitter "did-destroy" callback))
            (serialize []
              (println "Serializing me"))
            (deserialize []
              (println "Deserializing me"))
            (terminate-pending-state []
              (println "Terminate pending state just got called on me")
              (when (-> (.-workspace js/atom)
                        (.getCenter)
                        (.getActivePane)
                        (.getPendingItem))
                (.emit emitter "did-terminate-pending-state")))
            (on-did-terminate-pending-state [callback]
              (println "Registering on-did-terminate-pending-state on me")
              (.on emitter "did-terminate-pending-state" callback))
            (on-did-change [callback] ; These are called by Atom because it want's know know when this happens
              (println "Registering on-did-change on me"))
            (on-did-change-title [callback]
              (println "Registering on-did-change-title on me"))]
      (js-obj "element" element
              "view" element
              "outputEditor" output-editor
              "inputEditor" input-editor
              "emitter" emitter
              "getAllowedLocations" get-allowed-locations
              "getTitle" get-title
              "getPath" get-path
              "getURI" get-uri
              "getEncodedURI" get-encoded-uri
              "getBuffer" get-buffer
              "copy" copy
              "destroy" destroy
              "serialize" serialize
              "terminatePendingState" terminate-pending-state
              "isEqual" is-equal
              "onDidTerminatePendingState" on-did-terminate-pending-state
              "onDidChange" on-did-change
              "onDidChangeTitle" on-did-change-title
              "onDidDestroy" on-did-destroy))))

(def callbacks-on-did-add-text-editor (atom []))
(def callbacks-on-did-change-active-text-editor (atom []))

(defn trigger-on-did-add-text-editor [editor]
  (doseq [callback @callbacks-on-did-add-text-editor]
    (callback (js-obj "textEditor" editor
                      "pane" nil
                      "index" 0))))

(defn trigger-on-did-change-active-text-editor [editor]
  (doseq [callback @callbacks-on-did-change-active-text-editor]
    (callback (js-obj "textEditor" editor
                      "pane" nil
                      "index" 0))))

(defn collect-callbacks-on-did-add-text-editor [callback]
  (swap! callbacks-on-did-add-text-editor conj callback))

(defn replace-on-did-add-text-editor []
  (let [original-fn (.-onDidAddTextEditor (.-workspace js/atom))]
    (oset! js/atom ["workspace" "onDidAddTextEditor"]
                   (fn [callback]
                     (collect-callbacks-on-did-add-text-editor callback)
                     (.call original-fn (.-workspace js/atom) callback)))))
