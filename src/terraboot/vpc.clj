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

;;(defn vpc-dns-zone [name]
;;  (str name environment-dns)

(defn vpc-dns-zone-id [name]
  (str name "-mesos"))

(defn private-dns-zone
  [environment-dns environment-dns-identifier vpc-name]
  (resource "aws_route53_zone" environment-dns-identifier
            {:name environment-dns
             :comment (str "private routes for " environment-dns)
             :vpc_id (id-of "aws_vpc" vpc-name)}))

(defn private-route53-record [prefix environment-dns environment-dns-identifier spec]
  (let [name (str prefix "." environment-dns)]
    (resource "aws_route53_record" (safe-name name)
              (merge {:zone_id (id-of "aws_route53_zone" environment-dns-identifier)
                      :name prefix
                      :type "A" }
                     (if (:alias spec) {} {:ttl "300"})
                     spec))))

(defn vpc-vpn-infra
  [{:keys [vpc-name
           account-number
           region
           key-name
           azs
           subnet-cidr-blocks
           default-ami
           vpc-cidr-block
           cert-name
           root-dns
           environment
           project] :as opts}]
  (let [vpc-unique (vpc-unique-fn vpc-name)
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (id-of-fn vpc-unique)
        vpc-output-of (output-of-fn vpc-unique)
        vpc-security-group (partial scoped-security-group vpc-unique)
        elb-listener (account-elb-listener account-number)
        environment-dns (environment-dns environment project root-dns)
        environment-dns-identifier (environment-dns-identifier environment-dns "private")]
    (merge-in
     (resource "aws_vpc" vpc-name
               {:tags {:Name vpc-name}
                :cidr_block vpc-cidr-block
                :enable_dns_hostnames true})

     (elasticsearch-cluster
      (vpc-unique "monitoring")
      (select-keys opts [:vpc-name :account-number :key-name
                         :region :azs :default-ami :logstash-ami :vpc-cidr-block
                         :cert-name :es-allowed-ips
                         :es-instance-type :es-instance-count :es-ebs-volume-size]))

     (add-key-name-to-instances
      key-name
      (in-vpc (id-of "aws_vpc" vpc-name)
              (aws-instance (vpc-unique "vpn") {
                                                :user_data (vpn-user-data {:range-start (cidr-start vpc-cidr-block)
                                                                           :fallback-dns (fallback-dns vpc-cidr-block)
                                                                           :region region})
                                                :subnet_id (vpc-id-of "aws_subnet" (stringify "public-" (first azs)))
                                                :ami default-ami
                                                :vpc_security_group_ids [(vpc-id-of "aws_security_group" "vpn")
                                                                         (vpc-id-of "aws_security_group" "all-servers")
                                                                         ]
                                                :associate_public_ip_address true
                                                })


              (private-route53-record "kibana"
                                      environment-dns
                                      environment-dns-identifier
                                      {:records [(vpc-output-of "aws_instance" "logstash" "private_ip")]})
              (private-route53-record "logstash"
                                      environment-dns
                                      environment-dns-identifier
                                      {:records [(vpc-output-of "aws_instance" "logstash" "private_ip")]})

              (vpc-security-group "elb_grafana" {})
              (vpc-security-group "allow_elb_grafana" {}
                                  {:port 80
                                   :source_security_group_id (vpc-id-of "aws_security_group" "elb_grafana")})

              (vpc-resource "aws_eip" "vpn" {:instance (vpc-id-of "aws_instance" "vpn")
                                             :vpc true})

              (private-dns-zone environment-dns environment-dns-identifier vpc-name)

              (security-group "allow_outbound" {}
                              {:type "egress"
                               :from_port 0
                               :to_port 0
                               :protocol -1
                               :cidr_blocks [all-external]
                               })

              (vpc-security-group "all-servers" {}
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
              (output "private-dns-zone" "aws_route53_zone" environment-dns-identifier "id")
              (output "public-route-table" "aws_route_table" (vpc-unique "public") "id")
              (output "logstash-ip" "aws_eip" (vpc-unique "logstash") "private_ip")
              (output "es-endpoint" "aws_elasticsearch_domain" (vpc-unique "monitoring") "endpoint")
              )))))
