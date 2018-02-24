(ns clojure-repl.core
  (:require [clojure.string :as string]
            [cljs.nodejs :as node]
            [clojure-repl.common :as common :refer [stdout state]]
            [clojure-repl.host :as host]
            [clojure-repl.guest :as guest]
            [clojure-repl.local-repl :as local-repl]))

;; TODO: Prevent Atom from auto-saving the two REPL tabs inside project directory

(def ashell (node/require "atom"))

(def commands (.-commands js/atom))

(def CompositeDisposable (.-CompositeDisposable ashell))

;; TODO: Merge with the common/state
(def disposables (atom []))

(def subscriptions (CompositeDisposable.))

(defn inside-string-or-comment? [editor position]
  (let [scopes (.-scopes (.scopeDescriptorForBufferPosition editor position))]
    (or (>= (.indexOf scopes "string.quoted.double.clojure") 0)
        (>= (.indexOf scopes "comment.line.semicolon.clojure") 0)
        (>= (.indexOf scopes "string.regexp.clojure") 0))))

(defn find-all-namespace-declarations [editor range]
  (let [ranges (atom [])
        regex (js/RegExp. "\\s*\\(\\s*ns\\s*([A-Za-z\\*\\+\\!\\-\\_\\'\\?]?[A-Za-z0-9\\.\\*\\+\\!\\-\\_\\'\\?\\:]*)" "gm")]
    (.backwardsScanInBufferRange editor
                                 regex
                                 range (fn [result]
                                         (when-not (inside-string-or-comment? editor (.-start (.-range result)))
                                           (let [match-string (str (second (.-match result)))]
                                             (swap! ranges conj [(.-start (.-range result)) match-string])))))
    (.log js/console (str "Namespaces " @ranges))
    @ranges))

;; TODO: Warn user if the namespace isn't declared in the repl. Currently,
;;       repl won't return any result when we send any code to repl with
;;       namespace, which doesn't exist in the repl, as options.
(defn find-namespace-for-range [editor range]
  (let [search-range ((.-Range ashell) 0 (.-start range))
        namespaces (find-all-namespace-declarations editor search-range)]
    (some (fn [[point namespace]]
            (.log js/console (str "Namespace " namespace " " (.isGreaterThan (.-start range) point)))
            (when (.isGreaterThan (.-start range) point)
              namespace))
          namespaces)))

(defn execute-selected-text [editor]
  (let [selected-range (.getSelectedBufferRange editor)
        namespace (find-namespace-for-range editor selected-range)]
    (host/execute (.getSelectedText editor) (when namespace {:ns namespace}))))

(defn find-range-with-cursor [ranges cursor]
  (some #(when (.containsPoint % cursor)
           %)
        ranges))

(def open-parans #{"(" "[" "{"})

(def close-parans #{")" "]" "}"})

(defn get-all-top-level-ranges [editor]
  (let [ranges (atom [])
        paran-count (atom 0)
        regex (js/RegExp. "[\\{\\}\\[\\]\\(\\)]" "gm")]
    (.scan editor regex (fn [result]
                          (when-not (inside-string-or-comment? editor (.-start (.-range result)))
                            (let [match-string (str (first (.-match result)))]
                              (if (contains? open-parans match-string)
                                (do
                                  (when (= @paran-count 0)
                                    (swap! ranges conj [(.-start (.-range result))]))
                                  (swap! paran-count inc))
                                (when (contains? close-parans match-string)
                                  (swap! paran-count dec)
                                  (when (= @paran-count 0)
                                    (swap! ranges update (dec (count @ranges)) #(conj % (.-end (.-range result)))))))))))

    (swap! ranges (partial filter #(= 2 (count %))))
    (swap! ranges (partial map #(apply (.-Range ashell) %)))
    @ranges))

(defn execute-top-level [editor]
  (let [ranges (get-all-top-level-ranges editor)
        cursor (.getCursorBufferPosition editor)]
    (when-let [range (find-range-with-cursor ranges cursor)]
      (let [namespace (find-namespace-for-range editor range)
            code (string/trim (.getTextInBufferRange editor range))]
        (host/execute code (when namespace {:ns namespace}))))))

(defn start-repl []
  (.log js/console "clojure-repl started!")
  (host/create-editors)
  (local-repl/start))

(defn send-to-repl []
  (let [editor (.getActiveTextEditor (.-workspace js/atom))]
    (cond
      (= editor (:guest-input-editor @state)) (common/prepare-to-execute)
      (= editor (:host-input-editor @state)) (host/execute-entered-text)
      (.isEmpty (.getLastSelection editor)) (execute-top-level editor)
      :else (execute-selected-text editor))))

(defn add-commands []
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:startRepl" start-repl))
  (swap! disposables conj (.add commands "atom-workspace" "clojure-repl:sendToRepl" send-to-repl)))

(defn activate []
  (.log js/console "Activating clojure-repl...")
  (add-commands)
  (guest/look-for-teletyped-repls))

(defn deactivate []
    (.log js/console "Deactivating clojure-repl...")
    (host/dispose)
    (guest/dispose)
    (doseq [disposable @disposables]
      (.dispose disposable)))

(defn start []
  (activate))

;; NOTE: Used to hot code reload. Calls stop before hotswapping code,
;;       then start after all code is loaded.
(defn stop []
  (deactivate))
