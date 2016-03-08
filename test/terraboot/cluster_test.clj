(ns terraboot.cluster-test
  (:require [terraboot.cluster :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [terraboot.vpc :as vpc]
            [expectations :refer :all]
            [clj-yaml.core :as yaml]))

(def master-from-cloud-config
  (yaml/parse-string
   (mesos-master-user-data)))

(def master-from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/master-config"))))

(expect (get-in master-from-cloud-config [:coreos :units])
        (get-in master-from-user-data [:coreos :units]))

(expect (:write_files master-from-cloud-config)
        (:write_files master-from-user-data))

(expect master-from-cloud-config
        master-from-user-data)

(def slave-from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/slave-config"))))

(def slave-from-cloud-config
  (yaml/parse-string
   (mesos-slave-user-data)))

(expect (get-in slave-from-cloud-config [:coreos :units])
        (get-in slave-from-user-data [:coreos :units]))

(expect slave-from-cloud-config
        slave-from-user-data)


(def public-slave-from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/public-slave-config"))))

(def public-slave-from-cloud-config
  (yaml/parse-string
   (mesos-public-slave-user-data)))

(expect public-slave-from-cloud-config
        public-slave-from-user-data)
