(ns clojure-repl.nrepl
  (:require [cljs.nodejs :as node]
            [oops.core :refer [oget+ oset! oset!+ ocall]]
            [clojure-repl.common :refer [console-log]]
            [cljs.core.async :as async :refer [chan closed? <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def bencode (node/require "bencode"))

(defn reset-decode-data []
  (oset! (.-data (.-decode bencode)) nil)
  (oset! (.-encoding (.-decode bencode)) nil)
  (oset! (.-position (.-decode bencode)) 0))

(defn ^:private decode-next
  "Returns a decoded data when it succeeds to decode. Returns nil when there's
  no more data to be decoded or when there's only partial data."
  []
  (try
    (.next decode)
    (catch Error e)))

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
  (let [new-data (.concat Buffer (Array. (.-data (.-decode bencode)) (Buffer. data)))]
    (oset! (.-data (.-decode bencode)) new-data)
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
      (let [decoded-data (.decode bencode data "utf8")]
        (decode-all [decoded-data])))
      (catch Error e))
    (concat-data-and-decode data)))
