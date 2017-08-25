(ns terraboot.cluster
  (:require [terraboot.core :refer :all]
            [terraboot.vpc :as vpc]
            [terraboot.cloud-config :refer [cloud-config]]
            [terraboot.utils :refer :all]))

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
                    {:name "docker-cleanup.service" :content (snippet "systemd/docker-cleanup.service")}
                    {:name "docker-cleanup.timer" :command "start" :content (snippet "systemd/docker-cleanup.timer")}
                    {:name "install-confd.service" :command "start" :content (snippet "systemd/install-confd.service")}
                    {:name "confd.service" :command "start" :content (snippet "systemd/confd.service") :enable true}
                    {:name "install-awscli.service" :command "start" :content (snippet "systemd/install-awscli.service") :enable true}
                    {:name "nrpe.service" :command "start" :content (snippet "systemd/nrpe.service") :enable true}]
            :update {:reboot-strategy "off"}}
   :write_files [{:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/pkginfo.json"
                  :content "{}\n"}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/mesos-master-provider"
                  :content (str "MESOS_CLUSTER=$${cluster-name}\n")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/exhibitor"
                  :content (snippet "system-files/exhibitor")}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/dns_config"
                  :content (snippet "system-files/dns_config")}
                 {:path "/etc/mesosphere/cluster-id"
                  :content "$${cluster-id}"
                  :permissions "0644"}
                 {:path "/etc/mesosphere/setup-flags/repository-url"
                  :content "https://downloads.dcos.io/dcos/stable"
                  :owner "root"
                  :permissions "0644"}
                 {:path "/etc/mesosphere/setup-flags/bootstrap-id"
                  :content "BOOTSTRAP_ID=5b4aa43610c57ee1d60b4aa0751a1fb75824c083"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/mesosphere/setup-flags/cluster-packages.json"
                  :content "[\"dcos-config--setup_59db72c6fef6fbca04d7dce3f8dd46a39e24da0f\", \"dcos-metadata--setup_59db72c6fef6fbca04d7dce3f8dd46a39e24da0f\"]\n"
                  :owner "root"
                  :permissions 420}
                 {:path "/etc/systemd/journald.conf.d/dcos.conf"
                  :content (snippet "system-files/dcos.conf")
                  :owner "root"}
                 {:path "/etc/rexray/config.yml"
                  :content (snippet "system-files/rexray.conf")
                  :permissions "0644"}
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/adminrouter.env"
                  :content "ADMINROUTER_ACTIVATE_AUTH_MODULE=false"} ; OAUTH!
                 {:path "/etc/mesosphere/setup-packages/dcos-provider-aws--setup/etc/ui-config.json"
                  :content (snippet "system-files/ui-config.json")} ; OAUTH!
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

(def beats-user-data
  {:coreos {:units [{:name "filebeat.service" :command "start" :content (snippet "systemd/filebeat.service")}
                    {:name "metricbeat.service" :command "start" :content (snippet "systemd/metricbeat.service")}
                    {:name "dcos-journalctl-file.service" :command "start" :content (snippet "systemd/dcos-journalctl-file.service")}
                    {:name "copy-bins.service" :command "start" :content (snippet "systemd/copy-bins.service")}]}
   :write_files [{:path "/etc/beats/filebeat.yml"
                  :content (snippet "system-files/filebeat.yml")}
                 {:path "/etc/beats/metricbeat.yml"
                  :content (snippet "system-files/metricbeat.yml")}]})

(def dockerd-logging
  {:write_files [{:path "/etc/systemd/system/docker.service.d/journald-logging.conf"
                  :content (snippet "system-files/dockerd-journald-logging.conf")
                  :permissions "0655"}]})

