(ns terraboot.cluster-test
  (:require [terraboot.cluster :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [terraboot.vpc :as vpc]
            [expectations :refer :all]
            [clj-yaml.core :as yaml]))

(def master-from-cloud-config
  (yaml/parse-string
   (mesos-master-user-data {:aws-region "eu-central-1"
                            :cluster-name "witan-production"
                            :cluster-id "arn:aws:cloudformation:eu-central-1:165664414043:stack/witan-production/4faef210-d029-11e5-91a2-500c52a6cefe"
                            :server-group "MasterServerGroup"
                            :master-role "witan-production-MasterRole-BD3UQVJJBON"
                            :slave-role "witan-production-SlaveRole-173MJXXXLJXYY"
                            :aws-access-key "AKIAJT254WNI2YV7NBMA"
                            :aws-secret-access-key "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"
                            :exhibitor-s3-bucket "witan-production-exhibitors3bucket-ei8ratmz0ym7"
                            :internal-lb-dns "internal-witan-pro-Internal-KIJPTA3IMQ1N-1673275547.eu-central-1.elb.amazonaws.com"
                            :fallback-dns "10.64.0.2"})))

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
   (mesos-slave-user-data {:aws-region "eu-central-1"
                           :cluster-name "witan-production"
                           :cluster-id "arn:aws:cloudformation:eu-central-1:165664414043:stack/witan-production/4faef210-d029-11e5-91a2-500c52a6cefe"
                           :server-group "SlaveServerGroup"
                           :master-role "witan-production-MasterRole-BD3UQVJJBON"
                           :slave-role "witan-production-SlaveRole-173MJXXXLJXYY"
                           :aws-access-key "AKIAJT254WNI2YV7NBMA"
                           :aws-secret-access-key "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"
                           :exhibitor-s3-bucket "witan-production-exhibitors3bucket-ei8ratmz0ym7"
                           :internal-lb-dns "internal-witan-pro-Internal-KIJPTA3IMQ1N-1673275547.eu-central-1.elb.amazonaws.com"
                           :fallback-dns "10.64.0.2"})))

(expect (get-in slave-from-cloud-config [:coreos :units])
        (get-in slave-from-user-data [:coreos :units]))

(expect slave-from-cloud-config
        slave-from-user-data)


(def public-slave-from-user-data
  (yaml/parse-string
   (slurp (clojure.java.io/resource "user-data/public-slave-config"))))

(def public-slave-from-cloud-config
  (yaml/parse-string
   (mesos-public-slave-user-data {:aws-region "eu-central-1"
                                  :cluster-name "witan-production"
                                  :cluster-id "arn:aws:cloudformation:eu-central-1:165664414043:stack/witan-production/4faef210-d029-11e5-91a2-500c52a6cefe"
                                  :server-group "PublicSlaveServerGroup"
                                  :master-role "witan-production-MasterRole-BD3UQVJJBON"
                                  :slave-role "witan-production-SlaveRole-173MJXXXLJXYY"
                                  :aws-access-key "AKIAJT254WNI2YV7NBMA"
                                  :aws-secret-access-key "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"
                                  :exhibitor-s3-bucket "witan-production-exhibitors3bucket-ei8ratmz0ym7"
                                  :internal-lb-dns "internal-witan-pro-Internal-KIJPTA3IMQ1N-1673275547.eu-central-1.elb.amazonaws.com"
                                  :fallback-dns "10.64.0.2"})))

(expect public-slave-from-cloud-config
        public-slave-from-user-data)
