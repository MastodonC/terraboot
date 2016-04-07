(ns terraboot.infra
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :refer [vpc-vpn-infra vpc-name]]
            [terraboot.cluster :refer [cluster-infra]]))

(def infra-path "infra/")

(defn generate-json []
  (do
    (to-file (vpc-vpn-infra "sandpit") (str infra-path "vpc.tf"))
    (to-file (cluster-infra {:vpc-name vpc-name
                             :cluster-name "production"
                             :min-number-of-masters 3
                             :max-number-of-masters 3
                             :min-number-of-slaves 2
                             :max-number-of-slaves 2
                             :min-number-of-public-slaves 1
                             :max-number-of-public-slaves 2}) (str infra-path "cluster.tf"))
    (to-file (cluster-infra {:vpc-name vpc-name
                             :cluster-name "staging"
                             :min-number-of-masters 3
                             :max-number-of-masters 3
                             :min-number-of-slaves 2
                             :max-number-of-slaves 2
                             :min-number-of-public-slaves 1
                             :max-number-of-public-slaves 1}) (str infra-path "staging.tf"))))

;; Possible extra option: to make directory a parameter
(defn -main []
  (generate-json))
