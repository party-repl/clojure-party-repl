(ns clojure-repl.bencode
  (:require [cljs.nodejs :as node]
            [oops.core :refer [oset!]]
            [clojure-repl.common :refer [console-log]]))

(def bencode (node/require "bencode"))

(defn reset-decode-data []
  (set! (.-data (.-decode bencode)) nil)
  (set! (.-encoding (.-decode bencode)) nil)
  (set! (.-position (.-decode bencode)) 0)
  (set! (.-bytes (.-decode bencode)) 0))

(defn ^:private decode-next
  "Returns a decoded data when it succeeds to decode. Returns nil when there's
  no more data to be decoded or when there's only partial data."
  []
  (try
    (.next (.-decode bencode))
    (catch js/Error e)))

(defn ^:private decode-all
  "Returns a vector of decoded data that was possible to decode as far as
  it could."
  [coll]
  (loop [all-data coll]
    (if-let [decoded-data (decode-next)]
      (recur (conj all-data decoded-data))
      all-data)))

(defn ^:private concat-data-and-decode
  "Returns a vector of decoded-data after concatinating the new data onto the
  previous data."
  [data]
  (console-log "Concat and decode...")
  (let [new-data (.concat js/Buffer (js/Array. (.-data (.-decode bencode)) (js/Buffer. data)))]
    (set! (.-data (.-decode bencode)) new-data)
    (decode-all [])))

(defn ^:private decoded-all? []
  (or (nil? (.-data (.-decode bencode)))
      (= (.-length (.-data (.-decode bencode)))
         (.-position (.-decode bencode)))))

(defn decode
  "Returns a vector of decoded data in case the encoded data includes multiple
  data chunks. It needs to be in a try-catch block because it can throw when
  the given data is empty, partial data, or invalid data.

  'decode' object holds two states: the data to be decoded and the position to
  start decoding next. The position gets updated when decode.next() succeeds.
  Because of these states, we need to handle two different cases:
    1. bencode.decode() is called the first time or when all the previous data
       has been decoded (meaning the position is at the last index), so we can
       override the previous data and just call bencode.decode() again.
    2. The previous data is only partially decoded, so the new data needs
       to be concatinated onto the previous data before decoding."
  [data]
  (if (decoded-all?)
    (try
      (console-log "Decoding...")
      (let [decoded-data (.decode bencode data "utf8")]
        (decode-all [decoded-data]))
      (catch js/Error e))
    (concat-data-and-decode data)))

(defn encode [data]
  (try
    (.encode bencode data "binary")
    (catch js/Error e)))