(defn mesos-slave-user-data
  []
  (cloud-config (deep-merge-with (comp vec concat)
                                 (mesos-instance-user-data)
                                 {:write_files [{:path "/etc/mesosphere/roles/slave"
                                                 :content ""}
                                                {:path "/etc/mesosphere/roles/aws"
                                                 :content ""}
                                                {:path "/home/core/cassandra-backup/backup-witan.sh"
                                                 :content (snippet "system-files/backup-witan.sh")
                                                 :permissions "0744"}]
                                  :coreos {:units [{:name "backup.service" :content (snippet "systemd/backup.service")}
                                                   {:name "backup.timer" :command "start" :content (snippet "systemd/backup.timer")}]}}
                                 beats-user-data
                                 dockerd-logging)))

(defn mesos-public-slave-user-data
  []
  (cloud-config (deep-merge-with (comp vec concat)
                                 (mesos-instance-user-data)
                                 {:write_files [{:path "/etc/mesosphere/roles/slave_public"
                                                 :content ""}
                                                {:path "/etc/mesosphere/roles/aws"
                                                 :content ""}]}
                                 beats-user-data
                                 dockerd-logging)))

;; arn:aws:s3:::my_corporate_bucket/exampleobject.png
(defn bucket-policy [bucket-name]
  (let [bucket-arn (str "arn:aws:s3:::" bucket-name)]
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

(def send-email-policy
  (policy {"Action" ["ses:SendEmail"]}))

(defn cluster-aws-instance [name spec]
  (let [default-vpc-sgs [(remote-output-of "vpc" "sg-allow-ssh")
                         (remote-output-of "vpc" "sg-all-servers")]]
    (resource "aws_instance" name (-> {:tags {:Name name}
                                       :instance_type "t2.micro"
                                       :monitoring true
                                       :subnet_id (id-of "aws_subnet" "private-a")}
                                      (merge-in spec)
                                      (update-in [:vpc_security_group_ids] concat default-vpc-sgs)))))

(defn open-elb-ports
  [listeners]
  (mapv #(assoc {} :port (or (:port %) (:lb_port %)) :cidr_blocks [all-external]) listeners))

(defn cluster-infra
  [{:keys [vpc-name
           region
           bucket
           profile
           azs
           key-name
           vpc-cidr-block
           cluster-name
           min-number-of-masters
           max-number-of-masters
           master-disk-allocation
           master-instance-type
           min-number-of-slaves
           max-number-of-slaves
           slave-disk-allocation
           slave-instance-type
           min-number-of-public-slaves
           max-number-of-public-slaves
           elb-azs
           public-slave-disk-allocation
           public-slave-instance-type
           subnet-cidr-blocks
           mesos-ami
           public-slave-alb-listeners
           public-slave-alb-sg
           application-policies
           slave-alb-listeners
           slave-alb-sg
           slave-sg
           account-number
           root-dns
           environment
           project]}]
  (let [vpc-unique (vpc-unique-fn vpc-name)
        vpc-id-of (id-of-fn vpc-unique)
        vpc-output-of (output-of-fn vpc-unique)
        cluster-identifier (cluster-identifier vpc-name cluster-name)
        cluster-unique (cluster-unique-fn vpc-name cluster-name)
        cluster-resource (partial resource cluster-unique)
        cluster-security-group (partial scoped-security-group cluster-unique)
        cluster-id-of (id-of-fn cluster-unique)
        cluster-output-of (output-of-fn cluster-unique)
        private-subnets (mapv #(cluster-id-of "aws_subnet" (stringify "private-" %)) azs)
        public-subnets (mapv #(cluster-id-of "aws_subnet" (stringify "public-" %)) azs)
        ;; these subnets are both slightly artificial hacks to put different azs under load balancers
        elb-subnets (mapv #(remote-output-of "vpc" (stringify "subnet-public-" % "-id")) elb-azs)
        elb-private-subnets (mapv #(remote-output-of "vpc" (stringify "subnet-private-" % "-id")) elb-azs)
        elb-listener (account-elb-listener account-number)
        environment-dns (environment-dns environment project root-dns)
        environment-dns-identifier (environment-dns-identifier environment-dns "private")]
    (merge-in
     (remote-state region bucket profile "vpc")
     (add-key-name-to-instances
      key-name
      (in-vpc (remote-output-of "vpc" "vpc-id")
              (apply merge-in (map #(private-public-subnets {:naming-fn cluster-unique
                                                             :region region
                                                             :az %
                                                             :cidr-blocks (% subnet-cidr-blocks)
                                                             :public-route-table (remote-output-of "vpc" "public-route-table")} ) azs))

              (cluster-security-group "admin-security-group" {}
                                      {:from_port 0
                                       :to_port 65535
                                       :cidr_blocks [vpc-cidr-block]}
                                      {:from_port 0
                                       :to_port 65535
                                       :protocol "udp"
                                       :cidr_blocks [vpc-cidr-block]}
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
                                      {:allow-all-sg (cluster-id-of "aws_security_group" "master-security-group")})

              (apply (partial cluster-security-group "public-slave-alb-sg" {}) public-slave-alb-sg)

              (cluster-security-group "public-slave-security-group" {}
                                      {:allow-all-sg (cluster-id-of "aws_security_group" "master-security-group")}
                                      {:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-security-group")}
                                      {:allow-all-sg (cluster-id-of "aws_security_group" "slave-security-group")}
                                      {:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-alb-sg")}
                                      {:port 5001
                                       :cidr_blocks [vpc-cidr-block]})

              (apply (partial cluster-security-group "slave-alb-sg" {}) slave-alb-sg)
              (apply (partial cluster-security-group "slave-security-group" {})
                     (concat [{:allow-all-sg (cluster-id-of "aws_security_group" "public-slave-security-group")}
                              {:allow-all-sg (cluster-id-of "aws_security_group" "slave-security-group")}
                              {:allow-all-sg (cluster-id-of "aws_security_group" "master-security-group")}
                              {:port 2181
                               :source_security_group_id (cluster-id-of "aws_security_group" "lb-security-group")}]
                             (or slave-sg [])))

              (cluster-resource "aws_s3_bucket" "exhibitor-s3-bucket" {:bucket (cluster-unique "exhibitor-s3-bucket")
                                                                       :force_destroy true})

              (cluster-resource "aws_iam_user" "mesos-user" {:name "mesos-user"})

              (cluster-resource "aws_iam_user_policy" "mesos-user-policy-s3"
                                {:name "mesos-user-policy-s3"
                                 :user (cluster-id-of "aws_iam_user" "mesos-user")
                                 :policy (bucket-policy (cluster-unique "exhibitor-s3-bucket"))})
              (cluster-resource "aws_iam_access_key" "host-key" {:user (cluster-id-of "aws_iam_user" "mesos-user")})


              (iam-role "master-role"
                        cluster-unique
                        {:name "master-s3"
                         :policy (bucket-policy (cluster-unique "exhibitor-s3-bucket"))}
                        {:name "master-auto-scaling-policy"
                         :policy auto-scaling-policy}                       )


              (apply (partial iam-role "slave-role"
                              cluster-unique)
                     (concat [{:name "cloudwatch-policy"
                               :policy cloudwatch-metrics-policy}
                              {:name "slave-eip-policy"
                               :policy (policy {"Action" ["ec2:AssociateAddress"]})}
                              {:name "slave-cloudwatch-policy"
                               :policy (policy { "Action" ["cloudwatch:GetMetricStatistics",
                                                           "cloudwatch:ListMetrics",
                                                           "cloudwatch:PutMetricData",
                                                           "EC2:DescribeTags" ]
                                                "Condition" {"Bool" { "aws:SecureTransport" "true"}}})}
                              {:name "slave-email-policy"
                               :policy send-email-policy}] application-policies))


              (template-file (cluster-unique "master-user-data")
                             (mesos-master-user-data)
                             {:aws-region region
                              :cluster-name cluster-name
                              :cluster-id cluster-identifier
                              :server-group (cluster-unique "masters")
                              :master-role (cluster-id-of "aws_iam_role" "master-role")
                              :slave-role (cluster-id-of "aws_iam_role" "slave-role")
                              :aws-access-key (cluster-id-of "aws_iam_access_key" "host-key")
                              :aws-secret-access-key (cluster-output-of "aws_iam_access_key" "host-key" "secret")
                              :exhibitor-s3-bucket (cluster-unique "exhibitor-s3-bucket")
                              :internal-lb-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                              :fallback-dns (vpc/fallback-dns vpc-cidr-block)
                              :number-of-masters min-number-of-masters
                              :mesos-dns "127.0.0.1"
                              :alerts-server (str "alerts." environment-dns)
                              :logstash-dns (str "logstash." environment-dns)})

              (asg "masters"
                   cluster-unique
                   {:image_id mesos-ami
                    :instance_type master-instance-type
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
                    :user_data (rendered-template-file (cluster-unique "master-user-data"))
                    :max_size max-number-of-masters
                    :min_size min-number-of-masters
                    :health_check_type "EC2"
                    :health_check_grace_period 20
                    :root_block_device_size master-disk-allocation
                    :subnets public-subnets
                    :lifecycle {:create_before_destroy true}
                    :elb [{:name "internal-lb"
                           :listener [(elb-listener {:port 80 :protocol "HTTP"})
                                      (elb-listener {:port 2181 :protocol "TCP"})
                                      (elb-listener {:port 8181 :protocol "TCP"})
                                      (elb-listener {:port 53 :protocol "TCP"})]
                           :health_check {:healthy_threshold 2
                                          :unhealthy_threshold 3
                                          :target "HTTP:80/exhibitor/exhibitor/v1/cluster/status"
                                          :timeout 5
                                          :interval 30}
                           :subnets elb-subnets
                           :internal true
                           :security_groups (concat (mapv #(cluster-id-of "aws_security_group" %)  ["lb-security-group"
                                                                                                    "admin-security-group"
                                                                                                    "master-security-group"])
                                                    remote-default-sgs)}]})

              (template-file (cluster-unique "public-slave-user-data")
                             (mesos-public-slave-user-data)
                             {:aws-region region
                              :cluster-name cluster-name
                              :cluster-id cluster-identifier
                              :server-group (cluster-unique "public-slaves")
                              :master-role (cluster-id-of "aws_iam_role" "master-role")
                              :slave-role (cluster-id-of "aws_iam_role" "slave-role")
                              :aws-access-key (cluster-id-of "aws_iam_access_key" "host-key")
                              :aws-secret-access-key (cluster-output-of "aws_iam_access_key" "host-key" "secret")
                              :exhibitor-s3-bucket (cluster-unique "exhibitor-s3-bucket")
                              :internal-lb-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                              :fallback-dns (vpc/fallback-dns vpc-cidr-block)
                              :number-of-masters min-number-of-masters
                              :mesos-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                              :alerts-server (str "alerts." environment-dns)
                              :logstash-ip (remote-output-of "vpc" "logstash-ip")
                              :logstash-dns (str "logstash." environment-dns)})

              (vpc/private-route53-record "masters"
                                          environment-dns
                                          environment-dns-identifier
                                          {:zone_id (remote-output-of "vpc" "private-dns-zone")
                                           :alias {:name (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                                                   :zone_id (cluster-output-of "aws_elb" "internal-lb" "zone_id")
                                                   :evaluate_target_health true}})

              (asg "public-slaves"
                   cluster-unique
                   {:image_id mesos-ami
                    :instance_type public-slave-instance-type
                    :sgs [(cluster-id-of "aws_security_group" "public-slave-security-group")
                          (remote-output-of "vpc" "sg-sends-influx")
                          (remote-output-of "vpc" "sg-sends-gelf")
                          (remote-output-of "vpc" "sg-all-servers")
                          (remote-output-of "vpc" "sg-allow-ssh")]
                    :role (cluster-unique "slave-role")
                    :public_ip true
                    :tags {:Key "role"
                           :PropagateAtLaunch "true"
                           :Value "mesos-slave"}
                    :user_data (rendered-template-file (cluster-unique "public-slave-user-data"))
                    :root_block_device_size public-slave-disk-allocation
                    :max_size max-number-of-public-slaves
                    :min_size min-number-of-public-slaves
                    :health_check_type "EC2"
                    :health_check_grace_period 20
                    :subnets public-subnets
                    :lifecycle {:create_before_destroy true}
                    :default-security-groups remote-default-sgs
                    :alb [{:name "public-apps"
                           :listeners (map #(assoc % :account-number account-number) public-slave-alb-listeners)
                           :subnets elb-subnets
                           :security-groups (concat [(cluster-id-of "aws_security_group" "public-slave-alb-sg")
                                                     (remote-output-of "vpc" "sg-allow-http-https")]
                                                    remote-default-sgs)}]
                    :elb []})

              (template-file (cluster-unique "slave-user-data")
                             (mesos-slave-user-data)
                             {:aws-region region
                              :cluster-name cluster-name
                              :cluster-id cluster-identifier
                              :server-group (cluster-unique "slaves")
                              :master-role (cluster-id-of "aws_iam_role" "master-role")
                              :slave-role (cluster-id-of "aws_iam_role" "slave-role")
                              :aws-access-key (cluster-id-of "aws_iam_access_key" "host-key")
                              :aws-secret-access-key (cluster-output-of "aws_iam_access_key" "host-key" "secret")
                              :exhibitor-s3-bucket (cluster-unique "exhibitor-s3-bucket")
                              :internal-lb-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                              :fallback-dns (vpc/fallback-dns vpc-cidr-block)
                              :number-of-masters min-number-of-masters
                              :mesos-dns (cluster-output-of "aws_elb" "internal-lb" "dns_name")
                              :alerts-server (str "alerts." environment-dns)
                              :logstash-ip (remote-output-of "vpc" "logstash-ip")
                              :logstash-dns (str "logstash." environment-dns)})

              (asg "slaves"
                   cluster-unique
                   {:image_id mesos-ami
                    :instance_type slave-instance-type
                    :sgs (concat [(cluster-id-of "aws_security_group" "slave-security-group")
                                  (remote-output-of "vpc" "sg-all-servers")
                                  (remote-output-of "vpc" "sg-sends-influx")
                                  (remote-output-of "vpc" "sg-sends-gelf")]
                                 remote-default-sgs)
                    :role (cluster-unique "slave-role")
                    :tags {:Key "role"
                           :PropagateAtLaunch "true"
                           :Value "mesos-slave"}
                    :user_data (rendered-template-file (cluster-unique "slave-user-data"))
                    :root_block_device_size slave-disk-allocation
                    :max_size max-number-of-slaves
                    :min_size min-number-of-slaves
                    :health_check_type "EC2" ;; or "ELB"?
                    :health_check_grace_period 20
                    :subnets private-subnets
                    :lifecycle {:create_before_destroy true}
                    :elb []
                    :alb (if (seq slave-alb-listeners)
                           [{:name "internal-tasks"
                             :internal true
                             :listeners (map #(assoc % :account-number account-number) slave-alb-listeners)
                             :subnets elb-private-subnets
                             :security-groups (concat [(cluster-id-of "aws_security_group" "slave-alb-sg")]
                                                      remote-default-sgs)}]
                           [])})
              (when (seq slave-alb-listeners)
                (vpc/private-route53-record "slaves"
                                            environment-dns
                                            environment-dns-identifier
                                            {:zone_id (remote-output-of "vpc" "private-dns-zone")
                                             :alias {:name (cluster-output-of "aws_alb" "internal-tasks" "dns_name")
                                                     :zone_id (cluster-output-of "aws_alb" "internal-tasks" "zone_id")
                                                       :evaluate_target_health true}})))))))
