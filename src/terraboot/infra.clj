(ns terraboot.infra
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :refer [vpc-vpn-infra vpc-name]]
            [terraboot.cluster :refer [cluster-infra]]))

(def infra-path "infra/")

(defn generate-json [target]
  (let [account-number "165664414043"
        azs [:a]]
    (condp = target
      "vpc"     (to-file (vpc-vpn-infra {:vpc-name vpc-name
                                         :account-number account-number
                                         :azs azs
                                         :subnet-cidr-blocks {:a {:public "172.20.0.0/24"
                                                                  :private "172.20.8.0/24"}}}) "vpc/vpc.tf")
      "staging" (to-file (cluster-infra {:vpc-name vpc-name
                                         :cluster-name "staging"
                                         :min-number-of-masters 3
                                         :max-number-of-masters 3
                                         :master-disk-allocation 20
                                         :min-number-of-slaves 2
                                         :max-number-of-slaves 2
                                         :min-number-of-public-slaves 1
                                         :max-number-of-public-slaves 1
                                         :azs azs
                                         :subnet-cidr-blocks {:a {:public "172.20.1.0/24"
                                                                  :private "172.20.9.0/24"}}}) "staging/staging.tf"))))


(defn -main [target]
  (generate-json target))
