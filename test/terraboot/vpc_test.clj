(ns terraboot.vpc-test
  (:require [terraboot.vpc :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [terraboot.core :refer :all]
            [clj-yaml.core :as yaml]
            [expectations :refer :all]))

(def from-cloud-config
  (yaml/parse-string
   (vpn-user-data {:range-start "10.64.0.0"
                   :fallback-dns "10.64.0.2"})))

(def from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/vpn-config"))))

(expect (:users from-cloud-config)
        (:users from-user-data))

(expect (:packages from-cloud-config)
        (:packages from-user-data))

(expect (get-in from-cloud-config [:write_files 0 :content])
        (get-in from-user-data [:write_files 0 :content]))

(expect (map :path (:write_files from-cloud-config))
        (map :path (:write_files from-user-data)))

(expect (:write_files from-cloud-config)
        (:write_files from-user-data))

(expect from-user-data from-cloud-config)
