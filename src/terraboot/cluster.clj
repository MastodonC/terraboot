(ns terraboot.cluster
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :as vpc]
            [terraboot.cloud-config :refer [cloud-config]]))

;; reflects default-sgs but uses the vpc remote output
(def remote-default-sgs [(remote-output-of "vpc" "sg-allow-ssh")
                         (remote-output-of "vpc" "sg-allow-outbound")])

(defn mesos-instance-user-data []
  {:coreos {:units [{:name "etcd.service" :command "stop" :mask true}
                    {:name "update-engine.service" :command "stop" :mask true}
                    {:name "locksmithd.service" :command "stop" :mask true}
                    {:name "systemd-resolved.service" :command "stop"}
                    {:name "systemd-journald.service" :command "restart"}
                    {:name "docker.service" :command "restart" :enable true}
                    {:name "dcos-link-env.service" :command "start" :content (snippet "systemd/dcos-link-env.service")}
                    {:name "dcos-download.service" :content (snippet "systemd/dcos-download.service")}
                    {:name "dcos-setup.service" :command "start" :content (clojure.string/trim-newline (snippet "systemd/dcos-setup.service")) :enable true}
                    {:name "cadvisor.service" :command "start" :content (snippet "systemd/cadvisor.service") :enable true}
                    {:name "docker-cleanup.service" :content (snippet "systemd/docker-cleanup.service")}
                    {:name "docker-cleanup.timer" :command "start" :content (snippet "systemd/docker-cleanup.timer")}
                    {:name "install-confd.service" :command "start" :content (snippet "systemd/install-confd.service")}
                    {:name "confd.service" :command "start" :content (snippet "systemd/confd.service") :enable true}
                    {:name "install-awscli.service" :command "start" :content (snippet "systemd/install-awscli.service") :enable true}
                    {:name "filebeat.service" :command "start" :content (snippet "systemd/filebeat.service") :enable true}
                    {:name "nrpe.service" :command "start" :content (snippet "systemd/nrpe.service") :enable true}]
            :update {:reboot-strategy "off"}}
   :write_files [{:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/pkginfo.json"
                  :content "{}\n"}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/cloudenv"
                  :content (snippet "system-files/cloudenv")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/mesos-master-provider"
                  :content (str "MESOS_CLUSTER=${cluster-name}\n")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor"
                  :content (snippet "system-files/exhibitor")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor.properties"
                  :content (snippet "system-files/exhibitor.properties")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/dns_config"
                  :content (snippet "system-files/dns_config")}
                 {:path "/etc/mesosphere/cluster-id"
                  :content "${cluster-id}"
                  :permissions "0644"}
                 {:path "/etc/mesosphere/setup-flags/repository-url"
                  :content "https://downloads.mesosphere.com/dcos/stable\n"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/mesosphere/setup-flags/bootstrap-id"
                  :content "BOOTSTRAP_ID=18d094b1648521b017622180e3a8e05788a81e80"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/mesosphere/setup-flags/cluster-packages.json"
                  :content "[\"dcos-config--setup_39bcd04b14a990a870cdff4543566e78d7507ba5\", \"dcos-metadata--setup_39bcd04b14a990a870cdff4543566e78d7507ba5\"]\n"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/systemd/journald.conf.d/dcos.conf"
                  :content "[Journal]\nMaxLevelConsole=warning\n"
                  :owner "root"}
                 {:path "/etc/systemd/journal-upload.conf"
                  :content (snippet "systemd/journal-upload.conf")
                  :owner "root"}
                 {:path "/etc/confd/conf.d/ssh-authorized-keys.toml"
                  :content (snippet "system-files/ssh-authorized-keys.toml")}
                 {:path "/etc/confd/templates/ssh-authorized-keys.tmpl"
                  :content (snippet "system-files/ssh-authorized-keys.tmpl")}]})

(defn mesos-master-user-data []
  (cloud-config (merge-with (comp vec concat)
                            (mesos-instance-user-data)
                            {:write_files [{:path "/etc/mesosphere/roles/master"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws_master"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws"
                                            :content ""}]})))

