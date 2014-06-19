(ns flambo.api
  (:refer-clojure :exclude [fn map reduce first count take distinct filter group-by])
  (:require [serializable.fn :as sfn]
            [clojure.tools.logging :as log]
            [flambo.function :refer [flat-map-function
                                     flat-map-function2
                                     function
                                     function2
                                     function3
                                     pair-function
                                     pair-flat-map-function
                                     void-function]]
            [flambo.conf :as conf]
            [flambo.utils :as u])
  (:import (scala Tuple2)
           (java.util Comparator)
           (org.apache.spark.api.java JavaSparkContext StorageLevels)
           (flambo.function Function Function2 Function3 VoidFunction FlatMapFunction
                            PairFunction PairFlatMapFunction)))

(System/setProperty "spark.serializer" "org.apache.spark.serializer.KryoSerializer")
(System/setProperty "spark.kryo.registrator" "flambo.kryo.BaseFlamboRegistrator")

(def STORAGE-LEVELS {:memory-only StorageLevels/MEMORY_ONLY
                     :memory-only-ser StorageLevels/MEMORY_ONLY_SER
                     :memory-and-disk StorageLevels/MEMORY_AND_DISK
                     :memory-and-disk-ser StorageLevels/MEMORY_AND_DISK_SER
                     :disk-only StorageLevels/DISK_ONLY
                     :memory-only-2 StorageLevels/MEMORY_ONLY_2
                     :memory-only-ser-2 StorageLevels/MEMORY_ONLY_SER_2
                     :memory-and-disk-2 StorageLevels/MEMORY_AND_DISK_2
                     :memory-and-disk-ser-2 StorageLevels/MEMORY_AND_DISK_SER_2
                     :disk-only-2 StorageLevels/DISK_ONLY_2})

(defmacro fn
  [& body]
  `(sfn/fn ~@body))

(defmacro defsparkfn
  [name & body]
  `(def ~name
     (fn ~@body)))

(defn spark-context
  ([conf]
     (log/debug "JavaSparkContext" (conf/to-string conf))
     (JavaSparkContext. conf))
  ([master app-name]
     (log/debug "JavaSparkContext" master app-name)
     (JavaSparkContext. master app-name)))

(defn local-spark-context
  [app-name]
  (let [conf (-> (conf/spark-conf)
                 (conf/master "local[*]")
                 (conf/app-name app-name))]
    (spark-context conf)))

(defmacro with-context
  [context-sym & body]
  `(let [~context-sym (f/spark-context "local[*]" "test")]
     (try
       ~@body
       (finally (.stop ~context-sym)))))

(defn jar-of-ns
  [ns]
  (let [clazz (Class/forName (clojure.string/replace (str ns) #"-" "_"))]
    (JavaSparkContext/jarOfClass clazz)))

(defsparkfn untuple [^Tuple2 t]
  (let [v (transient [])]
    (conj! v (._1 t))
    (conj! v (._2 t))
    (persistent! v)))

(defsparkfn double-untuple [t]
  (let [[x t2] (untuple t)
        v (transient [])]
    (conj! v x)
    (conj! v (untuple t2))
    (persistent! v)))

(defn ftruthy?
  [f]
  (fn [x] (u/truthy? (f x))))

;; ## RDD construction
;;
;; Function for constructing new RDDs
;;
(defn text-file [spark-context filename]
  (.textFile spark-context filename))

(defn parallelize
  ([spark-context lst] (.parallelize spark-context lst))
  ([spark-context lst num-slices] (.parallelize spark-context lst num-slices)))

;; ## Transformations
;;
;; Function for transforming RDDs
;;
(defn map
  [rdd f]
  (.map rdd (function f)))

(defn map-to-pair
  [rdd f]
  (.mapToPair rdd (pair-function f)))

(defn reduce
  [rdd f]
  (.reduce rdd (function2 f)))

(defn flat-map
  [rdd f]
  (.flatMap rdd (flat-map-function f)))

(defn flat-map-to-pair
  [rdd f]
  (.flatMapToPair rdd (pair-flat-map-function f)))

(defn filter
  [rdd f]
  (.filter rdd (function (ftruthy? f))))

(defn foreach
  [rdd f]
  (.foreach rdd (void-function f)))

(defn aggregate
  [rdd zero-value seq-op comb-op]
  (.aggregate rdd zero-value (function2 seq-op) (function2 comb-op)))

(defn fold
  [rdd zero-value f]
  (.fold rdd zero-value (function2 f)))

(defn reduce-by-key
  [rdd f]
  (-> rdd
      (map-to-pair identity)
      (.reduceByKey (function2 f))
      (.map (function untuple))))

(defn group-by
  [rdd f]
  (-> rdd
      (.groupBy (function f))
      (.map (function untuple))))

(defn group-by-key
  [rdd]
  (-> rdd
      (map-to-pair identity)
      .groupByKey
      (.map (function untuple))))

(defn combine-by-key
  [rdd create-combiner merge-value merge-combiners]
  (-> rdd
      (map-to-pair identity)
      (.combineByKey (function create-combiner)
                     (function2 merge-value)
                     (function2 merge-combiners))
      (.map (function untuple))))

(defn sort-by-key
  ([rdd]
     (sort-by-key rdd compare true))
  ([rdd x]
     ;; RDD has a .sortByKey signature with just a Boolean arg, but it doesn't
     ;; seem to work when I try it, bool is ignored.
     (if (instance? Boolean x)
       (sort-by-key rdd compare x)
       (sort-by-key rdd x true)))
  ([rdd compare-fn asc?]
     (-> rdd
         (map-to-pair identity)
         (.sortByKey
          (if (instance? Comparator compare-fn)
            compare-fn
            (comparator compare-fn))
          (u/truthy? asc?))
         (.map (function untuple)))))

(defn join
  [rdd other]
  (-> rdd
      (map-to-pair identity)
      (.join (map-to-pair other identity))
      (.map (function double-untuple))))

(defn left-outer-join
  [rdd other]
  (-> rdd
      (map-to-pair identity)
      (.leftOuterJoin (map-to-pair other identity))
      (.map (function
             (fn [t]
                      (let [[x t2] (untuple t)
                            [a b] (untuple t2)]
                        (vector x [a (.orNull b)])))))))

(defn sample
  [rdd with-replacement? fraction seed]
  (.sample rdd with-replacement? fraction seed))

;; ## Actions
;;
;; Action return their results to the driver process.
;;
(defn count-by-key
  "Only available on RDDs of type (K, V).
  Returns a map of (K, Int) pairs with the count of each key."
  [rdd]
  (into {}
        (-> rdd
            (map-to-pair identity)
            .countByKey)))

(defn save-as-text-file
  [rdd path]
  (.saveAsTextFile rdd path))

(defn save-as-sequence-file
  [rdd path]
  (.saveAsSequenceFile rdd path))

(defn persist
  [rdd storage-level]
  (.persist rdd storage-level))

(def first (memfn first))
(def count (memfn count))
(def glom (memfn glom))
(def cache (memfn cache))
(def collect (memfn collect))

;; take defined with memfn fails with an ArityException, so doing this instead:
(defn take
  [rdd cnt]
  (.take rdd cnt))

(def distinct (memfn distinct))
(def cache (memfn cache))
(def collect (memfn collect))
(def distinct (memfn distinct))
(def coalesce (memfn coalesce))
