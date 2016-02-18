(ns terraboot.cluster
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :as vpc]
            [me.raynes.conch :refer [with-programs]]))

(def cluster-infra
   )

(defn generate-json
  []
  (to-file vpc/vpc-vpn-infra "infra/vpc.tf"))

(defn -main [action]
  (with-programs [terraform]
    (condp = action
      "gen-json" (generate-json)
      "plan"     (do (generate-json)
                     (-> (terraform "plan" "infra/") println))
      "apply"    (do (generate-json)
                     (-> (terraform "apply" "infra/") println))
      :else (println "Please use one of the following options: gen-json, plan, apply"))))
