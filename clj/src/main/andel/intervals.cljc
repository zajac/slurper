(ns ^:lean-ns andel.intervals
  (:refer-clojure :exclude [remove])
  (:require
    [andel.utils :refer [cond+]])
  (:import
    [java.util ArrayList PriorityQueue]
    [andel.intervals Intervals Interval IntervalsIterator]))

(def empty-tree (Intervals/empty))

(defn insert [^Intervals itree intervals]
  (.addIntervals itree intervals))

(defn remove [^Intervals itree marker-ids]
  (.removeByIds itree marker-ids))

(defn expand [^Intervals itree ^long offset ^long len]
  (.expand itree offset len))

(defn collapse [^Intervals itree ^long offset ^long len]
  (.collapse itree offset len))

(def empty-iterator (IntervalsIterator/fromList []))

(defn query
  (^IntervalsIterator [itree]
   (query itree 0 Long/MAX_VALUE))
  (^IntervalsIterator [itree ^long from]
   (query itree from Long/MAX_VALUE))
  (^IntervalsIterator [^Intervals itree ^long from ^long to]
   (if itree
     (.query itree from to)
     empty-iterator)))

(defn query-reverse
  (^IntervalsIterator [itree]
   (query-reverse itree 0 Long/MAX_VALUE))
  (^IntervalsIterator [itree ^long from]
   (query-reverse itree from Long/MAX_VALUE))
  (^IntervalsIterator [^Intervals itree ^long from ^long to]
   (if itree
     (.queryReverse itree from to)
     empty-iterator)))

(defn query-all [^Intervals itree ^long from ^long to]
  (.toList (query itree from to)))

(defn ii-first [^IntervalsIterator ii]
  (when (.next ii)
    ii))

(defn- something? [it]
  (and (some? it) (not (identical? it empty-iterator))))

(defn merge-iterators
  (^IntervalsIterator [it] it)
  (^IntervalsIterator [it1 it2]
   (cond
     (and (something? it1) (something? it2)) (IntervalsIterator/merge it1 it2 IntervalsIterator/FORWARD_COMPARATOR)
     (something? it1) it1
     (something? it2) it2
     :else nil))
  (^IntervalsIterator [it1 it2 & its]
   (reduce merge-iterators (merge-iterators it1 it2) its)))

(defmacro >Interval [& {:keys [id from to greedy-left? greedy-right? attrs]}]
  `(Interval. ~id ~from ~to ~greedy-left? ~greedy-right? ~attrs))

(defn find-marker-by-id ^Interval [^Intervals itree ^long id]
  (.findById itree id))

(defn trim [^Interval i ^long from ^long to]
  (cond
    (<= (.-to i) from) nil
    (>= (.-from i) to) nil
    (= (.-from i) (.-to i)) nil
    (and (<= from (.-from i)) (<= (.-to i) to)) i
    :else (Interval. (.-id i) (max from (.-from i)) (min to (.-to i)) (.-closedLeft i) (.-closedRight i) (.-data i)))) 

(defn map-shredded [start end intervals visit]
  (let [acc    (ArrayList.)
        yield  (fn [^long from ^long to active]
                 (let [from' (max from start)
                       to'   (min to end)]
                   (when (< from' to')
                     (.add acc (visit from' to' active)))))
        active (PriorityQueue. Interval/CMP_ENDS)]
    (loop [pos   start
           queue intervals]
      (cond+
        (and (empty? queue) (empty? active))
        (yield pos end active)
       
        (>= pos end)
        (yield pos end active)
       
        (empty? queue)
        (let [^Interval first-active (first active)]
          (yield pos (.-to first-active) active)
          (.poll active)
          (recur (.-to first-active) queue))
        
        :let [^Interval first-queue (first queue)]
       
        (empty? active)
        (do
          (yield pos (.-from first-queue) active)
          (.add active first-queue)
          (recur (.-from first-queue) (next queue)))
       
        :let [^Interval first-active (first active)]
        
        (< (.-from first-queue) (.-to first-active))
        (do
          (yield pos (.-from first-queue) active)
          (.add active first-queue)
          (recur (.-from first-queue) (next queue)))
       
        :else
        (do
          (yield pos (.-to first-active) active)
          (.poll active)
          (recur (.-to first-active) queue))))
    acc))
