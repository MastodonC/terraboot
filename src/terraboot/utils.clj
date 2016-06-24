(ns terraboot.utils
  (require [clojure.set :as set]))

(letfn [(sensitive-merge-in*
          [mfns]
          (fn [a b]
            (if (map? a)
              (do (when-let [dups (seq (set/intersection (set (keys a)) (set (keys b))))]
                    (throw (Exception. (str "Duplicate keys: " dups))))
                  (merge-with ((first mfns) (rest mfns)) a b))
              b)))
        (merge-in*
          [mfns]
          (fn [a b]
            (if (map? a)
              (merge-with ((first mfns) (rest mfns)) a b)
              b)))
        (merge-with-fn-seq
          [fn-seq]
          (partial merge-with
                   ((first fn-seq) (rest fn-seq))))]
  ;; todo rediscover duplicates but not for the :output tree
  (def merge-in
    (merge-with-fn-seq (repeat merge-in*))))

;; this is copy pasta from http://dev.clojure.org/jira/browse/CLJ-1468 and is used in some utility libraries
(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn only when there's a non-map at a particular level."
  {:added "1.7"}
  [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (apply merge-with m maps)
       (apply f maps)))
   maps))
