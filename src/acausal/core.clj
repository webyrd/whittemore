(ns acausal.core
  (:refer-clojure :exclude [ancestors parents])
  (:require [rhizome.viz]
            [clojure.set :refer [union subset? intersection difference]]
            [clojure.string :as string]
            [clojupyter.protocol.mime-convertible :as mc]
            [clojure.pprint :as p]))


;; TODO: test more thoroughly
(defn transpose
  "Returns the transpose of directed graph g (adjacency list representation)
  Returns map of nodes -> set of nodes"
  [g]
  (apply merge-with
         into

         ; key -> #{}
         (into {}
               (for [k (keys g)]
                 {k #{}}))

         ; val -> #{key}
         (for [k (keys g)
               v (get g k)]
           {v #{k}})))


;; TODO: make more efficient
(defn pairs-of
  "Returns all pairs of items in coll as set of sets."
  [coll]
  (set
    (for [i coll
          j coll
          :when (not= i j)]
      #{i j})))


;; Models are are stored as map of vars to set of parents, `pa`
;; and set of pairs (sets), `bi`
(defrecord Model [pa bi])


;; TODO: rename args?
;; TODO: validate no cycles; confounded are sets
(defn model
  "Construct a model"
  [dag & confounded]
  (let [bi (apply union (map pairs-of confounded))
        pa (into {} (for [[k v] dag] [k (set v)]))]
    (Model. pa bi)))


;; TODO: refactor
;; Currently, a hack for the rhizome visualization
(defrecord Latent [ch])

(defn latent?
  "True iff node is a Latent node"
  [node]
  (instance? acausal.core.Latent node))


;; TODO: refactor (see view-model)
;; Consider adding support for subscripts
(defn node->descriptor
  "Graphviz options for node"
  [n]
  (if (latent? n)
    {:label "", :shape "none", :width 0, :height 0}
    {:label (if (keyword? n) (name n) (str n))}))
   ; :shape "circle"


;; TODO: refactor (see view-model)
(defn edge->descriptor
  "Graphviz options for edge"
  [i j]
  (if (latent? i)
    {:style "dotted" :arrowhead "empty"} ; :constraint "false"
    {}))


;; TODO: refactor
(defn rhizome-graph
  "Returns a 'rhizome graph', given model m"
  [m]
  (into (transpose (:pa m))
        (for [multiedge (:bi m)]
          {(Latent. multiedge) multiedge})))

(def rhizome-options
  [:vertical? true
   :node->descriptor node->descriptor
   :edge->descriptor edge->descriptor])


;; TODO: refactor
;; currently a hack, since rhizome doesn't support a clean way to draw
;; multiedges; may switch to Dorothy or Tangle
;; Consider using dotviz, multiedges, with constraint=false for placement
(defn view-model
  "View model m"
  [m]
  (let [children (rhizome-graph m)]
    (apply rhizome.viz/view-graph
           (keys children)
           children
           rhizome-options)))


(defn model->svg
  "Render model m as svg graphic"
  [m]
  (let [children (rhizome-graph m)]
    (apply rhizome.viz/graph->svg
           (keys children)
           children
           rhizome-options)))


;; TODO: Needs :imap argument
;; probably needs a way to define support of random variables
(defrecord Data [vars surrogate])


;; TODO: validate
(defn data
  "Construct a data object"
  [v & {:keys [do*] :or {do* []}}]
  (Data. (set v) (set do*)))


;; TODO: consider different field names
;; TODO: consider supporting conditional queries, :given
;; alias (q ..) to query?
;; Note that by convention: y is effect, x is do(), w is given
(defrecord Query [effect do])

(defn query
  "Construct a query"
  [effect & {:keys [do] :or {do []}}]
  (Query. (set effect) (set do)))




(defn parents
  "Pa(x)_m
  Returns the parents of the (collection of) nodes x in model m."
  [m x]
  (apply union
         (for [node x]
           (get (:pa m) node))))


(defn ancestors
  "An(x)_m
  Returns the ancestors of the (collection of) nodes x in model m, inclusive."
  [m x]
  (loop [frontier (set x)
         visited #{}]
  (if (empty? frontier)
    visited
    (recur (parents m frontier)
           (union visited frontier)))))


(defn verticies
  "Returns the verticies of model m as a set."
  [m]
  (set (keys (:pa m))))


;; Efficient?
(defn graph-cut
  "Given a graph (adjacency list) g and set x, return g with all keys in x
  mapping to #{}."
  [g x]
  (let [new-kv (for [k x] [k #{}])]
    (into g new-kv)))


;; hack?
;; TODO: can probably improve with transients
;; see: https://clojuredocs.org/clojure.core/disj%21
(defn pair-cut
  "Given a set of pairs (sets), return a set of pairs where all pairs that
  contain an item in x have been removed."
  [pairs x]
  (let [to-remove (filter #(or (contains? x (first %))
                               (contains? x (second %)))
                          pairs)]
    (apply disj pairs to-remove)))


;; TODO: test more thoroughly
(defn cut-incoming
  "G_{\\overline{x}}
  Returns a model where all incoming edges to nodes in x have been severed in g"
  [m x]
  (let [pa (graph-cut (:pa m) x)
        bi (pair-cut (:bi m) x)]
    (Model. pa bi)))


;; may be inefficient
(defn subgraph
  "G_{X}
  Returns a model containing all verticies in (set) x and edges between those
  verticies, including the bidirected edges"
  [m x]
  (let [to-remove (difference (verticies m) x)
        bi (pair-cut (:bi m) to-remove)
        pa (into {}
                 (for [[k v] (:pa m)
                       :when (contains? x k)]
                   [k (intersection v x)]))]
    (Model. pa bi)))


;; TODO: remove?
(defn latents
  "Helper function, return set of latents of m"
  [m]
  (into #{}
        (for [[k v] (:pa m)
              :when (latent? k)]
          (:ch k))))


;; efficient?
(defn adjacent
  "Helper function..."
  [pairs node]
  (disj (apply union
               (filter #(contains? % node) pairs))
        node))


;; efficient? rename?
(defn connected-component
  "Helper function... Assumes edges are set of set of multiedges, n is node"
  [pairs node]
  (loop [frontier (list node)
         visited #{}]
    (if (empty? frontier)
      visited
      (let [current (peek frontier)]
        (if (contains? visited current)
          (recur (pop frontier)
                 visited)
        ;else    
          (recur (into (pop frontier) (adjacent pairs current))
                 (conj visited current)))))))


;; TODO: test more thoroughly, cleanup
(defn c-components
  "Returns the confounded components of m as set of sets of verticies."
  [m]
  (loop [nodes (verticies m)
         components #{}]
    (if (empty? nodes)
      components
      (let [current-node (first nodes)
            current-component (connected-component (:bi m) current-node)]
        (recur (difference nodes current-component)
               (conj components current-component))))))


;; helper function
(defn find-superset
  "Given a collection of sets, return a set that is a superset of s,
  or nil if no such superset exists"
  [coll s]
  (first (filter #(subset? s %) coll)))


(defn sources
  "Return all nodes from dag g which have zero in-degree"
  [g]
  (set
    (filter #(empty? (get g %))
            (keys g))))


;; rename/restructure?
;; probably inefficient
(defn kahn-cut
  [g x]
  (into {}
        (for [[k v] g
              :when (not (contains? x k))]
          [k (difference v x)])))


;; TODO: test more thoroughly; restructure?
;; Should be able to take advantage of d-seperation structure (Tikka paper),
;; but can save that problem for later
;; ?? is a vector the right type for result?
(defn topological-sort
  "Given a model m, return a topological sort"
  [m]
  (loop [remaining (:pa m)
         result (empty [])]
    (if (empty? remaining)
      result
      (let [frontier (sources remaining)]
        (if (empty? frontier)
          (throw (Error. "Not a DAG"))
          (recur (kahn-cut remaining frontier)
                 (into result frontier)))))))


;; WARN: will return entire ordering if v not in ordering
(defn predecessors
  "Set of nodes preceding v in topological ordering"
  [ordering v]
  (set (take-while #(not= % v) ordering)))


;; TODO: determine structure of Formula
;; name field(s)?
(defrecord Formula [f])


;;; TODO: cleanup/restructure
(defn node->latex
  [n]
  (if (keyword? n)
    (name n)
    (str n)))


;; TODO: cleanup/restucture
(defn marginalize
  "\\sum_{sub} p
   p is the current distribution; sub is a set of vars"
  [p sub]
  (if (empty? sub)
    p
    {:sub sub :sum p}))


;; TODO: cleanup/restucture
(defn product
  "Temporary representation of \\sum_{sub} p
  coll - collection of probability expressions"
  [coll]
  (let [exprs (set coll)]
    (if (= (count exprs) 1)
      (first exprs)
      {:prod exprs})))


;; TODO: zID;
;; TODO: more testing, restructure?
(defn id
  "y set
   x set
   p formula type; for now, initially call with {:p #{vars}}
   g model"
  [y x p g]
  (let [v (verticies g)]
    (cond
      ;; line 1
      (empty? x)
      (marginalize p (difference v y))

      ;; line 2, refactor? (don't need the recur?)
      (not (empty? (difference v (ancestors g y))))
      (id y
          (intersection x (ancestors g y))
          {:p (ancestors g y)
           :where (marginalize p (difference v (ancestors g y)))}
          (subgraph g (ancestors g y)))

      ;; line 3
      :else
      (let [w (difference (difference v x) (ancestors (cut-incoming g x) y))]
        (if (not (empty? w))
          (id y (union x w) p g)
          
          ;; line 4
          (let [cg-x (c-components (subgraph g (difference v x)))]
            (if (> (count cg-x) 1)
              (marginalize
                (product
                  (for [si cg-x]
                    (id si
                        (difference v si)
                        p
                        g)))
                (difference v (union y x)))

              ;; line 5 (cgx should be singleton)
              (let [s (first cg-x)
                    cg (c-components g)]
                (if (= (first cg) (verticies g))
                  {:fail g :hedge s} ;; restructure!

                  ;; line 6
                  (if (contains? cg s)
                    (let [pi (topological-sort g)]
                      (marginalize
                        (product
                          (for [vi s]
                            (if (:where p)
                              {:where (:where p)
                               :p #{vi} :given (predecessors pi vi)}
                              {:p #{vi} :given (predecessors pi vi)})))
                        (difference s y)))

                    ;; line 7;
                    (if-let [s-prime (find-superset cg s)]
                      (let [pi (topological-sort g)
                            p-prime (product 
                                      (for [vi s]
                                        (if (:where p)
                                          {:where (:where p)
                                           :p #{vi}
                                           :given (predecessors pi vi)}
                                          {:p #{vi}
                                           :given (predecessors pi vi)})))]
                        (id y
                            (intersection x s-prime)
                            {:p s-prime :where p-prime}
                            (subgraph g s-prime)))

                      ;; fall-through
                      (throw (Error. "Should be unreachable")))))))))))))
                      

  
(defn identify
  "zID(C?) algorithm
   By default, assume P(v) as data [m q d]; [m q]"
  [y x m]
  (let [p {:p (verticies m)}]
    (id y x p m)))


(def hedge-less
  (model
    {:w_1 []
     :x [:w_1]
     :y_1 [:x]
     :w_2 []
     :y_2 [:w_2]}
    #{:w_1 :y_1}
    #{:w_1 :w_2}
    #{:w_2 :x}
    #{:w_1 :y_2}))


(def kidney
  (model 
    {:recovery [:treatment :size]
     :size []
     :treatment [:size]}))


(def identifiable-a
  (model
    {:y [:x]
     :x []}))


(def identifiable-b
  (model
    {:x []
     :y [:x :z]
     :z [:x]}
    #{:z :y}))
     

(def identifiable-c
  (model
    {:x [:z]
     :y [:x :z]
     :z []}
    #{:z :y}))
     

(def identifiable-d
  (model
    {:x [:z]
     :y [:x :z]
     :z []}
    #{:x :z}))


(def identifiable-e
  (model
    {:x []
     :y [:z]
     :z [:x]}
    #{:x :y}))

(def identifiable-f
  (model
    {:x []
     :z1 [:x]
     :z2 [:z1]
     :y [:x :z1 :z2]}
    #{:x :z2}
    #{:z1 :y}))

(def identifiable-g
  (model
    {:x [:z2]
     :z1 [:x :z2]
     :z2 []
     :z3 [:z2]
     :y [:z1 :z3]}
    #{:x :z2}
    #{:x :z3}
    #{:x :y}
    #{:y :z2}))


(def blood-pressure
  (model 
    {:recovery [:blood-pressure :treatment]
     :blood-pressure [:treatment]
     :treatment []}))


(comment 

(identify #{:y} #{:x} identifiable-a)
(identify #{:y} #{:x} identifiable-b)
(identify #{:y} #{:x} identifiable-c)
(identify #{:y} #{:x} identifiable-d)
(identify #{:y} #{:x} identifiable-e)
(identify #{:y} #{:x} identifiable-f)
(identify #{:y} #{:x} identifiable-g)

(p/pprint (identify #{:y_1 :y_2} #{:x} hedge-less))

(p/pprint (identify #{:recovery} #{:treatment} kidney))

(p/pprint (identify #{:recovery} #{:treatment} blood-pressure))

)


(def non-a
  (model
    {:x []
     :y [:x]}
    #{:x :y}))

(def non-b
  (model
    {:x []
     :z [:x]
     :y [:z]}
    #{:x :z}))

(def non-c
  (model
    {:x []
     :z [:x]
     :y [:z]}
    #{:x :z}))

(def non-d
  (model
    {:x []
     :y [:x :z]
     :z []}
    #{:x :z}
    #{:z :y}))

(def non-e
  (model
    {:x [:z]
     :y [:x]
     :z []}
    #{:x :z}
    #{:z :y}))

(def non-f
  (model
    {:x []
     :z [:x]
     :y [:z]}
    #{:x :y}
    #{:z :y}))

(def non-g
  (model
    {:x []
     :z1 [:x]
     :z2 []
     :y [:z1 :z2]}
    #{:x :z2}
    #{:z1 :z2}))

(def non-h
  (model
    {:x [:z]
     :z []
     :w [:x]
     :y [:w]}
    #{:x :z}
    #{:x :y}
    #{:z :y}
    #{:z :w}))

(comment

(identify #{:y} #{:x} non-a)
(identify #{:y} #{:x} non-b)
(identify #{:y} #{:x} non-c)
(identify #{:y} #{:x} non-d)
(identify #{:y} #{:x} non-e)
(identify #{:y} #{:x} non-f)
(identify #{:y} #{:x} non-g)
(identify #{:y} #{:x} non-h)

)





;;; Jupyter integration (TODO: seperate this out later?)

(extend-protocol mc/PMimeConvertible
  Model
  (to-mime [this]
    (mc/stream-to-string
      {:image/svg+xml (model->svg this)})))

(comment

(extend-protocol mc/PMimeConvertible
  Formula
  (to-mime [this]
    (mc/stream-to-string
      {:text/latex (str "$" (:s this) "$")})))

)

;;; example models


(def m1
  (model
    {:w []
     :z [:w :x]
     :y [:z]
     :x []}
    [:x :y]
    [:w :z]))


(def m2
  (model
    {:w []
     :z [:w :x]
     :y [:z]
     :x []}
    [:w :y :z]))


(def m3
  (model
         {:x []
          :z [:x]
          :y [:z]}
         #{:x :z :y}))

(def m4
  (model
         {:v []
          :w [:v]
          :z [:w :x]
          :y [:z]
          :x []}
         #{:w :z}
         #{:z :y :x}))


(comment

(def example #{#{:a :b :c} #{:c :d} #{:g :h} #{:d :f} #{1 2} #{3 4} #{2 5} #{2 9} #{9 10 11}})

(disj (apply union (filter #(contains? % 2) example)) 2)

(connected-component example 1)

(c-components m1)

)


(comment

(view-model m2)

(difference #{:recovery :size :treatment} #{:treatment})

(cut-incoming kidney #{:treatment})

(ancestors (cut-incoming kidney #{:treatment}) #{:recovery})

(id #{:recovery} #{:treatment} ["P(v)"] kidney)

(id #{:recovery} #{} [] kidney)

)
