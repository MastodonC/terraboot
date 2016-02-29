(ns terraboot.infra
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :refer [vpc-vpn-infra vpc-name]]
            [terraboot.cluster :refer [cluster-infra]]
            [me.raynes.conch :refer [with-programs]]))

(def infra-path "infra/")

(defn generate-json []
  (do
    (to-file vpc-vpn-infra (str infra-path "vpc.tf"))
    (to-file (cluster-infra vpc-name "production") (str infra-path "cluster.tf"))))

;; Possible extra option: to make directory a parameter
(defn -main [action]
  (with-programs [terraform]
    (condp = action
      "gen-json" (generate-json)
      "plan"     (do (generate-json)
                     (-> (terraform "plan" infra-path) println))
      "apply"    (do (generate-json)
                     (-> (terraform "apply" infra-path) println))
      :else (generate-json))))
