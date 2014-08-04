(ns papaline.core
  (:require [clojure.core.async :as a
             :refer :all
             :exclude [partition-by map into reduce partition take merge]]))

(defn stage [stage-fn & {:keys [in-chan
                                buffer-size
                                buffer-type]
                         :or {buffer-size 100}}]
  (let [buffer-fn (case buffer-type
                    :sliding sliding-buffer
                    :dropping dropping-buffer
                    buffer)
        buffer-factory #(chan (buffer-fn buffer-size))]
    [buffer-factory stage-fn]))

(defn copy-stage [stage-fn & options]
  (let [sfn (fn [& args]
              (apply stage-fn args)
              args)]
    (apply stage sfn options)))

(defn pipeline [stages]
  (let [stages (mapv #(if (fn? %) (stage %) %) stages)
        realized-stages (mapv #(vector ((first %)) (second %)) stages)
        entry (-> realized-stages first first)
        done-chan (chan)]
    (loop [stages* realized-stages]
      (when (first stages*)
        (let [[in-chan task] (first stages*)
              [out-chan _] (second stages*)]
          (go-loop []
                   (let [[ctx port] (alts! [done-chan in-chan] :priority true)]
                     (if (not= port done-chan)
                       (do
                         (go
                           (let [ctx (try
                                       (let [args (:args ctx)
                                             args (if (or (nil? args) ;; empty arguments
                                                          (sequential? args))
                                                    args [args])]
                                         (assoc ctx :args (apply task args)))
                                       (catch Exception e
                                         (if (and (instance? clojure.lang.ExceptionInfo e)
                                                  (:abort (ex-data e)))
                                           (merge ctx (ex-data e))
                                           (do
                                             (.printStackTrace e)
                                             (throw e)))))
                                 out-chan (or out-chan (:wait ctx))]
                             (when out-chan
                               (cond
                                (:abort ctx)
                                (when (:wait ctx) (>! (:wait ctx) ctx))

                                ;; the results are forked
                                (:fork (meta (:args ctx)))
                                (let [ctx (-> ctx
                                              (update-in [:forks]
                                                         #(conj (or % []) (count @(:args ctx))))
                                              (update-in  [:fork-rets]
                                                          #(conj (or % []) (atom []))))]
                                  (doseq [forked-args @(:args ctx)]
                                    (>! out-chan (assoc ctx :args forked-args))))

                                ;; this tasks requires join
                                (:join (meta (:args ctx)))
                                (let [fork-rets (swap! (last (:fork-rets ctx)) conj @(:args ctx))]
                                  (when (= (last (:forks ctx)) (count fork-rets))
                                    (>! out-chan (assoc ctx
                                                   :args fork-rets
                                                   :forks (vec (drop-last (:forks ctx)))
                                                   :fork-rets (vec (drop-last (:fork-rets ctx)))))))

                                ;; normal linear
                                :else (>! out-chan ctx)))))
                         (recur))
                       (close! in-chan))))
          (recur (rest stages*)))))
    [(fn [call-info]
       (go
        (>! entry call-info)))
     done-chan
     realized-stages]))

(defn run-pipeline [pipeline & args]
  ((first pipeline) {:args args}))

(defn run-pipeline-wait [pipeline & args]
  (let [sync-chan (chan)]
    ((first pipeline) {:args args
                       :wait sync-chan})
    (:args (first (alts!! [(second pipeline) sync-chan])))))

(defn run-pipeline-timeout [pipeline timeout-interval timeout-val & args]
  (let [sync-chan (chan)
        timeout-chan (timeout timeout-interval)]
    ((first pipeline) {:args args
                       :wait sync-chan})
    (let [done-chan (second pipeline)
          [v port] (alts!! [done-chan timeout-chan sync-chan] :priority true)]
      (if (= port timeout-chan)
        timeout-val
        (:args v)))))

(defn pipeline-stage
  "pipeline as a stage"
  [pipeline]
  (let [stages (nth pipeline 2)
        in-chan (second (first stages))]
    (stage (fn [& args]
             (apply run-pipeline-wait pipeline args))
           :in-chan in-chan)))

(defn cancel-pipeline [pipeline]
  (>!! (second pipeline) 0))

(deftype MetadataObj [val meta-map-wrapper]
  clojure.lang.IDeref
  (deref [this]
    val)

  clojure.lang.IObj
  (withMeta [this m]
    (swap! meta-map-wrapper merge m)
    this)

  clojure.lang.IMeta
  (meta [this]
    @meta-map-wrapper))

(defn- meta-obj [v]
  (MetadataObj. v (atom {})))

(defn- assoc-meta [v & args]
  (with-meta v (apply assoc (or (meta v) {}) args)))

(defn abort
  ([] (throw (ex-info "Aborted" {:abort true})))
  ([ret] (throw (ex-info "Aborted" {:abort true :ret ret}))))

(defn fork [ret]
  (when-not (sequential? ret)
    (throw (IllegalArgumentException. "Only sequential value is forkable.")))
  (assoc-meta (meta-obj ret) :fork true))

(defn join [ret]
  (assoc-meta (meta-obj ret) :join true))
