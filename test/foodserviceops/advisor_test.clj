(ns foodserviceops.advisor-test
  "Unit tests of `foodserviceops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [foodserviceops.advisor :as adv]
            [foodserviceops.store :as store]))

(def db (store/seed-db))

(deftest propose-service-record-shape
  (testing "service-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-service-record
                           :facility-id "facility-1"
                           :patch {:meal-count 200 :menu "chicken over rice"}})]
      (is (= :log-service-record (:op p)))
      (is (= "facility-1" (:facility-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :facility-id)))))

(deftest propose-service-operation-shape
  (testing "service-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-service-operation
                           :facility-id "facility-2"
                           :patch {:shift "lunch-prep" :date "2026-07-20"}})]
      (is (= :schedule-service-operation (:op p)))
      (is (= "facility-2" (:facility-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :facility-id "facility-1"
                           :patch {:item "disposable trays" :quantity 500 :estimated-cost 120.0}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-food-safety-concern-shape
  (testing "food-safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-food-safety-concern
                           :facility-id "facility-1"
                           :patch {:concern "allergen mismatch on tray 14"}})]
      (is (= :flag-food-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-service-record :schedule-service-operation :coordinate-supply-order
                :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :facility-id "facility-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-service-record :schedule-service-operation :coordinate-supply-order
                :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :facility-id "facility-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
