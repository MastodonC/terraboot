(ns terraboot.cluster
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :as vpc]
            [terraboot.cloud-config :refer [cloud-config]]))

(defn mesos-master-user-data [vars]
  (cloud-config {:coreos {:units [{:name "etcd.service" :command "stop" :mask true}
                                  {:name "update-engine.service" :command "stop" :mask true}
                                  {:name "locksmithd.service" :command "stop" :mask true}
                                  {:name "systemd-resolved.service" :command "stop"}
                                  {:name "format-var-lib-ephemeral.service" :command "start" :content (snippet "systemd/format-var-lib-ephemeral.service")}
                                  {:name "var-lib.mount" :command "start" :content (clojure.string/trim-newline (snippet "systemd/var-lib.mount"))}
                                  {:name "dcos-link-env.service" :command "start" :content (snippet "systemd/dcos-link-env.service")}
                                  {:name "dcos-download.service" :content (snippet "systemd/dcos-download.service")}
                                  {:name "dcos-setup.service" :command "start" :content (clojure.string/trim-newline (snippet "systemd/dcos-setup.service")) :enable true}
                                  {:name "dcos-cfn-signal.service" :command "start" :content (clojure.string/trim-newline (snippet "systemd/dcos-cfn-signal.master.service"))}]
                          :update {:reboot-strategy "off"}}
                 :files {"/etc/mesosphere/setup-packages/dcos-provider-aws--setup/pkginfo.json"
                         {:content (snippet "system-files/pkginfo.json")}
                         "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/cloudenv"
                         {:content (snippet "system-files/cloudenv")}
                         "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/mesos-master-provider"
                         {:content "MESOS_CLUSTER=witan-production"}
                         "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor"
                         {:content (snippet "system-files/exhibitor")}
                         "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor.properties"
                         {:content (snippet "system-files/exhibitor.properties")}
                         "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/dns_config"
                         {:content (snippet "system-files/dns_config")}
                         "/etc/mesosphere/cluster-id"
                         {:content "arn:aws:cloudformation:eu-central-1:165664414043:stack/witan-production/4faef210-d029-11e5-91a2-500c52a6cefe"
                          :permissions "0644"}
                         "/etc/mesosphere/setup-flags/repository-url"
                         {:content "https://downloads.mesosphere.com/dcos/stable"
                          :owner "root"
                          :permissions 420}
                         "/etc/mesosphere/setup-flags/bootstrap-id"
                         {:content "BOOTSTRAP_ID=299269a7aa9e23a1edc94de3f2375356b2942af8"
                          :owner "root"
                          :permissions 420}
                         "/etc/mesosphere/setup-flags/cluster-packages.json"
                         {:content "[\"dcos-config--setup_7660c4e993820a3dea2c017b37c7eeb93151b1da\", \"dcos-detect-ip--setup_7660c4e993820a3dea2c017b37c7eeb93151b1da\", \"dcos-metadata--setup_7660c4e993820a3dea2c017b37c7eeb93151b1da\"]"
                          :owner "root"
                          :permissions 420}
                         "/etc/mesosphere/roles/master"
                         {:content ""}
                         "/etc/mesosphere/roles/aws_master"
                         {:content ""}
                         "/etc/mesosphere/roles/aws"
                         {:content ""}}}))

(defn cluster-infra
  [vpc-name cluster-number]


   )
