(ns clojure-repl.bencode
  (:require [cljs.nodejs :as node]
            [oops.core :refer [oget oset!]]
            [clojure-repl.common :refer [console-log]]))

(def bencode (node/require "bencode"))

(defn reset-decode-data []
  (oset! (.-decode bencode) "data" nil)
  (oset! (.-decode bencode) "encoding" nil)
  (oset! (.-decode bencode) "position" 0))

(defn ^:private decode-next
  "Returns a decoded data when it succeeds to decode. Returns nil when there's
  no more data to be decoded or when there's only partial data."
  []
  (let [previous-position (.-position (.-decode bencode))]
    (try
      (.next (.-decode bencode))
      (catch js/Error e
        (when (> (.-position (.-decode bencode))
                 (.-length (.-data (.-decode bencode))))
          (oset! (.-decode bencode) "position" previous-position))
        (console-log "Caught " e)))))

(defn ^:private decode-all
  "Returns a vector of decoded data that was possible to decode as far as
  it could."
  []
  (loop [all-data []]
    (if-let [decoded-data (decode-next)]
      (do
        (recur (conj all-data decoded-data)))
      all-data)))

(defn ^:private concat-data-and-decode
  "Returns a vector of decoded-data after concatinating the new data onto the
  previous data."
  [data]
  (console-log "Concat and decode...")
  (let [new-data (.concat js/Buffer (js/Array. (.-data (.-decode bencode)) (js/Buffer. data)))]
    (oset! (.-decode bencode) "data" new-data)
    (decode-all)))

(defn decoded-all? []
  (console-log "Decoded All?: " (when (.-data (.-decode bencode))
                                  (.-length (.-data (.-decode bencode))))
                                (when (.-data (.-decode bencode))
                                  (.-position (.-decode bencode))))
  (or (nil? (.-data (.-decode bencode)))
      (= (.-length (.-data (.-decode bencode)))
         (.-position (.-decode bencode)))))

(defn get-decode-data []
  {:data (oget (.-decode bencode) "data")
   :position (oget (.-decode bencode) "position")
   :encoding (oget (.-decode bencode) "encoding")})

(defn apply-decode-data
  "Sets the given decode data as the bencode state if the data has not been
  fully decoded."
  [{:keys [data position encoding]}]
  (console-log "Applying decode data...")
  (when (and data position (not= (.-length data) position))
    (oset! (.-decode bencode) "data" data)
    (oset! (.-decode bencode) "position" position)
    (oset! (.-decode bencode) "encoding" encoding)
    (console-log "Applied data: " (.-length (.-data (.-decode bencode))) (.-position (.-decode bencode)))))

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
      (do
        (apply-decode-data {:data data
                            :position 0
                            :encoding "utf8"})
        (decode-all))
      (catch js/Error e
        (console-log "Caught Error during decoding: " e)))
    (concat-data-and-decode data)))

(defn encode
  "Encodes the given data using bencode."
  [data]
  (try
    (.encode bencode data "binary")
    (catch js/Error e
      (console-log "Failed encoding because of " e))))
