(ns terraboot.elasticsearch
  (:require [terraboot.core :refer :all]
            [terraboot.utils :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.core.strint :refer [<<]]))

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
ExecStart=/usr/bin/docker run --name ~{image-name} ~{option-string} ~{full-image-name}:~{release} ~{entry-point}
ExecStop=/usr/bin/docker stop ~{image-name}

[Install]
WantedBy=multi-user.target")})))

(defn cloud-config-coreos [units]
  (cloud-config {:coreos {:update
                          {:reboot-strategy "off"}
                          :units (concat common-coreos-units
                                         units)
                          }}))

(defn logstash-user-data-coreos [es-host]
  (let [logstash (docker-systemd-unit "mastodonc" "logstash-ng"
                                      {:options [(str "--env " "ES_HOST=" es-host)
                                                 "--net=host"]
                                       :entry-point "-f /etc/logstash/logstash.conf"}
                                      )
        nginx (docker-systemd-unit "mastodonc" "kibana-nginx"
                                      {:options [(str "--env " "ES_HOST=" es-host)
                                                 "--net=host"]
                                       }
                                      )]
    (cloud-config-coreos [logstash
                          nginx])))

(defn elasticsearch-policy
  []
  (json/generate-string {"Version" "2012-10-17",
                         "Statement" [{"Action" "es:*",
                                       "Principal" "*",
                                       "Resource" "$${es-arn}",
                                       ;; There is currently a bug which means 'Resource' needs adding after the
                                       ;; cluster is created or it will constantly say it needs to change.
                                       ;; https://github.com/hashicorp/terraform/issues/5067
                                       "Effect" "Allow",
                                       "Condition"
                                       {
                                        "IpAddress"
                                        {"aws:SourceIp" ["$${allowed-ips}"]}}}]}))

