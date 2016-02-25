(ns terraboot.cluster
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :as vpc]
            [terraboot.cloud-config :refer [cloud-config]]))

(defn mesos-instance-user-data [vars]
  {:coreos {:units [{:name "etcd.service" :command "stop" :mask true}
                    {:name "update-engine.service" :command "stop" :mask true}
                    {:name "locksmithd.service" :command "stop" :mask true}
                    {:name "systemd-resolved.service" :command "stop"}
                    {:name "format-var-lib-ephemeral.service" :command "start" :content (snippet "systemd/format-var-lib-ephemeral.service")}
                    {:name "var-lib.mount" :command "start" :content (clojure.string/trim-newline (snippet "systemd/var-lib.mount"))}
                    {:name "dcos-link-env.service" :command "start" :content (snippet "systemd/dcos-link-env.service")}
                    {:name "dcos-download.service" :content (snippet "systemd/dcos-download.service")}
                    {:name "dcos-setup.service" :command "start" :content (clojure.string/trim-newline (snippet "systemd/dcos-setup.service")) :enable true}
                    {:name "dcos-cfn-signal.service" :command "start" :content (clojure.string/trim-newline (from-template "systemd/dcos-cfn-signal.service" vars))}]
            :update {:reboot-strategy "off"}}
   :write_files [{:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/pkginfo.json"
                  :content (snippet "system-files/pkginfo.json")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/cloudenv"
                  :content (from-template "system-files/cloudenv" vars)}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/mesos-master-provider"
                  :content (str "MESOS_CLUSTER=" (:cluster-name vars) "\n")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor"
                  :content (from-template "system-files/exhibitor" vars)}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor.properties"
                  :content (from-template "system-files/exhibitor.properties" vars)}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/dns_config"
                  :content (from-template "system-files/dns_config" vars)}
                 {:path "/etc/mesosphere/cluster-id"
                  :content (:cluster-id vars)
                  :permissions "0644"}
                 {:path "/etc/mesosphere/setup-flags/repository-url"
                  :content "https://downloads.mesosphere.com/dcos/stable\n"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/mesosphere/setup-flags/bootstrap-id"
                  :content "BOOTSTRAP_ID=299269a7aa9e23a1edc94de3f2375356b2942af8\n"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/mesosphere/setup-flags/cluster-packages.json"
                  :content "[\"dcos-config--setup_7660c4e993820a3dea2c017b37c7eeb93151b1da\", \"dcos-detect-ip--setup_7660c4e993820a3dea2c017b37c7eeb93151b1da\", \"dcos-metadata--setup_7660c4e993820a3dea2c017b37c7eeb93151b1da\"]"
                  :owner "root"
                  :permissions 420}]})

(defn mesos-master-user-data [vars]
  (cloud-config (merge-with (comp vec concat)
                            (mesos-instance-user-data vars)
                            {:write_files [{:path "/etc/mesosphere/roles/master"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws_master"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws"
                                            :content ""}]})))

(defn mesos-slave-user-data
  [vars]
  (cloud-config (merge-with (comp vec concat)
                            (mesos-instance-user-data vars)
                            {:write_files [{:path "/etc/mesosphere/roles/slave"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws"
                                            :content ""}]})))

(defn cluster-infra
  [vpc-name cluster-number]


  )
