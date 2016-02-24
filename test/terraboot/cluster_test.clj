(ns terraboot.cluster-test
  (:require [terraboot.cluster :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [expectations :refer :all]
            [clj-yaml.core :as yaml]))

(def from-cloud-config
  (yaml/parse-string
   (mesos-master-user-data {})))

(def from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/master-config"))))

(expect (get-in from-cloud-config [:coreos :units])
        (get-in from-user-data [:coreos :units]))

(expect (:write_files from-cloud-config)
        (:write_files from-user-data))
#_(expect from-cloud-config
        from-user-data)