(defn add-to-systemd
  [m systemd]
  (update-in m [:coreos :units] #(vec (concat % systemd))))

(defn mesos-slave-user-data
  []
  (cloud-config (-> (merge-with (comp vec concat)
                                (mesos-instance-user-data)
                                {:write_files [{:path "/etc/mesosphere/roles/slave"
                                                :content ""}
                                               {:path "/etc/mesosphere/roles/aws"
                                                :content ""}
                                               {:path "/home/core/cassandra-backup/backup-witan.sh"
                                                :content (snippet "system-files/backup-witan.sh")
                                                :permissions "0744"}]})
                    (add-to-systemd [{:name "backup.service" :content (snippet "systemd/backup.service")}
                                     {:name "backup.timer" :command "start" :content (snippet "systemd/backup.timer")}] )  )))

(defn mesos-public-slave-user-data
  []
  (cloud-config (merge-with (comp vec concat)
                            (mesos-instance-user-data)
                            {:write_files [{:path "/etc/mesosphere/roles/slave_public"
                                            :content ""}
                                           {:path "/etc/mesosphere/roles/aws"
                                            :content ""}]})))

(defn exhibitor-bucket-policy [bucket-name]
  (let [bucket-arn (arn-of "aws_s3_bucket" bucket-name)]
    (policy {"Action" ["s3:AbortMultipartUpload",
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
             "Resource" [bucket-arn
                         (str bucket-arn "/*")]})))

(def auto-scaling-policy
  (policy {"Action" ["ec2:DescribeKeyPairs",
                     "ec2:DescribeSubnets",
                     "autoscaling:DescribeLaunchConfigurations",
                     "autoscaling:UpdateAutoScalingGroup",
                     "autoscaling:DescribeAutoScalingGroups",
                     "autoscaling:DescribeScalingActivities",
                     "elasticloadbalancing:DescribeLoadBalancers"]}))

(def full-amazon-s3-access
  (policy {"Action" "s3:*"}))

(def cloudwatch-metrics-policy
  (policy {"Action" ["cloudwatch:GetMetricStatistics"
                     "cloudwatch:ListMetrics"
                     "cloudwatch:PutMetricData"
                     "EC2:DescribeTags"]
           "Condition" {"Bool" {"aws:SecureTransport" "true"}}}))

(defn cluster-aws-instance [name spec]
  (let [default-vpc-sgs [(remote-output-of "vpc" "sg-allow-ssh")
                         (remote-output-of "vpc" "sg-all-servers")]]
    (resource "aws_instance" name (-> {:tags {:Name name}
                                       :instance_type "t2.micro"
                                       :key_name "ops-terraboot"
                                       :monitoring true
                                       :subnet_id (id-of "aws_subnet" "private-a")}
                                      (merge-in spec)
                                      (update-in [:vpc_security_group_ids] concat default-vpc-sgs)))))


(defn local-deploy-scripts [{:keys [cluster-name
                                    internal-lb
                                    name-fn
                                    min-number-of-slaves]}]
  (let [cluster-resource (partial resource name-fn)
        cluster-output-of (fn [type name & values] (apply (partial output-of type (name-fn name)) values))
        directory-name (str "~/" cluster-name)
        dump-local-file (fn [content file-name] (str "mkdir -p " directory-name "; echo '" content "' > " directory-name "/" file-name))
        make-executable (fn [file-name] (str "chmod +x " directory-name "/" file-name))]
    ;; local resources: easy customized local access to the cluster
    (merge-in
     (cluster-resource "template_file" "cassandra_deploy"
                       {:template (snippet "local-exec/cassandra-production.json")
                        :vars {:cassandra_node_count min-number-of-slaves
                               :cassandra_seed_count (max (quot min-number-of-slaves 3) 1)}
                        :provisioner {"local-exec" {"cassandra-marathon"
                                                    {:command (dump-local-file (cluster-output-of "template_file" "cassandra_deploy" "rendered") (str "cassandra-marathon.json"))}}}})

     (cluster-resource "template_file" "deploy-sh"
                       {:template (snippet "local-exec/deploy.sh")
                        :vars {:internal-lb internal-lb
                               :cluster-name cluster-name}
                        :provisioner {"local-exec" {"deploy-sh"
                                                    {:command (str (dump-local-file (cluster-output-of "template_file" "deploy-sh" "rendered") "deploy.sh") ";"
                                                                   (make-executable "deploy.sh"))}}}})

     (cluster-resource "template_file" "dcos-cli-install"
                       {:template (snippet "local-exec/dcos-cli-install.sh")
                        :vars {:internal-lb internal-lb}
                        :provisioner {"local-exec" {"dcos-cli-install"
                                                    {:command (str (dump-local-file (cluster-output-of "template_file" "dcos-cli-install" "rendered") "dcos-cli-install.sh") ";"
                                                                   (make-executable "dcos-cli-install.sh"))}}}})

     (cluster-resource "template_file" "open-mesos-admin"
                       {:template (str "open http://" internal-lb)
                        :provisioner {"local-exec" {"open-mesos-admin"
                                                    {:command (str (dump-local-file (cluster-output-of "template_file" "open-mesos-admin" "rendered") "open-mesos-admin.sh") ";"
                                                                   (make-executable "open-mesos-admin.sh"))}}}}
                       ))))

(defn logstash-user-data []
  (cloud-config {:package_update true
                 :packages ["oracle-java8-installer" "oracle-java8-set-default" "logstash" "dnsmasq"]
                 :apt_sources
                 [{:source "ppa:webupd8team/java"}
                  {:source "deb http://packages.elastic.co/logstash/2.2/debian stable main"
                   :key (snippet "system-files/elasticsearch-apt.pem")} ]
                 :write_files
                 [{:path "/etc/logstash/conf.d/in-gelf.conf"
                   :content (snippet "vpc-logstash/in-gelf.conf")}
                  {:path "/etc/logstash/conf.d/out-logstash.conf"
                   :content (snippet "vpc-logstash/out-logstas.conf")}
                  {:path "/etc/ssl/ca.cert"
                   :content (snippet "vpn-keys/ca.crt")}
                  {:path "/etc/dnsmasq.conf"
                   :content (snippet "system-files/dnsmasq.conf")}]
                 :bootcmd
                 ["cloud-init-per once accepted-oracle-license-v1-1 echo \"oracle-java8-installer shared/accepted-oracle-license-v1-1 select true\" | debconf-set-selections"]
                 :runcmd
                 ["update-rc.d logstash defaults"]}))

(defn open-elb-ports
  [listeners]
  (mapv #(assoc {} :port (or (:port %) (:lb_port %)) :cidr_blocks [all-external]) listeners))

(defn cluster-infra
  [{:keys [vpc-name
           cluster-name
           min-number-of-masters
           max-number-of-masters
           master-disk-allocation
           min-number-of-slaves
           max-number-of-slaves
           slave-disk-allocation
           min-number-of-public-slaves
           max-number-of-public-slaves
           public-slave-disk-allocation
           azs
           subnet-cidr-blocks
           mesos-ami
           public-slave-elb-listeners
           public-slave-elb-health
           account-number]}]
  (let [vpc-unique (fn [name] (str vpc-name "-" name))
        vpc-id-of (fn [type name] (id-of type (vpc-unique name)))
        cluster-identifier (str vpc-name "-" cluster-name)
        cluster-unique (fn [name] (str cluster-identifier "-" name))
        cluster-resource (partial resource cluster-unique)
        cluster-security-group (partial scoped-security-group cluster-unique)
        cluster-id-of (fn [type name] (id-of type (cluster-unique name)))
        cluster-output-of (fn [type name & values] (apply (partial output-of type (cluster-unique name)) values))
        private-subnets (mapv #(cluster-id-of "aws_subnet" (stringify "private-" %)) azs)
        public-subnets (mapv #(cluster-id-of "aws_subnet" (stringify "public-" %)) azs)
        elb-listener (account-elb-listener account-number)]
    (merge-in
     (remote-state "vpc")
     (in-vpc (remote-output-of "vpc" "vpc-id")
             (apply merge-in (map #(private-public-subnets {:naming-fn cluster-unique
                                                            :az %
                                                            :cidr-blocks (% subnet-cidr-blocks)
                                                            :public-route-table (remote-output-of "vpc" "public-route-table")} ) azs))

             (cluster-security-group "admin-security-group" {}
                                     {:from_port 0
                                      :to_port 65535
                                      :cidr_blocks [vpc/vpc-cidr-block]}
                                     {:from_port 0
                                      :to_port 65535
                                      :protocol "udp"
                                      :cidr_blocks [vpc/vpc-cidr-block]}
                                     )

             (cluster-security-group "lb-security-group" {}
                                     {:port 2181
                                      :source_security_group_id (cluster-id-of "aws_security_group" "slave-security-group")}
                                     {:type "egress"
                                      :from_port 0
                                      :to_port 0
                                      :protocol -1
                                      :cidr_blocks [all-external]
                                      })

             (cluster-security-group "master-security-group" {}
                                     {:port 5050
                                      :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")}
                                     {:port 80
                                      :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")}
                                     {:port 8080
                                      :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")}
                                     {:port 8181
                                      :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")}
                                     {:port 2181
                                      :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "slave-security-group")}
                                     )

             (apply (partial cluster-security-group "public-slave-elb" {}) (open-elb-ports public-slave-elb-listeners))

             (cluster-security-group "public-slave-security-group" {}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "master-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "slave-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-elb")})

             (cluster-security-group "slave-security-group" {}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "slave-security-group")}
                                     {:allow-all-sg (cluster-id-of "aws_security_group" "master-security-group")}
                                     {:port 2181
                                      :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")})

             (cluster-resource "aws_s3_bucket" "exhibitor-s3-bucket" {:bucket (cluster-unique "exhibitor-s3-bucket")})

             (cluster-resource "aws_iam_user" "mesos-user" {:name "mesos-user"})

             (cluster-resource "aws_iam_user_policy" "mesos-user-policy-s3"
                               {:name "mesos-user-policy-s3"
                                :user (cluster-id-of "aws_iam_user" "mesos-user")
                                :policy (exhibitor-bucket-policy (cluster-unique "exhibitor-s3-bucket"))})
             (cluster-resource "aws_iam_access_key" "host-key" {:user (cluster-id-of "aws_iam_user" "mesos-user")})


             (iam-role "master-role"
                       cluster-unique
                       {:name "master-s3"
                        :policy (exhibitor-bucket-policy (cluster-unique "exhibitor-s3-bucket"))}
                       {:name "master-auto-scaling-policy"
                        :policy auto-scaling-policy}                       )


             (iam-role "slave-role"
                       cluster-unique
                       {:name "amazon-s3-policy"
                        :policy full-amazon-s3-access}
                       {:name "cloudwatch-policy"
                        :policy cloudwatch-metrics-policy}
                       {:name "slave-eip-policy"
                        :policy (policy {"Action" ["ec2:AssociateAddress"]})}
                       {:name "slave-cloudwatch-policy"
                        :policy (policy { "Action" ["cloudwatch:GetMetricStatistics",
                                                    "cloudwatch:ListMetrics",
                                                    "cloudwatch:PutMetricData",
                                                    "EC2:DescribeTags" ]
                                         "Condition" {"Bool" { "aws:SecureTransport" "true"}}
                                         })})


             (cluster-resource "template_file" "master-user-data"
                               {:template (mesos-master-user-data)
                                :vars {:aws-region region
                                       :cluster-name cluster-name
                                       :cluster-id cluster-identifier
                                       :server-group (cluster-unique "masters")
                                       :master-role (cluster-id-of "aws_iam_role" "master-role")
                                       :slave-role (cluster-id-of "aws_iam_role" "slave-role")
                                       :aws-access-key (cluster-id-of "aws_iam_access_key" "host-key")
                                       :aws-secret-access-key (cluster-output-of "aws_iam_access_key" "host-key" "secret")
                                       :exhibitor-s3-bucket (cluster-unique "exhibitor-s3-bucket")
                                       :internal-lb-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                       :fallback-dns (vpc/fallback-dns vpc/vpc-cidr-block)
                                       :number-of-masters min-number-of-masters
                                       :influxdb-dns (str "influxdb." (vpc/vpc-dns-zone vpc-name))
                                       :mesos-dns "127.0.0.1"
                                       :alerts-server (str "alerts." (vpc/vpc-dns-zone vpc-name))}
                                :lifecycle { :create_before_destroy true }
                                })


             (asg "masters"
                  cluster-unique
                  {:image_id mesos-ami
                   :instance_type "m4.large"
                   :sgs (concat [(cluster-id-of "aws_security_group" "master-security-group")
                                 (cluster-id-of "aws_security_group" "admin-security-group")
                                 (remote-output-of "vpc" "sg-sends-influx")
                                 (remote-output-of "vpc" "sg-sends-gelf")
                                 (remote-output-of "vpc" "sg-all-servers")]
                                remote-default-sgs)
                   :role (cluster-unique "master-role")
                   :public_ip true
                   :tags {:Key "role"
                          :PropagateAtLaunch "true"
                          :Value "mesos-master"}
                   :user_data (cluster-output-of "template_file" "master-user-data" "rendered")
                   :max_size max-number-of-masters
                   :min_size min-number-of-masters
                   :health_check_type "EC2"
                   :health_check_grace_period 20
                   :root_block_device_size master-disk-allocation
                   :subnets public-subnets
                   :lifecycle {:create_before_destroy true}
                   :elb [{:name "internal-lb"
                          :listeners [(elb-listener {:port 80 :protocol "HTTP"})
                                      (elb-listener {:port 5050 :protocol "HTTP"})
                                      (elb-listener {:port 2181 :protocol "TCP"})
                                      (elb-listener {:port 8181 :protocol "HTTP"})
                                      (elb-listener {:port 8080 :protocol "HTTP"})
                                      (elb-listener {:port 53 :protocol "TCP"})]
                          :health_check {:healthy_threshold 2
                                         :unhealthy_threshold 3
                                         :target "HTTP:8181/exhibitor/v1/cluster/status"
                                         :timeout 5
                                         :interval 30}
                          :subnets public-subnets
                          :internal true
                          :security-groups (concat (mapv #(cluster-id-of "aws_security_group" %)  ["lb-security-group"
                                                                                                   "admin-security-group"
                                                                                                   "master-security-group"])
                                                   remote-default-sgs)
                          }]})

             (cluster-resource "template_file" "public-slave-user-data"
                               {:template (mesos-public-slave-user-data)
                                :vars {:aws-region region
                                       :cluster-name cluster-name
                                       :cluster-id cluster-identifier
                                       :server-group (cluster-unique "public-slaves")
                                       :master-role (cluster-id-of "aws_iam_role" "master-role")
                                       :slave-role (cluster-id-of "aws_iam_role" "slave-role")
                                       :aws-access-key (cluster-id-of "aws_iam_access_key" "host-key")
                                       :aws-secret-access-key (cluster-output-of "aws_iam_access_key" "host-key" "secret")
                                       :exhibitor-s3-bucket (cluster-unique "exhibitor-s3-bucket")
                                       :internal-lb-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                       :fallback-dns (vpc/fallback-dns vpc/vpc-cidr-block)
                                       :number-of-masters min-number-of-masters
                                       :influxdb-dns (str "influxdb." (vpc/vpc-dns-zone vpc-name))
                                       :mesos-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                       :alerts-server (str "alerts." (vpc/vpc-dns-zone vpc-name)) }
                                :lifecycle { :create_before_destroy true }})

             (vpc/private_route53_record (str cluster-name "-masters") vpc-name
                                         {:zone_id (remote-output-of "vpc" "private-dns-zone")
                                          :alias {:name (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                                  :zone_id (cluster-output-of "aws_elb" "internal-lb" "zone_id")
                                                  :evaluate_target_health true}})

             (asg "public-slaves"
                  cluster-unique
                  {:image_id mesos-ami
                   :instance_type "m4.xlarge"
                   :sgs [(cluster-id-of "aws_security_group" "public-slave-security-group")
                         (remote-output-of "vpc" "sg-sends-influx")
                         (remote-output-of "vpc" "sg-sends-gelf")
                         (remote-output-of "vpc" "sg-all-servers")]
                   :role (cluster-unique "slave-role")
                   :public_ip true
                   :tags {:Key "role"
                          :PropagateAtLaunch "true"
                          :Value "mesos-slave"}
                   :user_data (cluster-output-of "template_file" "public-slave-user-data" "rendered")
                   :root_block_device_size public-slave-disk-allocation
                   :max_size max-number-of-public-slaves
                   :min_size min-number-of-public-slaves
                   :health_check_type "EC2"
                   :health_check_grace_period 20
                   :subnets public-subnets
                   :lifecycle {:create_before_destroy true}
                   :default-security-groups remote-default-sgs
                   :elb [{:name "public-slaves"
                          :health_check {:healthy_threshold 2
                                         :unhealthy_threshold 2
                                         :target public-slave-elb-health
                                         :timeout 5
                                         :interval 30}
                          :lb_protocol "https"

                          :listeners (mapv elb-listener public-slave-elb-listeners)
                          :subnets public-subnets
                          :security-groups (concat [(cluster-id-of "aws_security_group" "public-slave-security-group")
                                                    (remote-output-of "vpc" "sg-allow-http-https")]
                                                   remote-default-sgs)}]})

             (route53_record (cluster-unique "deploy")
                             {:alias {:name (cluster-output-of "aws_elb" "public-slaves" "dns_name")
                                      :zone_id (cluster-output-of "aws_elb" "public-slaves" "zone_id")
                                      :evaluate_target_health true}})

             (route53_record cluster-identifier
                             {:alias {:name (cluster-output-of "aws_elb" "public-slaves" "dns_name")
                                      :zone_id (cluster-output-of "aws_elb" "public-slaves" "zone_id")
                                      :evaluate_target_health true}})

             (cluster-resource "template_file" "slave-user-data"
                               {:template (mesos-slave-user-data)
                                :vars {:aws-region region
                                       :cluster-name cluster-name
                                       :cluster-id cluster-identifier
                                       :server-group (cluster-unique "slaves")
                                       :master-role (cluster-id-of "aws_iam_role" "master-role")
                                       :slave-role (cluster-id-of "aws_iam_role" "slave-role")
                                       :aws-access-key (cluster-id-of "aws_iam_access_key" "host-key")
                                       :aws-secret-access-key (cluster-output-of "aws_iam_access_key" "host-key" "secret")
                                       :exhibitor-s3-bucket (cluster-unique "exhibitor-s3-bucket")
                                       :internal-lb-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                       :fallback-dns (vpc/fallback-dns vpc/vpc-cidr-block)
                                       :number-of-masters min-number-of-masters
                                       :influxdb-dns (str "influxdb." (vpc/vpc-dns-zone vpc-name))
                                       :mesos-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                       :alerts-server (str "alerts." (vpc/vpc-dns-zone vpc-name))
                                       }
                                :lifecycle { :create_before_destroy true }

                                })


             (asg "slaves"
                  cluster-unique
                  {:image_id mesos-ami
                   :instance_type "m4.xlarge"
                   :sgs (concat [(cluster-id-of "aws_security_group" "slave-security-group")
                                 (remote-output-of "vpc" "sg-all-servers")
                                 (remote-output-of "vpc" "sg-sends-influx")
                                 (remote-output-of "vpc" "sg-sends-gelf")]
                                remote-default-sgs)
                   :role (cluster-unique "slave-role")
                   :tags {:Key "role"
                          :PropagateAtLaunch "true"
                          :Value "mesos-slave"}
                   :user_data  (cluster-output-of "template_file" "slave-user-data" "rendered")
                   :root_block_device_size slave-disk-allocation
                   :max_size max-number-of-slaves
                   :min_size min-number-of-slaves
                   :health_check_type "EC2" ;; or "ELB"?
                   :health_check_grace_period 20
                   :subnets private-subnets
                   :lifecycle {:create_before_destroy true}
                   :elb []
                   })


             (local-deploy-scripts {:cluster-name cluster-name
                                    :name-fn cluster-unique
                                    :min-number-of-slaves min-number-of-slaves
                                    :internal-lb (cluster-output-of "aws_elb" "internal-lb" "dns_name")})
             ))))
