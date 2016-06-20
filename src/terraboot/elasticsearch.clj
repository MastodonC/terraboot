(ns terraboot.elasticsearch
  (:require [terraboot.core :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [cheshire.core :as json]))

(def logstash-user-data (cloud-config {:users ["default"
                                               {:name "admin"
                                                :sudo "ALL=(ALL) NOPASSWD:ALL"
                                                :groups ["users" "admin"]
                                                }
                                               ]
                                       :package_update true
                                       :bootcmd ["echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | /usr/bin/debconf-set-selections"
                                                 "wget -qO - https://packages.elastic.co/GPG-KEY-elasticsearch | apt-key add -" ]
                                       :apt_sources [{:source "ppa:webupd8team/java"}
                                                     {:source "http://packages.elastic.co/logstash/2.2/debian"
                                                      :key (snippet "system-files/elasticsearch-apt.pem")                                                      }]
                                       :packages ["oracle-java8-installer"
                                                  "oracle-java8-set-default"
                                                  "logstash"]


                                       :runcmd ["update-rc.d logstash defaults"
                                                "/opt/logstash/bin/plugin install logstash-output-amazon_es"]
                                       :write_files [{:path "/etc/logstash/conf.d/out-es.conf"
                                                      :permissions "644"
                                                      :content (snippet "system-files/out-es.conf")}]}))

(defn elasticsearch-cluster [name {:keys [vpc-name account-number azs default-ami vpc-cidr-block] :as spec}]
  ;; http://docs.aws.amazon.com/elasticsearch-service/latest/developerguide/es-createupdatedomains.html#es-createdomain-configure-ebs
  ;; See for what instance-types and storage is possible
  (let [vpc-unique (fn [name] (str vpc-name "-" name))
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (fn [type name] (id-of type (vpc-unique name)))
        vpc-output-of (fn [type name & values] (apply (partial output-of type (vpc-unique name)) values))
        vpc-security-group (partial scoped-security-group vpc-unique)
        elb-listener (account-elb-listener account-number)]
    (merge-in
     (vpc-resource "aws_elasticsearch_domain" name
                   {:domain_name (vpc-unique name)
                    :advanced_options { "rest.action.multi.allow_explicit_index" true}
                    :access_policies (json/generate-string {"Version" "2012-10-17",
                                                            "Statement" [{"Action" "es:*",
                                                                          "Principal" "*",
                                                                          "Resource" (str "arn:aws:es:eu-central-1:" account-number ":domain/" vpc-name "-elasticsearch/*"),
                                                                          ;; There is currently a bug which means 'Resource' needs adding after the
                                                                          ;; cluster is created or it will constantly say it needs to change.
                                                                          ;; https://github.com/hashicorp/terraform/issues/5067
                                                                          "Effect" "Allow",
                                                                          "Condition"
                                                                          {
                                                                           "IpAddress"
                                                                           {"aws:SourceIp" (concat
                                                                                            (mapv #(vpc-output-of "aws_eip" (stringify "public-" % "-nat") "public_ip") azs)
                                                                                            [(vpc-output-of "aws_eip" "logstash" "public_ip")])
                                                                            }}}]})
                    :cluster_config {:instance_count 2,
                                     :instance_type "t2.small.elasticsearch"}
                    :ebs_options {:ebs_enabled true,
                                  :volume_type "gp2",
                                  :volume_size 35
                                  },
                    :snapshot_options { :automated_snapshot_start_hour 23}})

     (in-vpc (id-of "aws_vpc" vpc-name)
             (vpc-security-group "logstash" {}
                                 {:port 12201
                                  :protocol "udp"
                                  :source_security_group_id (vpc-id-of "aws_security_group" "sends_gelf")}
                                 {:port 12201
                                  :protocol "udp"
                                  :cidr_blocks (mapv #(str (vpc-output-of "aws_eip" (stringify "public-" % "-nat") "public_ip") "/32") azs)}
                                 {:port 9200
                                  :protocol "udp"
                                  :cidr_blocks [all-external]})

             (vpc-resource "aws_eip" "logstash"
                           {:vpc true
                            :instance (vpc-id-of "aws_instance" "logstash")})


             (route53_record "logstash" {:records [(vpc-output-of "aws_eip" "logstash" "public_ip")]})

             (vpc-security-group "sends_gelf" {})

             (vpc-security-group "sends_logstash" {})

             (vpc-resource "template_file" "logstash-user-data"
                           {:template logstash-user-data
                            :vars {:elasticsearch-lb (id-of "aws_elb" "kibana")}})

             (aws-instance (vpc-unique "logstash") {:ami default-ami
                                                    :vpc_security_group_ids [(vpc-id-of "aws_security_group" "logstash")
                                                                             (id-of "aws_security_group" "allow_ssh")
                                                                             (vpc-id-of "aws_security_group" "sends_influx")
                                                                             (vpc-id-of "aws_security_group" "all-servers")
                                                                             ]
                                                    :associate_public_ip_address true
                                                    :subnet_id (vpc-id-of "aws_subnet" "public-a")
                                                    })

             (aws-instance (vpc-unique "kibana") {
                                                  :ami default-ami
                                                  :vpc_security_group_ids [(vpc-id-of "aws_security_group" "kibana")
                                                                           (vpc-id-of "aws_security_group" "allow-elb-kibana")
                                                                           (vpc-id-of "aws_security_group" "sends_influx")
                                                                           (vpc-id-of "aws_security_group" "all-servers")]
                                                  :subnet_id (vpc-id-of "aws_subnet" "private-a")
                                                  })


             (elb "kibana" resource {:name "kibana"
                                     :health_check {:healthy_threshold 2
                                                    :unhealthy_threshold 3
                                                    :target "HTTP:80/status"
                                                    :timeout 5
                                                    :interval 30}
                                     :internal true
                                     :subnets (mapv #(id-of "aws_subnet" (stringify vpc-name "-public-" %)) azs)
                                     :listeners [(elb-listener {:lb-port 443 :lb-protocol "https" :port 80 :protocol "http" :cert-name "StartMastodoncNet"})]
                                     :instances [(id-of "aws_instance" (vpc-unique "kibana"))]
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
                                     :listeners [(elb-listener {:lb-port 443 :lb-protocol "https" :port 80 :protocol "http" :cert-name "StartMastodoncNet"})]
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

             (route53_record "alerts" {:type "CNAME"
                                       :records [(output-of "aws_elb" "alerts" "dns_name")]})


             (route53_record "kibana" {:type "CNAME"
                                       :records [(output-of "aws_elb" "kibana" "dns_name")]})

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

             ))))
