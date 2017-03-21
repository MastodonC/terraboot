(ns terraboot.vpc
  (:require [terraboot.core :refer :all]
            [terraboot.utils :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [terraboot.elasticsearch :refer [elasticsearch-cluster]]
            [clojure.string :as string]))

(defn cidr-start
  [cidr-block]
  (first (string/split cidr-block #"/")))

(defn parse-ip
  [ipv4]
  (mapv #(Integer/parseInt %) (string/split ipv4 #"\.")))

(defn reconstitute-ip
  [ip-map]
  (string/join "." ip-map))

(defn fallback-dns
  "this assumes dns is always 2 up from start of range"
  [cidr-block]
  (let [parsed-ip (parse-ip (cidr-start cidr-block))
        second (+ 2 (last parsed-ip))]
    (reconstitute-ip (conj (vec (drop-last parsed-ip)) second))))

(def subnet-types [:public :private])

(defn vpn-user-data [vars]
  (cloud-config {:package_update true
                 :packages ["openvpn"]
                 :users ["default"]
                 :output {:all "| tee -a /var/log/cloud-init-output.log"}
                 :runcmd ["sysctl -p"
                          "sysctl -w net.ipv4.ip_forward=1"
                          "iptables -t nat -A POSTROUTING -s 10.20.0.0/24 -o eth0 -j MASQUERADE"
                          "service openvpn restart"]
                 :write_files [{:path "/etc/openvpn/ta.key"
                                :content (snippet "vpn-keys/ta.key")
                                :permissions "600"}
                               {:path "/etc/openvpn/ca.crt"
                                :content (snippet "vpn-keys/ca.crt")
                                :permissions "644"}
                               {:path "/etc/openvpn/mesos-vpn-gw.key"
                                :content (snippet "vpn-keys/mesos-vpn-gw.key")
                                :permissions "600"}
                               {:path "/etc/openvpn/mesos-vpn-gw.crt"
                                :content (snippet "vpn-keys/mesos-vpn-gw.crt")
                                :permissions "644"}
                               {:path "/etc/openvpn/dh2048.pem"
                                :content (snippet "vpn-keys/dh2048.pem")
                                :permissions "600"}
                               {:path "/etc/openvpn/server.conf"
                                :content (from-template "system-files/server.conf" vars)
                                :permissions "644"}
                               {:path "/etc/openvpn/crl.pem"
                                :content (snippet "vpn-keys/crl.pem")
                                :permissions "644"}
                               {:path "/etc/sysctl.d/99-ip-forwarding.conf"
                                :content "net.ipv4.ip_forward = 1\n"
                                :permissions "644"}
                               {:path "/etc/openvpn/up.sh"
                                :content (snippet "system-files/up.sh")
                                :permissions "744"}]
                 }))

(defn vpc-dns-zone [name]
  (str name "-vpc.kixi" ))

(defn vpc-dns-zone-id [name]
  (str name "-mesos"))

(defn private_route53_record [prefix vpc-name spec]
  (let [dns-zone (vpc-dns-zone vpc-name)
        name (str prefix "." dns-zone)]
    (resource "aws_route53_record" (safe-name name)
              (merge {:zone_id (id-of "aws_route53_zone" (vpc-dns-zone-id vpc-name))
                      :name prefix
                      :type "A" }
                     (if (:alias spec) {} {:ttl "300"})
                     spec))))

(defn vpc-vpn-infra
  [{:keys [es-endpoint
           vpc-name
           account-number
           region
           key-name
           azs
           subnet-cidr-blocks
           default-ami
           mesos-ami
           vpc-cidr-block
           cert-name]}]
  (let [vpc-unique (vpc-unique-fn vpc-name)
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (id-of-fn vpc-unique)
        vpc-output-of (output-of-fn vpc-unique)
        vpc-security-group (partial scoped-security-group vpc-unique)
        elb-listener (account-elb-listener account-number)]
    (merge-in
     (resource "aws_vpc" vpc-name
               {:tags {:Name vpc-name}
                :cidr_block vpc-cidr-block
                :enable_dns_hostnames true})

     (elasticsearch-cluster (vpc-unique "monitoring") {:es-endpoint es-endpoint ; Horrible, just to break a cycle in Terraform
                                                       :vpc-name vpc-name
                                                       :account-number account-number
                                                       :key-name key-name
                                                       :region region
                                                       :azs azs
                                                       :default-ami default-ami
                                                       :mesos-ami mesos-ami
                                                       :vpc-cidr-block vpc-cidr-block
                                                       :cert-name cert-name})

     (add-key-name-to-instances
      key-name
      (in-vpc (id-of "aws_vpc" vpc-name)
              (aws-instance (vpc-unique "vpn") {
                                                :user_data (vpn-user-data {:range-start (cidr-start vpc-cidr-block)
                                                                           :fallback-dns (fallback-dns vpc-cidr-block)})
                                                :subnet_id (vpc-id-of "aws_subnet" (stringify "public-" (first azs)))
                                                :ami default-ami
                                                :vpc_security_group_ids [(vpc-id-of "aws_security_group" "vpn")
                                                                         (vpc-id-of "aws_security_group" "sends_influx")
                                                                         (vpc-id-of "aws_security_group" "all-servers")
                                                                         ]
                                                :associate_public_ip_address true
                                                })

              (aws-instance "influxdb" {:ami default-ami
                                        :instance_type "m4.large"
                                        :vpc_security_group_ids [(vpc-id-of "aws_security_group" "influxdb")
                                                                 (id-of "aws_security_group" "allow_ssh")
                                                                 (vpc-id-of "aws_security_group" "allow_elb_grafana")
                                                                 (vpc-id-of "aws_security_group" "all-servers")
                                                                 ]
                                        :subnet_id (vpc-id-of "aws_subnet" (stringify "public-" (first azs)))
                                        :root_block_device {:volume_size 100}})

              (elb "grafana" resource {:name "grafana"
                                       :health_check {:healthy_threshold 2
                                                      :unhealthy_threshold 3
                                                      :target "HTTP:80/status"
                                                      :timeout 5
                                                      :interval 30}
                                       :internal true
                                       :listener [(elb-listener (if cert-name
                                                                   {:lb-port 443 :lb-protocol "https" :port 80 :protocol "http" :cert-name cert-name}
                                                                   {:port 80 :protocol "http"}))]
                                       :instances [(id-of "aws_instance" "influxdb")]
                                       :subnets (mapv #(id-of "aws_subnet" (stringify  vpc-name "-public-" %)) azs)
                                       :security_groups (mapv #(id-of "aws_security_group" %) ["allow_outbound"
                                                                                               "allow_external_http_https"
                                                                                               (vpc-unique "elb_grafana")])
                                       })
              (private_route53_record "grafana" vpc-name {:zone_id  (vpc-id-of "aws_route53_zone" "mesos")
                                                          :name "grafana"
                                                          :alias {:name (output-of "aws_elb" "grafana" "dns_name")
                                                                  :zone_id (output-of "aws_elb" "grafana" "zone_id")
                                                                  :evaluate_target_health true}})
              (private_route53_record "influxdb" vpc-name {:records [(output-of "aws_instance" "influxdb" "private_ip")]})

              (private_route53_record "kibana" vpc-name {:records [(vpc-output-of "aws_instance" "logstash" "private_ip")]})
              (private_route53_record "logstash" vpc-name {:records [(vpc-output-of "aws_instance" "logstash" "private_ip")]})

              (private_route53_record "alerts" vpc-name {:records [(vpc-output-of "aws_instance" "alerts" "private_ip")]})

              (vpc-security-group "elb_grafana" {})
              (vpc-security-group "allow_elb_grafana" {}
                                  {:port 80
                                   :source_security_group_id (vpc-id-of "aws_security_group" "elb_grafana")})

              (vpc-security-group "influxdb" {}
                                  {:port 222
                                   :protocol "tcp"
                                   :cidr_blocks [all-external]}
                                  {:port 8086
                                   :protocol "tcp"
                                   :source_security_group_id (vpc-id-of "aws_security_group" "sends_influx")})

              (vpc-security-group "sends_influx" {})

              (resource "aws_eip" "influxdb"
                        {:vpc true
                         :instance (id-of "aws_instance" "influxdb")})

              (vpc-resource "aws_eip" "vpn" {:instance (vpc-id-of "aws_instance" "vpn")
                                             :vpc true})

              (vpc-resource "aws_route53_zone" "mesos"
                            {:name "vpc.kixi"
                             :comment "private routes within vpc"
                             :vpc_id (id-of "aws_vpc" vpc-name)})

              (security-group "allow_outbound" {}
                              {:type "egress"
                               :from_port 0
                               :to_port 0
                               :protocol -1
                               :cidr_blocks [all-external]
                               })

              (vpc-security-group "all-servers" {}
                                  {:port 5666
                                   :source_security_group_id (vpc-id-of "aws_security_group" "nrpe")}
                                  {:type "egress"
                                   :from_port 0
                                   :to_port 0
                                   :protocol -1
                                   :cidr_blocks [all-external]})

              (security-group "allow_ssh" {}
                              {:port 22
                               :cidr_blocks [vpc-cidr-block]
                               })

              (security-group "allow_external_http_https" {}
                              {:from_port 80
                               :to_port 80
                               :cidr_blocks [all-external]
                               }
                              {:from_port 443
                               :to_port 443
                               :cidr_blocks [all-external]})

              (vpc-security-group "vpn" {}
                                  {:from_port 22
                                   :to_port 22
                                   :cidr_blocks [all-external]}
                                  {:from_port 1194
                                   :to_port 1194
                                   :protocol "udp"
                                   :cidr_blocks [all-external]})


              (resource "aws_internet_gateway" vpc-name
                        {:tags {:Name "main"}})

              (vpc-resource "aws_route_table" "public" {:tags { :Name "public"}
                                                        :route { :cidr_block all-external
                                                                :gateway_id (id-of "aws_internet_gateway" vpc-name)}
                                                        :vpc_id (id-of "aws_vpc" vpc-name)})


              (resource "aws_db_subnet_group" vpc-name
                        {:name vpc-name
                         :subnet_ids (map #(id-of "aws_subnet" (stringify vpc-name "-private-" %)) azs)
                         :description "subnet for dbs"})


              ;; all the subnets
              (apply merge-in (mapv #(private-public-subnets {:naming-fn vpc-unique
                                                              :az %
                                                              :cidr-blocks (% subnet-cidr-blocks)
                                                              :public-route-table (vpc-id-of "aws_route_table" "public")
                                                              :region region}) azs))
              (apply merge-in (for [az azs
                                    name [:public :private]]
                                (output (stringify "subnet-" name "-" az "-id") "aws_subnet" (vpc-unique (stringify name "-" az)) "id")))
              (output "sg-all-servers" "aws_security_group" (vpc-unique "all-servers") "id")
              (output "sg-allow-ssh" "aws_security_group" "allow_ssh" "id")
              (output "sg-allow-outbound" "aws_security_group" "allow_outbound" "id")
              (output "sg-allow-http-https" "aws_security_group" "allow_external_http_https" "id")
              (output "vpc-id" "aws_vpc" vpc-name  "id")
              (output "sg-sends-influx" "aws_security_group" (vpc-unique "sends_influx") "id")
              (output "sg-sends-gelf" "aws_security_group" (vpc-unique "sends_gelf") "id")
              (output "private-dns-zone" "aws_route53_zone" (vpc-unique "mesos") "id")
              (output "public-route-table" "aws_route_table" (vpc-unique "public") "id")
              (output "logstash-ip" "aws_eip" (vpc-unique "logstash") "private_ip")
              )))))
