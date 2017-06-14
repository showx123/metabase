(ns metabase.query-processor.middleware.binning
  (:require [clojure.walk :as walk]
            [metabase.util :as u])
  (:import [metabase.query_processor.interface BinnedField ComparisonFilter BetweenFilter]))

(defn- update! [^clojure.lang.ITransientAssociative coll k f]
  (assoc! coll k (f (get coll k))))

(defn- filter->field-map [mbql-filter]
  (let [acc (transient {})]
    (clojure.walk/prewalk
     (fn [x]
       (when (or
            (instance? BetweenFilter x)
            (and (instance? ComparisonFilter x)
                 (contains? #{:< :> :<= :>=} (:filter-type x))))
         (update! acc (get-in x [:field :field-id]) #(if (seq %)
                                                       (conj % x)
                                                       [x])))
       x)
     mbql-filter)
    (persistent! acc)))

(defn- calculate-bin-width [min-value max-value num-bins]
  (u/round-to-decimals 5 (/ (- max-value min-value)
                            num-bins)))

(defn extract-bounds [{field-id :field-id, global-min :min-value, global-max :max-value} field-filter-map]
  ;; Assuming only one for now
  (let [bound-1 (first (for [{:keys [value filter-type]} (get field-filter-map field-id)
                             :when (or (= :> filter-type)
                                       (= :>= filter-type))]
                         (:value value)                  ))
        bound-2 (first (for [{:keys [value filter-type]} (get field-filter-map field-id)
                             :when (or (= :< filter-type)
                                       (= :<= filter-type))]
                         (:value value)))
        comparison-bounds (when (and bound-1 bound-2)
                            (if (> bound-1 bound-2)
                              [bound-2 bound-1]
                              [bound-1 bound-2]))
        ;;Assuming either >/< or between
        between-bounds (first (for [{:keys [filter-type min-value max-value]} (get field-filter-map field-id)
                                    :when (= :between filter-type)]
                                [min-value max-value]))]
    (or (seq comparison-bounds)
        (seq between-bounds)
        [global-min global-max]))  )

(defn update-bin-width [breakouts filter-field-map]
  (mapv (fn [{:keys [field num-bins] :as breakout}]
          (if-let [[min-value max-value] (and (instance? BinnedField breakout)
                                              (extract-bounds field filter-field-map))]
            (assoc breakout
              :min-value min-value
              :max-value max-value
              :bin-width (calculate-bin-width min-value max-value num-bins))
            breakout))
        breakouts))

(defn update-binning-strategy
  [qp]
  (fn [query]
    (let [binned-breakouts (filter #(instance? BinnedField %) (get-in query [:query :breakout]))]
      (if (seq binned-breakouts)
        (qp (update-in query [:query :breakout] update-bin-width (filter->field-map (get-in query [:query :filter]))))
        (qp query)))))
