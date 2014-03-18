(ns papaline.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan >!! <!! go timeout]]
            [papaline.core :refer :all]))

(deftest test-stage
  (let [f (fn [arg])]
    (is (= 2 (count (stage f))))
    (is (fn? (first (stage f))))))

(deftest test-pipeline
  (let [f (take 5 (repeat (fn [c] (swap! c inc))))
        stgs (map stage f)
        ppl (pipeline stgs)]
    (is (= 2 (count ppl)))
    (is (fn? (first ppl)))))

(deftest test-run-pipeline
  (let [c0 (atom 0)
        sync-chan (chan)
        num 5
        f (vec (take num (repeat (fn [c sync-chan] (swap! c inc) [c sync-chan]))))
        f (conj f (fn [c sync-chan] (>!! sync-chan 0)))
        stgs (map stage f)
        ppl (pipeline stgs)]
    (run-pipeline ppl c0 sync-chan)
    (<!! sync-chan)
    (is (= num @c0))))

(deftest test-copy-stage
  (let [c0 (atom 0)
        sync-chan (chan)
        num 5
        stgs (vec (map copy-stage (take num (repeat (fn [c sync-chan] (swap! c inc))))))
        stgs (conj stgs (stage (fn [c sync-chan]  (>!! sync-chan 0))))
        ppl (pipeline stgs)]
    (run-pipeline ppl c0 sync-chan)
    (<!! sync-chan)
    (is (= num @c0))))

(deftest test-pipeline-wait
  (let [c0 (atom 0)
        num 5
        stgs (vec (map copy-stage (take num (repeat (fn [c] (swap! c inc))))))
        ppl (pipeline stgs)]
    (run-pipeline-wait ppl c0)
    (is (= num @c0))))

(deftest test-pipeline-timeout
  (let [num 2
        stgs (vec (map copy-stage (take num (repeat (fn [] (<!! (timeout 2000)))))))
        ppl (pipeline stgs)]
    (is (= :timeout (run-pipeline-timeout ppl 1000 :timeout)))
    (cancel-pipeline ppl)))
