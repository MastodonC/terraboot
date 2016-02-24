(ns terraboot.cluster-test
  (:require [terraboot.cluster :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [expectations :refer :all]
            [clj-yaml.core :as yaml]))

(def master-from-cloud-config
  (yaml/parse-string
   (mesos-master-user-data {})))

(def master-from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/master-config"))))

(expect (get-in master-from-cloud-config [:coreos :units])
        (get-in master-from-user-data [:coreos :units]))

(expect (:write_files master-from-cloud-config)
        (:write_files master-from-user-data))

(expect master-from-cloud-config
        master-from-user-data)
