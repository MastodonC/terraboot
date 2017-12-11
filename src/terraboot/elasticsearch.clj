(ns terraboot.elasticsearch
  (:require [terraboot.core :refer :all]
            [terraboot.utils :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.core.strint :refer [<<]]
            [terraboot.user-data :refer :all]))

(def stop-update-engine {:name "update-engine.service"
                         :command "stop"})

(def stop-locksmithd {:name "locksmithd.service"
                      :command "stop"})

(def common-coreos-units [stop-update-engine
                          stop-locksmithd])

(defn docker-systemd-unit
  ([org-name image-name] (docker-systemd-unit org-name image-name {}))
  ([org-name image-name {options :options
                         entry-point :entry-point
                         release :release
                         :or {options []
                              entry-point ""
                              release "latest"}}]
   (let [option-string (str/join " " options)
         full-image-name (str org-name "/" image-name)]
     {:name (str image-name ".service")
      :command "start"
      :enable true
      :content (<<
                "[Unit]
Description=~{image-name}
After=docker.service
Requires=docker.service

[Service]
Slice=machine.slice
TimeoutSec=600
ExecStartPre=/usr/bin/docker pull ~{full-image-name}:~{release}
ExecStartPre=-/usr/bin/docker kill ~{image-name}
ExecStartPre=-/usr/bin/docker rm ~{image-name}
ExecStart=/usr/bin/docker run --log-driver journald --name ~{image-name} ~{option-string} ~{full-image-name}:~{release} ~{entry-point}
ExecStop=/usr/bin/docker stop ~{image-name}

[Install]
WantedBy=multi-user.target")})))

(defn cloud-config-coreos [units]
  (cloud-config (deep-merge-with (comp vec concat)
                                 {:coreos {:update
                                                  {:reboot-strategy "off"}
                                           :units (concat common-coreos-units
                                                          units)}}
                                 beats-user-data
                                 elastalert-user-data
                                 dockerd-logging)))

(defn logstash-user-data-coreos [es-endpoint region]
  (let [logstash (docker-systemd-unit "mastodonc" "logstash-ng"
                                      {:options [(str "--env " "ES_HOST=" es-endpoint)
                                                 (str "--env " "REGION=" region)
                                                 "--net=host"]
                                       :entry-point "-f /etc/logstash/logstash.conf"})
        nginx (docker-systemd-unit "mastodonc" "kibana-nginx"
                                   {:options [(str "--env " "ES_HOST=" es-endpoint)
                                              "--net=host"]})
        elastalert (docker-systemd-unit "bitsensor" "elastalert"
                                       {:options ["-v /opt/elastalert/config.yaml:/opt/elastalert/config.yaml"
                                                  "-v /opt/elastalert/config.json:/opt/elastalert-server/config/config.sjon"
                                                  "-v /opt/elastalert/rules:/opt/elastalert/rules"
                                                  "--net=host"]})
        ]
    (cloud-config-coreos [logstash
                          nginx
                          elastalert])))

(defn elasticsearch-policy
  [name es-allowed-ips]
  (json/generate-string {"Version" "2012-10-17",
                         "Statement" [{"Action" "es:*",
                                       "Principal" "*",
                                       "Resource" (str (output-of "aws_elasticsearch_domain" name :arn) "/*"),
                                       "Effect" "Allow",
                                       "Condition"
                                       {
                                        "IpAddress"
                                        {"aws:SourceIp" (into ["$${allowed-ips}"] es-allowed-ips)}}}]}))

(defn elasticsearch-cluster [name {:keys [account-number
                                          cert-name
                                          default-ami
                                          es-allowed-ips
                                          es-ebs-volume-size
                                          es-endpoint
                                          es-instance-count
                                          es-instance-type
                                          key-name
                                          logstash-ami
                                          region azs
                                          vpc-cidr-block
                                          vpc-name
                                          environment
                                          project
                                          root-dns
                                          cluster-name
                                          elastalert-repo-url] :as spec}]
  (let [vpc-unique (vpc-unique-fn vpc-name)
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (id-of-fn vpc-unique)
        vpc-output-of (output-of-fn vpc-unique)
        vpc-security-group (partial scoped-security-group vpc-unique)
        elb-listener (account-elb-listener account-number)
        es-arn (str "arn:aws:es:" region ":" account-number ":domain/" name)
        es-arn-* (str es-arn "/*")
        environment-dns (environment-dns environment project root-dns)
        cluster-unique (cluster-unique-fn vpc-name cluster-name)]

    (merge-in
     (template-file (vpc-unique "elasticsearch-policy")
                    (elasticsearch-policy name es-allowed-ips)
                    {:es-arn es-arn-*
                     :allowed-ips (vpc-output-of "aws_eip" "logstash" "public_ip")})

     (resource "aws_elasticsearch_domain" name
               {:domain_name name
                :elasticsearch_version "5.1"
                :advanced_options { "rest.action.multi.allow_explicit_index" "true"}
                :cluster_config {:instance_count (or es-instance-count 2),
                                 :instance_type (or es-instance-type "m4.large.elasticsearch")}
                :ebs_options {:ebs_enabled true,
                              :volume_type "gp2",
                              :volume_size (or es-ebs-volume-size 100)
                              },
                :snapshot_options { :automated_snapshot_start_hour 23}})

     (resource "aws_elasticsearch_domain_policy" (str name "-policy")
               {:domain_name (output-of "aws_elasticsearch_domain" name :domain_name)
                :access_policies (rendered-template-file (vpc-unique "elasticsearch-policy"))})

     (vpc-resource "aws_iam_role" "logstash" {:name "logstash"
                                              :assume_role_policy (json/generate-string {
                                                                                         "Version" "2012-10-17",
                                                                                         "Statement" [
                                                                                                      {
                                                                                                       "Action" "sts:AssumeRole",
                                                                                                       "Principal" {
                                                                                                                    "Service" "ec2.amazonaws.com"
                                                                                                                    },
                                                                                                       "Effect" "Allow",
                                                                                                       "Sid" ""}]})})

     (vpc-resource "aws_iam_role_policy" "logstash"
                   {"name" "logstash"
                    "role"  (vpc-id-of "aws_iam_role" "logstash")
                    "policy" (json/generate-string {
                                                    "Version" "2012-10-17"
                                                    "Statement" [{
                                                                  "Resource" es-arn-*,
                                                                  "Action" ["es:*"],
                                                                  "Effect" "Allow"
                                                                  }
                                                                 {
                                                                  "Resource" es-arn,
                                                                  "Action" ["es:*"],
                                                                  "Effect" "Allow"
                                                                  }
                                                                 ]})})

     (vpc-resource "aws_iam_instance_profile" "logstash" {
                                                          :name "logstash"
                                                          :role (vpc-output-of "aws_iam_role" "logstash" "name")
                                                          })

     (add-key-name-to-instances
      key-name
      (in-vpc (id-of "aws_vpc" vpc-name)
              (vpc-security-group "logstash" {}
                                  {:port 9200
                                   :protocol "tcp"
                                   :cidr_blocks [vpc-cidr-block]})

              (vpc-resource "aws_eip" "logstash"
                            {:vpc true
                             :instance (vpc-id-of "aws_instance" "logstash")})

              (vpc-security-group "sends_logstash" {})

              (template-file (cluster-unique "logstash-user-data")
                             (logstash-user-data-coreos (output-of "aws_elasticsearch_domain" name :endpoint) region)
                             {:cluster-name          cluster-name
                              :logstash-dns          (str "logstash." environment-dns)
                              :elastalert-repo-url   elastalert-repo-url})

              (aws-instance (vpc-unique "logstash") {:ami logstash-ami
                                                     :instance_type "m4.large"
                                                     :vpc_security_group_ids [(vpc-id-of "aws_security_group" "logstash")
                                                                              (id-of "aws_security_group" "allow_ssh")
                                                                              (vpc-id-of "aws_security_group" "all-servers")
                                                                              (vpc-id-of "aws_security_group" "elb-kibana")
                                                                              ]
                                                     :user_data (rendered-template-file (cluster-unique "logstash-user-data"))
                                                     :associate_public_ip_address true
                                                     :subnet_id (vpc-id-of "aws_subnet" "public-a")
                                                     :iam_instance_profile (vpc-id-of "aws_iam_instance_profile" "logstash")})

              (vpc-security-group "elb-kibana" {}
                                  {:port 80
                                   :cidr_blocks [vpc-cidr-block]}
                                  {:port 443
                                   :cidr_blocks [vpc-cidr-block]})
              (vpc-security-group "allow-elb-kibana" {}
                                  {:port 80
                                   :source_security_group_id (vpc-id-of "aws_security_group" "elb-kibana")}
                                  {:port 443
                                   :source_security_group_id (vpc-id-of "aws_security_group" "elb-kibana")})
              (vpc-security-group  "kibana" {}
                                   {:type "egress"
                                    :from_port 0
                                    :to_port 0
                                    :protocol -1
                                    :cidr_blocks [all-external]})

              )))))
