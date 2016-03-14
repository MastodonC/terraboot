(ns terraboot.vpc
  (:require [terraboot.core :refer :all]
            [terraboot.cloud-config :refer [cloud-config]]
            [terraboot.elasticsearch :refer [elasticsearch-cluster]]
            [clojure.string :as string]))

(def vpc-name "sandpit")

(def vpc-cidr-block "172.20.0.0/20")

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

(def cidr-block {:public {:a "172.20.0.0/24"
                          :b "172.20.1.0/24"
                          :c "172.20.2.0/24"}
                 :private {:a "172.20.8.0/24"
                           :b "172.20.9.0/24"
                           :c "172.20.10.0/24"}
                 })

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
                                :content (from-template "server.conf" vars)
                                :permissions "644"}
                               {:path "/etc/openvpn/crl.pem"
                                :content (snippet "vpn-keys/crl.pem")
                                :permissions "644"}
                               {:path "/etc/sysctl.d/99-ip-forwarding.conf"
                                :content "net.ipv4.ip_forward = 1\n"
                                :permissions "644"}]
                 }))

(def vpc-vpn-infra
  (let [vpc-unique (fn [name] (str vpc-name "-" name))
        vpc-resource (partial resource vpc-unique)
        vpc-id-of (fn [type name] (id-of type (vpc-unique name)))
        vpc-output-of (fn [type name & values] (apply (partial output-of type (vpc-unique name)) values))
        vpc-security-group (partial scoped-security-group vpc-unique)]
    (merge-in
     (resource "aws_vpc" vpc-name
               {:tags {:Name vpc-name}
                :cidr_block vpc-cidr-block})

     (elasticsearch-cluster "elasticsearch" {:vpc_name vpc-name})

     (in-vpc vpc-name
             (aws-instance (vpc-unique "vpn") {
                                               :user_data (vpn-user-data {:range-start (cidr-start vpc-cidr-block)
                                                                          :fallback-dns (fallback-dns vpc-cidr-block)})
                                               :subnet_id (vpc-id-of "aws_subnet" "public-b")
                                               :ami "ami-bc5b48d0"
                                               :vpc_security_group_ids [(vpc-id-of "aws_security_group" "vpn")
                                                                        (id-of "aws_security_group" "allow_outbound")
                                                                        ]
                                               :associate_public_ip_address true
                                               })

             (vpc-resource "aws_eip" "vpn" {:instance (vpc-id-of "aws_instance" "vpn")
                                            :vpc true})

             (security-group "allow_outbound" {}
                             {:type "egress"
                              :from_port 0
                              :to_port 0
                              :protocol -1
                              :cidr_blocks [all-external]
                              })

             (security-group "allow_ssh" {}
                             {:port 22
                              :cidr_blocks [all-external]
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

             (vpc-security-group "allow-all-tcp-within-public-subnet" {}
                                 {:from_port 0
                                  :to_port 65535
                                  :cidr_blocks (vals (:public cidr-block))})

             (vpc-security-group "allow-all-udp-within-public-subnet" {}
                                 {:from_port 0
                                  :to_port 65535
                                  :protocol "udp"
                                  :cidr_blocks (vals (:public cidr-block))})

             (vpc-security-group "allow-icmp-within-public-subnet" {}
                                 {:from_port 0
                                  :to_port 65535
                                  :protocol "udp"
                                  :cidr_blocks (vals (:public cidr-block))})

             (resource "aws_internet_gateway" vpc-name
                       {:tags {:Name "main"}})

             (vpc-resource "aws_route_table" "public" {:tags { :Name "public"}
                                                       :route { :cidr_block all-external
                                                               :gateway_id (id-of "aws_internet_gateway" vpc-name)}
                                                       :vpc_id (id-of "aws_vpc" vpc-name)})

             ;; Public Subnets
             (resource-seq
              (apply concat
                     (for [az azs]
                       (let [subnet-name (stringify vpc-name "-public-" az)
                             nat-eip (stringify subnet-name "-nat")]
                         [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                     :cidr_block (get-in cidr-block [:public az])
                                                     :availability_zone (stringify region az)
                                                     }]
                          ["aws_route_table_association" subnet-name {:route_table_id (vpc-id-of "aws_route_table" "public")
                                                                      :subnet_id (id-of "aws_subnet" subnet-name)
                                                                      }]
                          ["aws_nat_gateway" subnet-name {:allocation_id (id-of "aws_eip" nat-eip)
                                                          :subnet_id  (id-of "aws_subnet" subnet-name)}]

                          ["aws_eip" nat-eip {:vpc true}]])
                       )))
             ;; Private Subnets

             (resource-seq
              (apply concat
                     (for [az azs]
                       (let [subnet-name (stringify vpc-name "-private-" az)
                             public-subnet-name (stringify vpc-name "-public-" az)]
                         [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                     :cidr_block (get-in cidr-block [:private az])
                                                     :availability_zone (stringify region az)
                                                     }]
                          ["aws_route_table" subnet-name {:tags {:Name subnet-name}
                                                          :route {:cidr_block all-external
                                                                  :nat_gateway_id (id-of "aws_nat_gateway" public-subnet-name)}}

                           ]
                          ["aws_route_table_association" subnet-name {:route_table_id (id-of "aws_route_table" subnet-name)
                                                                      :subnet_id (id-of "aws_subnet" subnet-name)
                                                                      }]]))))))))
