(ns andel.intervals-test
  (:require [clojure.test.check.generators :as g]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [clojure.test :refer :all])
  (:import [andel Intervals Intervals$Interval]))

(defn assert-ids-are-sane [intervals tree]
  (doseq [{:keys [id] :as expected} intervals]
    (let [i (Intervals/getById tree id)
          actual {:from (.-from i)
                  :to (.-to i)
                  :greedy-left? (.closedLeft i)
                  :greedy-right? (.closedRight i)
                  :id (.-id i)
                  :data (.-data i)}]
      (assert (= expected actual)
              {:actual   actual
               :expected expected}))))

(defn tree->intervals [tree]
  (let [it (Intervals/query tree 0 (/ Long/MAX_VALUE 4))]
    (loop [r []]
      (if (.next it)
        (recur (conj r {:id (.id it)
                        :from (.from it)
                        :greedy-left? (.closedLeft it)
                        :greedy-right? (.closedRight it)
                        :to (.to it)
                        :data (.data it)}))
        r))))

(defn intervals-bulk-gen
  ([] (intervals-bulk-gen 1))
  ([next-id]
   (g/bind
    (g/fmap (fn [size] (range next-id (+ next-id size))) g/nat)
    (fn [ids]
      (let [cnt (count ids)]
        (g/let [a (g/vector (g/large-integer* {:min 0 :max 10000}) cnt)
                b (g/vector (g/large-integer* {:min 0 :max 10000}) cnt)
                g-l? (g/vector g/boolean cnt)
                g-r? (g/vector g/boolean cnt)]
          (->> (mapv (fn [a b g-l? g-r? id]
                       {:id id
                        :from (min a b)
                        :to (max a b)
                        :greedy-left? (or (= a b) g-l?)
                        :greedy-right? (or (= a b) g-r?)
                        :data nil})
                     a b g-l? g-r? ids)
               (sort-by :from)
               (into []))))))))

(defn ->interval [{:keys [id from to data greedy-left? greedy-right?]}]
  (Intervals$Interval. id from to (boolean greedy-left?) (boolean greedy-right?) data))

(def empty-tree (Intervals. 32))

(defn naive-type-in [intervals [offset length]]
  (if (< 0 length)
    (into []
          (map (fn [{:keys [from to greedy-left? greedy-right?] :as interval}]
            (cond
              (and greedy-left?
                   (= offset from))
              (assoc interval :to (+ to length))

              (and greedy-right?
                   (= offset to))
              (assoc interval :to (+ to length))

              (and (< from offset)
                   (< offset to))
              (assoc interval :to (+ to length))

              (<= offset from)
              (assoc interval
                     :to (+ to length)
                     :from (+ from length))

              :else
              interval)))
          intervals)
    (let [length (- length)]
      (into []
            (comp (remove
                   (fn [{:keys [from to greedy-left? greedy-right?]}]
                     (and (< offset from) (< to (+ offset length)))
                     #_(or (and (< offset from) (< to (+ offset length)))
                         (and (not= from to)
                              (or (and greedy-left? (not greedy-right?) (< offset from) (<= to (+ offset length)))
                                  (and (not greedy-left?) greedy-right? (<= offset from) (< to (+ offset length))))))))
                  (map (fn [interval]
                         (let [update-point (fn [point] (if (< offset point)
                                                          (max offset (- point length))
                                                          point))]
                           (-> interval
                               (update :from update-point)
                               (update :to update-point)))))
                  (remove (fn [marker]
                            (and (= (:from marker) (:to marker))
                                 (not (and (:greedy-left? marker)
                                           (:greedy-right? marker)))))))
            intervals))))

(defn type-in [tree [offset size]]
  (if (< size 0)
    (Intervals/collapse tree offset (Math/abs size))
    (Intervals/expand tree offset size)))