(defn elasticsearch-cluster [name {:keys [vpc-name account-number region azs default-ami vpc-cidr-block cert-name key-name] :as spec}]
  ;; http://docs.aws.amazon.com/elasticsearch-service/latest/developerguide/es-createupdatedomains.html#es-createdomain-configure-ebs
  ;; See for what instance-types and storage is possible
  (let [vpc-unique (vpc-unique-fn vpc-name)
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (id-of-fn vpc-unique)
        vpc-output-of (output-of-fn vpc-unique)
        vpc-security-group (partial scoped-security-group vpc-unique)
        elb-listener (account-elb-listener account-number)
        es-arn (str "arn:aws:es:" region ":" account-number ":domain/" vpc-name "-elasticsearch-5")
        es-arn-* (str es-arn "/*")]

    (merge-in
     (template-file (vpc-unique "elasticsearch-policy")
                    (elasticsearch-policy)
                    {:es-arn es-arn-*
                     :allowed-ips  (vpc-output-of "aws_eip" "logstash" "public_ip")})

     (vpc-resource "aws_elasticsearch_domain" (str (vpc-unique name) "-5")
                   {:domain_name (str (vpc-unique name) "-5")
                    :elasticsearch_version "5.1"
                    :advanced_options { "rest.action.multi.allow_explicit_index" "true"}
                    :access_policies (rendered-template-file (vpc-unique "elasticsearch-policy"))
                    :cluster_config {:instance_count 2,
                                     :instance_type "t2.small.elasticsearch"}
                    :ebs_options {:ebs_enabled true,
                                  :volume_type "gp2",
                                  :volume_size 35
                                  },
                    :snapshot_options { :automated_snapshot_start_hour 23}})

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
                                                          :roles  [(vpc-output-of "aws_iam_role" "logstash" "name")]
                                                          })

     (add-key-name-to-instances
      key-name
      (in-vpc (id-of "aws_vpc" vpc-name)
              (vpc-security-group "logstash" {}
                                  {:port 12201
                                   :protocol "udp"
                                   :source_security_group_id (vpc-id-of "aws_security_group" "sends_gelf")}
                                  {:port 12201
                                   :protocol "udp"
                                   :cidr_blocks (mapv #(str (vpc-output-of "aws_eip" (stringify "public-" % "-nat") "public_ip") "/32") azs)}
                                  {:port 12201
                                   :protocol "udp"
                                   :cidr_blocks [vpc-cidr-block]}
                                  {:port 9200
                                   :protocol "tcp"
                                   :cidr_blocks [vpc-cidr-block]})

              (vpc-resource "aws_eip" "logstash"
                            {:vpc true
                             :instance (vpc-id-of "aws_instance" "logstash")})

              (vpc-security-group "sends_gelf" {})

              (vpc-security-group "sends_logstash" {})

              (aws-instance (vpc-unique "logstash") {:ami "ami-2cb14043"
                                                     :instance_type "m4.large"
                                                     :vpc_security_group_ids [(vpc-id-of "aws_security_group" "logstash")
                                                                              (id-of "aws_security_group" "allow_ssh")
                                                                              (vpc-id-of "aws_security_group" "sends_influx")
                                                                              (vpc-id-of "aws_security_group" "all-servers")
                                                                              (vpc-id-of "aws_security_group" "elb-kibana")
                                                                              ]
                                                     :user_data (logstash-user-data-coreos "es.sandpit-vpc.kixi")
                                                     :associate_public_ip_address true
                                                     :subnet_id (vpc-id-of "aws_subnet" "public-a")
                                                     :iam_instance_profile (vpc-id-of "aws_iam_instance_profile" "logstash")})


              (elb "kibana" resource {:name "kibana"
                                      :health_check {:healthy_threshold 2
                                                     :unhealthy_threshold 3
                                                     :target "HTTP:80/status"
                                                     :timeout 5
                                                     :interval 30}
                                      :internal true
                                      :subnets (mapv #(id-of "aws_subnet" (stringify vpc-name "-public-" %)) azs)
                                      :listeners [(elb-listener (if cert-name
                                                                  {:lb-port 443 :lb-protocol "https" :port 80 :protocol "http" :cert-name "StartMastodoncNet"}
                                                                  {:port 80 :protocol "http"}))]
                                      :instances [(id-of "aws_instance" (vpc-unique "logstash"))]
                                      :security-groups (map #(id-of "aws_security_group" %)
                                                            ["allow_outbound"
                                                             "allow_external_http_https"
                                                             (vpc-unique "elb-kibana")
                                                             ])})

              ;; alerting server needs access to all servers
              (vpc-security-group "nrpe" {})

              (database {:name (vpc-unique "alerts")
                         :subnet vpc-name})

              (aws-instance (vpc-unique "alerts")
                            {:ami default-ami
                             :subnet_id (vpc-id-of "aws_subnet" "private-a")
                             :vpc_security_group_ids [(vpc-id-of "aws_security_group" "nrpe")
                                                      (id-of "aws_security_group" (str "uses-db-" (vpc-unique "alerts")))
                                                      (vpc-id-of "aws_security_group" "allow-elb-alerts")
                                                      (vpc-id-of "aws_security_group" "all-servers")
                                                      (vpc-id-of "aws_security_group" "sends_influx")]})

              (elb "alerts" resource {:name "alerts"
                                      :health_check {:healthy_threshold 2
                                                     :unhealthy_threshold 3
                                                     :target "HTTP:80/"
                                                     :timeout 5
                                                     :interval 30}
                                      :listeners [(elb-listener (if cert-name
                                                                  {:lb-port 443 :lb-protocol "https" :port 80 :protocol "http" :cert-name cert-name}
                                                                  {:port 80 :protocol "http"}))]
                                      :subnets (mapv #(id-of "aws_subnet" (stringify  vpc-name "-public-" %)) azs)
                                      :instances [(id-of "aws_instance" (vpc-unique "alerts"))]
                                      :security-groups (map #(id-of "aws_security_group" %)
                                                            ["allow_outbound"
                                                             "allow_external_http_https"
                                                             (vpc-unique "elb-alerts")
                                                             ])})

              (vpc-security-group "elb-alerts" {})
              (vpc-security-group "allow-elb-alerts" {}
                                  {:port 80
                                   :source_security_group_id (vpc-id-of "aws_security_group" "elb-alerts")})

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
