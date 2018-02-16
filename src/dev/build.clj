(ns build
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.umd :as umd]
            [shadow.devtools.server :as devtools]
            [clojure.java.io :as io]))

(defn- plugin-setup []
  (-> (cljs/init-state)
      (cljs/set-build-options
          {:node-global-prefix "global.clojure_repl"})
      (cljs/find-resources-in-classpath)
      (umd/create-module
        {:activate 'clojure-repl.core/activate
         :serialize 'clojure-repl.core/serialize
         :deactivate 'clojure-repl.core/deactivate
         :startRepl 'clojure-repl.core/start-repl
         :executeEnteredText 'clojure-repl.core/execute-entered-text}
        {:output-to "plugin/lib/clojure-repl.js"})))

(defn release []
  (-> (plugin-setup)
      (cljs/compile-modules)
      (cljs/closure-optimize :simple)
      (umd/flush-module))
  :done)

(defn dev []
  (-> (plugin-setup)
      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cljs/compile-modules)
              (umd/flush-unoptimized-module))))))

(defn dev-once []
  (-> (plugin-setup)
      (cljs/compile-modules)
      (umd/flush-unoptimized-module))
  :done)

(defn dev-repl []
  (-> (plugin-setup)
      (devtools/start-loop
        {:before-load 'clojure-repl.core/stop
         :after-load 'clojure-repl.core/start
         :reload-with-state true
         :console-support true
         :node-eval true}
        (fn [state modified]
          (-> state
              (cljs/compile-modules)
              (umd/flush-unoptimized-module)))))

  :done)