(def type-in-prop
  (prop/for-all [[intervals typings] (g/bind (intervals-bulk-gen)
                                       (fn [bulk]
                                         (let [max-val (transduce (map :to) max 0 bulk)]
                                           (g/tuple (g/return bulk)
                                                    (g/vector (g/tuple (g/large-integer* {:min 0 :max max-val})
                                                                       g/int))))))]
    (let [tree (Intervals/insert empty-tree (into [] (map ->interval) intervals))]
      (= (reduce naive-type-in intervals typings)
         (tree->intervals (reduce type-in tree typings))))))

(defn intersects? [^long from1 ^long to1 ^long from2 ^long to2]
  (if (<= from1 from2)
    (< from2 to1)
    (< from1 to2)))

(defn play-query [model {:keys [from to]}]
  (vec (filter (fn [m] (intersects? (:from m) (:to m) from to)) model)))

(defn query-gen [max-val]
  (g/fmap (fn [[x y]]
            {:from (min x y)
             :to   (max x y)})
          (g/tuple (g/large-integer* {:min 0 :max max-val})
                   (g/large-integer* {:min 0 :max max-val}))))

(def bulk-and-queries-gen
  (g/bind (intervals-bulk-gen)
          (fn [bulk] (g/tuple (g/return bulk)
                              (g/vector (query-gen (->> bulk
                                                        (map :to)
                                                        (apply max 0))))))))

