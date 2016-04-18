(ns terraboot.elasticsearch
  (:require [terraboot.core :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [cheshire.core :as json]))

(def logstash-user-data (cloud-config {:users ["default"
                                               {:name "admin"
                                                :sudo "ALL=(ALL) NOPASSWD:ALL"
                                                :groups ["users" "admin"]
                                                :ssh-authorized-keys ["ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDiRTaPy06VZVuYQZs7XvG2ytbw3hg7F/uLC8hLoPD4ugbtVAWSrlO9koietHedLFkWI/UVwCP3FcMgZYqQnCQTwnEJ5bsp2r+MOI+nUfojZ6O8j7XMxwMtxf60S3FmVeuvN38Bbh2cygv72+uPbdE2giH+scD7lslm5LWsYAqK79ZVJ2Gk3do+x/eWc3mLqDnW/PNghgT2jJxg1T16kFPYiVFRUSYP1+CbmmQoJ38x8Xc7CZb2PfFcqHoHVzz9nBqRdhHl7GO2lSl8ostyy5nqhTWMkpOPxsJoGvJCS+ZUh/PPtUlxGikH8XcY+6h9QvThTR/17Irc9Aa7YJFPEk5l thattommyhall@gmail.com"]}
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

(def ubuntu "ami-9b9c86f7")

(defn elasticsearch-cluster [name {:keys [vpc_name] :as spec}]
  ;; http://docs.aws.amazon.com/elasticsearch-service/latest/developerguide/es-createupdatedomains.html#es-createdomain-configure-ebs
  ;; See for what instance-types and storage is possible
  (let [vpc-unique (fn [name] (str vpc_name "-" name))
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (fn [type name] (id-of type (vpc-unique name)))
        vpc-output-of (fn [type name & values] (apply (partial output-of type (vpc-unique name)) values))
        vpc-security-group (partial scoped-security-group vpc-unique)
        azs [:a :b]]
    (merge-in
     (vpc-resource "aws_elasticsearch_domain" name
                   {:domain_name (vpc-unique name)
                    :advanced_options { "rest.action.multi.allow_explicit_index" true}
                    :access_policies (json/generate-string {"Version" "2012-10-17",
                                                            "Statement" [{"Action" "es:*",
                                                                          "Principal" "*",
                                                                          "Resource" "arn:aws:es:eu-central-1:165664414043:domain/sandpit-elasticsearch/*",
                                                                          ;; There is currently a bug which means 'Resource' needs adding after the
                                                                          ;; cluster is created or it will constantly say it needs to change.
                                                                          ;; https://github.com/hashicorp/terraform/issues/5067
                                                                          "Effect" "Allow",
                                                                          "Condition"
                                                                          {
                                                                           "IpAddress"
                                                                           {"aws:SourceIp" [(vpc-output-of "aws_eip" "public-a-nat" "public_ip")
                                                                                            (vpc-output-of "aws_eip" "public-b-nat" "public_ip")
                                                                                            (vpc-output-of "aws_eip" "logstash" "public_ip")
                                                                                            "87.115.98.26/32" ; Tom
                                                                                            "146.200.166.70/32"  ; Elise
                                                                                            ]
                                                                            }}}]})
                    :cluster_config {:instance_count 2,
                                     :instance_type "t2.small.elasticsearch"}
                    :ebs_options {:ebs_enabled true,
                                  :volume_type "gp2",
                                  :volume_size 35
                                  },
                    :snapshot_options { :automated_snapshot_start_hour 23}})

     (in-vpc vpc_name
             (vpc-security-group "logstash" {}
                                 {:port 12201
                                  :protocol "udp"
                                  :source_security_group_id (vpc-id-of "aws_security_group" "sends_logstash")}
                                 {:port 12201
                                  :protocol "udp"
                                  :cidr_blocks [(str (vpc-output-of "aws_eip" "public-a-nat" "public_ip") "/32")
                                                (str (vpc-output-of "aws_eip" "public-b-nat" "public_ip") "/32")
                                                "52.29.162.148/32"
                                                "52.29.163.57/32"
                                                "52.29.97.114/32"
                                                "87.115.98.26/32"
                                                "151.230.75.151/32" ; Tom Temp
                                                ]}
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

             (aws-instance (vpc-unique "logstash") {:ami ubuntu
                                                    :vpc_security_group_ids [(vpc-id-of "aws_security_group" "logstash")
                                                                             (id-of "aws_security_group" "allow_ssh")
                                                                             (vpc-id-of "aws_security_group" "sends_influx")
                                                                             ]
                                                    :associate_public_ip_address true
                                                    :subnet_id (vpc-id-of "aws_subnet" "public-a")
                                                    })

             (aws-instance (vpc-unique "kibana") {
                                                  :ami ubuntu
                                                  :vpc_security_group_ids [(vpc-id-of "aws_security_group" "kibana")
                                                                           (vpc-id-of "aws_security_group" "allow-elb-kibana")
                                                                           (vpc-id-of "aws_security_group" "sends_influx")]
                                                  :subnet_id (vpc-id-of "aws_subnet" "private-a")
                                                  })


             (elb "kibana" resource {:name "kibana"
                                     :health_check {:healthy_threshold 2
                                                    :unhealthy_threshold 3
                                                    :target "HTTP:80/status"
                                                    :timeout 5
                                                    :interval 30}
                                     :cert_name "StartMastodoncNet"
                                     :subnets (mapv #(id-of "aws_subnet" (stringify  vpc_name "-public-" %)) azs)
                                     :instances [(id-of "aws_instance" (vpc-unique "kibana"))]
                                     :sgs ["allow_outbound"
                                           "allow_external_http_https"
                                           (vpc-unique "elb-kibana")
                                           ]})

             ;; alerting server needs access to all servers
             (vpc-security-group "nrpe" {})
             (vpc-security-group "all-servers" {}
                                 {:port 5666
                                  :source_security_group_id (vpc-id-of "aws_security_group" "nrpe")})

             (database {:name (vpc-unique "alerts")
                        :subnet vpc_name})

             (aws-instance (vpc-unique "alerts")
                           {:ami ubuntu
                            :subnet_id (vpc-id-of "aws_subnet" "private-a")
                            :vpc_security_group_ids [(vpc-id-of "aws_security_group" "nrpe")
                                                     (id-of "aws_security_group" (str "uses-db-" (vpc-unique "alerts")))
                                                     (vpc-id-of "aws_security_group" "allow-elb-alerts")]})

             (elb "alerts" resource {:name "alerts"
                                     :health_check {:healthy_threshold 2
                                                    :unhealthy_threshold 3
                                                    :target "HTTP:80/"
                                                    :timeout 5
                                                    :interval 30}
                                     :cert_name "StartMastodoncNet"
                                     :subnets (mapv #(id-of "aws_subnet" (stringify  vpc_name "-public-" %)) azs)
                                     :instances [(id-of "aws_instance" (vpc-unique "alerts"))]
                                     :sgs ["allow_outbound"
                                           "allow_external_http_https"
                                           (vpc-unique "elb-alerts")
                                           ]})

             (vpc-security-group "elb-alerts" {})
             (vpc-security-group "allow-elb-alerts" {}
                                 {:port 80
                                  :source_security_group_id (vpc-id-of "aws_security_group" "elb-alerts")})

             (route53_record "kibana" {:type "CNAME"
                                       :records [(output-of "aws_elb" "kibana" "dns_name")]})

             (vpc-security-group "elb-kibana" {})
             (vpc-security-group "allow-elb-kibana" {}
                                 {:port 80
                                  :source_security_group_id (vpc-id-of "aws_security_group" "elb-kibana")})
             (vpc-security-group  "kibana" {})

             ))))
