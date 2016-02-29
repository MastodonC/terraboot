(ns terraboot.elasticsearch
  (:require [terraboot.core :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [cheshire.core :as json]))

(def ubuntu-user-data (cloud-config { :users [{:name "admin"
                                               :sudo "ALL=(ALL) NOPASSWD:ALL"
                                               :groups ["users" "admin"]
                                               :ssh-authorized-keys ["ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDiRTaPy06VZVuYQZs7XvG2ytbw3hg7F/uLC8hLoPD4ugbtVAWSrlO9koietHedLFkWI/UVwCP3FcMgZYqQnCQTwnEJ5bsp2r+MOI+nUfojZ6O8j7XMxwMtxf60S3FmVeuvN38Bbh2cygv72+uPbdE2giH+scD7lslm5LWsYAqK79ZVJ2Gk3do+x/eWc3mLqDnW/PNghgT2jJxg1T16kFPYiVFRUSYP1+CbmmQoJ38x8Xc7CZb2PfFcqHoHVzz9nBqRdhHl7GO2lSl8ostyy5nqhTWMkpOPxsJoGvJCS+ZUh/PPtUlxGikH8XcY+6h9QvThTR/17Irc9Aa7YJFPEk5l thattommyhall@gmail.com"]}
                                              ]}))

(defn elasticsearch-cluster [name {:keys [vpc_name] :as spec}]
  ;; http://docs.aws.amazon.com/elasticsearch-service/latest/developerguide/es-createupdatedomains.html#es-createdomain-configure-ebs
  ;; See for what instance-types and storage is possible
  (merge-in
   (resource "aws_elasticsearch_domain" name
             {:domain_name name
              :advanced_options { "rest.action.multi.allow_explicit_index" true}
              :access_policies (json/generate-string {"Version" "2012-10-17",
                                                      "Statement" [{"Action" "es:*",
                                                                    "Principal" "*",
                                                                    "Resource" "arn:aws:es:eu-central-1:165664414043:domain/elasticsearch/*",
                                                                    ;; There is currently a bug which means 'Resource' needs adding after there
                                                                    ;; cluster is created or it will constantly say it needs to change.
                                                                    "Effect" "Allow",
                                                                    "Condition"
                                                                    {
                                                                     "IpAddress"
                                                                     {"aws:SourceIp" [(output-of "aws_eip" "public-a-nat" "public_ip")
                                                                                      (output-of "aws_eip" "public-b-nat" "public_ip")
                                                                                      (output-of "aws_eip" "logstash" "public_ip")
                                                                                      "87.115.98.26/32"]
                                                                      }}}]})
              :cluster_config {:instance_count 2,
                               :instance_type "t2.small.elasticsearch"}
              :ebs_options {:ebs_enabled true,
                            :volume_type "gp2",
                            :volume_size 35
                            },
              :snapshot_options { :automated_snapshot_start_hour 23}})

   (in-vpc vpc_name
           (security-group "logstash" {}
                           {:port 12201
                            :protocol "udp"
                            :source_security_group_id (id-of "aws_security_group" "sends_gelf")}
                           {:port 12201
                            :protocol "udp"
                            :cidr_blocks ["52.29.162.148/32"
                                          "52.29.163.57/32"
                                          "52.29.97.114/32"
                                          "87.115.98.26/32"]})

           (resource "aws_eip" "logstash"
                     {:vpc true
                      :instance (id-of "aws_instance" "logstash")})

           (security-group "sends_gelf" {})
           (aws-instance "logstash" {:ami "ami-9b9c86f7"
                                     :vpc_security_group_ids [(id-of "aws_security_group" "logstash")
                                                              (id-of "aws_security_group" "allow_ssh")
                                                              ]
                                     :associate_public_ip_address true
                                     :subnet_id (id-of "aws_subnet" "public-a")
                                     })

           (aws-instance "kibana" {

                                   :ami "ami-9b9c86f7"
                                   :vpc_security_group_ids [(id-of "aws_security_group" "kibana")]
                                   :subnet_id (id-of "aws_subnet" "public-a")
                                   })

           (security-group "kibana" {}
                           )

           )))