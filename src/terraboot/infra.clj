(ns terraboot.infra
    (:require [terraboot.core :refer :all]
            [terraboot.vpc :as vpc]
            [me.raynes.conch :refer [with-programs]]))

(def infra-path "infra/")

(defn generate-json
  []
  (to-file vpc/vpc-vpn-infra (str infra-path "vpc.tf")))

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
