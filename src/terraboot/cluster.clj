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

(defn mesos-public-slave-user-data
  [vars]
  (cloud-config (merge-with (comp vec concat)
                            (mesos-instance-user-data vars)
                            {:write_files [{:path "/etc/mesosphere/roles/slave_public"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws"
                                            :content ""}]})))

(defn exhibitor-bucket-name [cluster-name]
  (str cluster-name "-exhibitor-s3-bucket"))

(defn cluster-infra
  [vpc-name cluster-name]
  (merge-in
   (in-vpc vpc-name
           (security-group "admin-security-group" {}
                           {:from_port 0
                            :to_port 65535
                            :cidr_blocks (vec (vals (:public vpc/cidr-block)))}
                           {:from_port 0
                            :to_port 65535
                            :protocol "udp"
                            :cidr_blocks (vec (vals (:public vpc/cidr-block)))}
                           {:from_port 0
                            :to_port 65535
                            :protocol "udp"
                            :cidr_blocks (vec (vals (:public vpc/cidr-block)))})

           (security-group "lb-security-group" {}
                           {:port 2181
                            :source_security_group_id (id-of "aws_security_group" "slave-security-group")})

           (security-group "master-security-group" {}
                           {:port 5050
                            :source_security_group_id (id-of "aws_security_group" "lb-security-group")}
                           {:port 80
                            :source_security_group_id (id-of "aws_security_group" "lb-security-group")}
                           {:port 8080
                            :source_security_group_id (id-of "aws_security_group" "lb-security-group")}
                           {:port 8181
                            :source_security_group_id (id-of "aws_security_group" "lb-security-group")}
                           {:port 2181
                            :source_security_group_id (id-of "aws_security_group" "lb-security-group")}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "public-slave-security-group")}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "slave-security-group")}
                           )

           (security-group "public-slave-security-group" {}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "master-security-group")}
                           {:from_port 0
                            :to_port 21
                            :cidr_blocks [all-external]}
                           {:from_port 0
                            :to_port 21
                            :protocol "udp"
                            :cidr_blocks [all-external]}
                           {:port 22
                            :cidr_blocks [vpc/vpc-cidr-block]}
                           {:from_port 23
                            :to_port 5050
                            :cidr_blocks [all-external]}
                           {:from_port 23
                            :to_port 5050
                            :protocol "udp"
                            :cidr_blocks [all-external]}
                           {:from_port 5052
                            :to_port 65535
                            :cidr_blocks [all-external]}
                           {:from_port 5052
                            :to_port 65535
                            :protocol "udp"
                            :cidr_blocks [all-external]}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "public-slave-security-group")}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "slave-security-group")})

           (security-group "slave-security-group" {}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "public-slave-security-group")}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "slave-security-group")}
                           {:from_port 0
                            :to_port 65535
                            :protocol -1
                            :source_security_group_id (id-of "aws_security_group" "master-security-group")}
                           {:port 2181
                            :source_security_group_id (id-of "aws_security_group" "lb-security-group")})

           (resource "aws_s3_bucket" "exhibitor-s3-bucket" {:bucket (exhibitor-bucket-name cluster-name)})

           (resource "aws_iam_access_key" "host-key" {:user (id-of "aws_iam_user" "mesos-user")})

           (resource "aws_iam_user" "mesos-user" {:name "mesos-user"})

           (resource "aws_iam_user_policy" "mesos-user-policy-s3"
                     {:name "mesos-user-policy-s3"
                      :user (id-of "aws_iam_user" "mesos-user")
                      :policy (policy {"Statement" {"Action" ["s3:AbortMultipartUpload",
                                                              "s3:DeleteObject",
                                                              "s3:GetBucketAcl",
                                                              "s3:GetBucketPolicy",
                                                              "s3:GetObject",
                                                              "s3:GetObjectAcl",
                                                              "s3:ListBucket",
                                                              "s3:ListBucketMultipartUploads",
                                                              "s3:ListMultipartUploadParts",
                                                              "s3:PutObject",
                                                              "s3:PutObjectAcl"]
                                                    "Effect" "Allow"
                                                    "Resource" [(str "arn:aws:s3:::" (exhibitor-bucket-name cluster-name) "/*")
                                                                (str "arn:aws:s3:::" (exhibitor-bucket-name cluster-name))]}})})
           (resource "aws_iam_user_policy" "mesos-user-policy-ec2"
                     {:name "mesos-user-policy-ec2"
                      :user (id-of "aws_iam_user" "mesos-user")
                      :policy (policy {"Statement" {"Action" ["ec2:DescribeKeyPairs",
                                                              "ec2:DescribeSubnets",
                                                              "autoscaling:DescribeLaunchConfigurations",
                                                              "autoscaling:UpdateAutoScalingGroup",
                                                              "autoscaling:DescribeAutoScalingGroups",
                                                              "autoscaling:DescribeScalingActivities",
                                                              "elasticloadbalancing:DescribeLoadBalancers"]
                                                    "Effect" "Allow"
                                                    "Resource" "*"}})})


           ))

  )