(defn insert-step-gen [{:keys [ids next-id] :as state}]
  (def state state)
  (g/fmap
   (fn [new-intervals]
     [(assoc state
                   :ids (clojure.set/union ids
                                           (into #{} (map :id) new-intervals))
                   :next-id (+ next-id (count new-intervals)))
      [:insert new-intervals]])
   (intervals-bulk-gen next-id)))

(defn delete-step-gen [{:keys [ids] :as state}]
  (g/fmap
   (fn [ids-to-delete]
     [(assoc state :ids (clojure.set/difference ids ids-to-delete))
      [:delete ids-to-delete]])
    (g/not-empty (g/set (g/elements ids)))))

(defn type-in-step-gen [state]
  (g/fmap
   (fn [typings] [state [:type-in typings]])
   (g/tuple (g/large-integer* {:min 0 :max 10000})
            (g/such-that (fn [i] (not= 0 i)) g/int))))

(defn recursive-intervals [s-gen]
  (g/bind s-gen
          (fn [{:keys [ids generated] :as state}]
            (g/sized
             (fn [size]
               (if (= 0 size)
                 (g/return state)
                 (let [next-gen (g/frequency (concat [[1 (insert-step-gen state)]]
                                                     (when (seq ids)
                                                       [[1 (delete-step-gen state)]
                                                        [3 (type-in-step-gen state)]])))]
                   (g/resize (dec size) (recursive-intervals next-gen)))))))))

 (def tree-actions-generator
    (let [op-gen (fn [state]
                   (g/frequency (concat [[1 (insert-step-gen state)]]
                                      (when (seq (:ids state))
                                        [[1 (delete-step-gen state)]
                                         [1 (type-in-step-gen state)]]))))]
    (clojure.test.check.generators.Generator.
     (fn [rnd size]
       (loop [state {:ids #{} :next-id 0}
              r []
              size size]
         (if (= 0 size)
           (rose/shrink (fn [& roses] (into [] (map second) roses)) r)
           (let [next-rose (g/call-gen (op-gen state) rnd size)
                 [state']  (rose/root next-rose)]
             (recur state' (conj r next-rose) (dec size)))))))))

(comment



  (def r (call-gen g 10))

  (.-children r)

  *e

  (def r(call-gen (g/gen-bind g/boolean (fn [rose] (g/gen-pure rose))) 100))

  (def r (call-gen g/boolean))

  (defn huj [r c]
    (let [op (str "huj " c)
          c (inc c)
          t (conj r op)]

      (lazy-seq
        (cons t (huj t c)))))

  (take 20 (huj [] 1))



  (map #(.-root %) (tree-seq #(seq (.-children %)) #(seq (.-children %)) r))

  (defn my-gen [g]
    (g/gen-bind g
            (fn [s]
              (g/sized
                (fn [size]
                  (if (= 0 size)
                    (g/return s)
                    (g/resize (dec size) (my-gen (g/return (conj s (rand-int 100)))))))))))

  (defn call-gen[gen size]
    (let [[created-seed rng] (make-rng 100)]
      (g/call-gen gen rng size)))

  (def r (call-gen (g/bind g/nat (fn [i] g/boolean))))

  (map #(.-children %)
   (.-children r))
state

  (def g (my-gen (g/return [])))

  (g/sample g)

  (import '[java.util Random])
  (require '[clojure.test.check.random :as random])
  (require ' [clojure.test.check.impl :refer [get-current-time-millis]])
  (require '[clojure.test.check.rose-tree :as rose])

  (defn- make-rng
    [seed]
    (if seed
      [seed (random/make-random seed)]
      (let [non-nil-seed (get-current-time-millis)]
        [non-nil-seed (random/make-random non-nil-seed)])))

  (def my-rose
    )

  (.-root my-rose)

  (.-children my-rose)


  (def his-rose
    (let [[created-seed rng] (make-rng 100)
          size 10]
      (g/call-gen (g/vector g/int) rng size)))


  (.-root his-rose)
  (.-root (first (.-children (.-root (nth (.-children his-rose) 4)))))

  (rose/root rose)





  )

#_(def tree-actions-generator
  (g/fmap
   (fn [{:keys [ids generated]}] generated)
   (g/sized
    (fn [size]
      (recursive-intervals
        (g/return {:ids #{}
                   :generated []}))))))

(defn naive-play-op [intervals-vec [op arg]]
  (case op
    :insert (sort-by :from (concat intervals-vec arg))
    :delete (into [] (remove (fn [i] (contains? arg (:id i)))) intervals-vec)
    :type-in (naive-type-in intervals-vec arg)))

(defn play-op [tree [op arg]]
  (case op
    :insert (Intervals/insert tree (into [] (map ->interval) arg))
    :delete (Intervals/remove tree arg)
    :type-in (let [[from len] arg]
               (if (< 0 len)
                 (Intervals/expand tree from len)
                 (Intervals/collapse tree from (- len))))))

(def intervals-prop
  (prop/for-all [ops tree-actions-generator]
                 (let [naive (reduce naive-play-op [] ops)
                       tree  (reduce play-op empty-tree ops)]
                   (assert-ids-are-sane naive tree)

                   ;;todo assert intervals are ordered
                   (= (set naive)
                      (set (tree->intervals tree))))))

(deftest intervals-test
  (is (:result (tc/quick-check 1000 intervals-prop :max-size 100))))

(comment

  (defn np [node]
    (if (instance? andel.Intervals$Node node)
      (let [^andel.Intervals$Node node node]
        {:starts (.-starts node)
         :ends (.-ends node)
         :ids (.-ids node)
         :children (mapv np (.-children node))})
      (str node)))

  (defn tp [^Intervals tree]
    {:open-root (np (.-openRoot tree))
     :closed-root (np (.-closedRoot tree))
     :mapping (.-parentsMap tree)})

  (def t (reduce play-op empty-tree (first fail)))

  (Intervals/insert t [(->interval {:id 24,
                          :from 0,
                          :to 1,
                          :greedy-left? true,
                          :greedy-right? false,
                          :data nil})
                       ])

  (let [[ops] fail
        naive (reduce naive-play-op [] ops)
        tree (reduce play-op empty-tree ops)
        actual (tree->intervals tree)]
    {:success? (= (set naive) (set actual))
     :naive naive
     :actual actual
     :diff-naive-vs-actual (clojure.data/diff (set naive) (set actual))
     :tree (tp tree)})

  *e

  )

