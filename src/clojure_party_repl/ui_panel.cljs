(ns clojure-party-repl.ui-panel ; TODO: Rename to repl-view
  "This is based on: https://github.com/atom/image-view/blob/master/lib/image-editor.js"
  (:require-macros [cljs.core :refer [exists?]])
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [clojure-party-repl.strings :as strings]))

(def node-atom (node/require "atom"))
(def Emitter (.-Emitter node-atom))
(def CompositeDisposable (.-CompositeDisposable node-atom))

; (defn create-dom []
;   (let [container (doto (.createElement js/document "div")
;                         (.setAttribute "class" "clojure-party-repl-view"))
;         output-editor (doto (.createElement js/document "atom-text-editor")
;                             (.setAttribute "class" "output-editor"))
;         output-editor-container (doto (.createElement js/document "div")
;                                       (.setAttribute "class" "output-editor-container"))
;         menu-container (doto (.createElement js/document "div")
;                              (.setAttribute "class" "menu-container"))
;         input-editor (doto (.createElement js/document "atom-text-editor")
;                            (.setAttribute "class" "input-editor"))
;         input-editor-container (doto (.createElement js/document "div")
;                                      (.setAttribute "class" "input-editor-container"))]
;     (.appendChild output-editor-container output-editor)
;     (.appendChild input-editor-container input-editor)
;     (doto container
;           (.appendChild output-editor-container)
;           (.appendChild menu-container)
;           (.appendChild input-editor-container))))

; TODO: Enable resizing using flex-grow like: PaneResizeHandleElement does

(defn create-dom []
  (let [output-editor-model (.buildTextEditor (.-workspace js/atom)
                                              (js-obj "autoHeight" false))
        input-editor-model (.buildTextEditor (.-workspace js/atom)
                                             (js-obj "autoHeight" false))
        output-editor (.-element output-editor-model)
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


  ; (let [container (doto (.createElement js/document "div")
  ;                       (.setAttribute "class" "clojure-party-repl-view"))]
  ;       ; output-editor (doto (.createElement js/document "atom-text-editor")
  ;       ;                     (.setAttribute "class" "output-editor"))]
  ;       ; menu-container (doto (.createElement js/document "div")
  ;                            ; (.setAttribute "class" "menu-container")))
  ;       ; input-editor (doto (.createElement js/document "atom-text-editor")
  ;       ;                    (.setAttribute "class" "input-editor"))]
  ;     output-editor))
    ; (doto container
    ;       (.appendChild output-editor))))
          ; (.appendChild menu-container)
          ; (.appendChild input-editor))))


; TODO: should we use an object here or will this state stored in a closure
; get garbage collected correctly?
(defn make-it-on [uri]
  (let [element (create-dom)
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
            (copy []
              (println "Copying me")
              (make-it-on uri))
            (destroy []
              (println "Disposing me!!")
              (.dispose subscriptions)
              (.removeChild js/document (.-parentNode element))
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
              "view" nil
              "emitter" emitter
              "getAllowedLocations" get-allowed-locations
              "getTitle" get-title
              "getPath" get-path
              "getURI" get-uri
              "getEncodedURI" get-encoded-uri
              "copy" copy
              "destroy" destroy
              "serialize" serialize
              "terminatePendingState" terminate-pending-state
              "isEqual" is-equal
              "onDidTerminatePendingState" on-did-terminate-pending-state
              "onDidChange" on-did-change
              "onDidChangeTitle" on-did-change-title
              "onDidDestroy" on-did-destroy))))
