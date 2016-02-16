(ns terraboot.vpc
  (:require [terraboot.core :refer :all]))

(def vpc-name "sandpit")

(def subnet-types [:public :private])

(def cidr-block {:public {:a "172.20.0.0/24"
                          :b "172.20.1.0/24"
                          :c "172.20.2.0/24"}
                 :private {:a "172.20.8.0/24"
                           :b "172.20.9.0/24"
                           :c "172.20.10.0/24"}
                 })

(defn add-to-every-value-map
  [map key value]
  (reduce-kv (fn [m k v]
               (assoc m k (assoc v key value))) {} map))

(defn in-vpc
  [vpc-name & resources]
  (let [vpc-id (id-of "aws_vpc" vpc-name)]
    (apply merge-in
           (map
            (apply comp
                       (map #(partial (fn [type resource] (update-in resource [:resource type] (fn [spec] (add-to-every-value-map spec :vpc_id vpc-id)))) %)
                           ["aws_security_group"
                            "aws_internet_gateway"
                            "aws_subnet"
                            "aws_route_table"]))
                resources))))

(def vpc-vpn-infra
  (merge-in
            (resource "aws_vpc" vpc-name
                      {:tags {:Name vpc-name}
                       :cidr_block "172.20.0.0/20"})


            (in-vpc vpc-name
                    (aws-instance "vpn" {
                                         :user_data (from-template "vpn-config" {:range-start "172.20.0.0"
                                                                                 :fallback-dns "172.20.0.2"})
                                         :subnet_id (id-of "aws_subnet" "public-b")
                                         :ami "ami-bc5b48d0"
                                         :vpc_security_group_ids [(id-of "aws_security_group" "vpn")
                                                                  ]
                                         :associate_public_ip_address true
                                         })
                    (security-group "allow_outbound" {}
                                    {:type "egress"
                                     :from_port 0
                                     :to_port 0
                                     :protocol -1
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

                    (security-group "vpn" {}
                                    {:from_port 22
                                     :to_port 22
                                     :cidr_blocks [all-external]}
                                    {:from_port 1194
                                     :to_port 1194
                                     :protocol "udp"
                                     :cidr_blocks [all-external]})

                    (resource "aws_internet_gateway" vpc-name
                              {})
                    (resource "aws_route_table" "public" {:tags { :Name "public"}
                                                          :vpc_id (id-of "aws_vpc" vpc-name)})
                    ;; Public Subnets

                    (resource-seq
                     (apply concat
                            (for [az azs]
                              (let [subnet-name (stringify "public" "-" az)
                                    nat-eip (stringify subnet-name "-nat")]
                                [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                            :cidr_block (get-in cidr-block [:public az])
                                                            :availability_zone (stringify region az)
                                                            }]
                                 ["aws_route_table_association" subnet-name {:route_table_id (id-of "aws_route_table" "public")
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
                              (let [subnet-name (stringify "private-" az)
                                    public-subnet-name (stringify "public-" az)]
                                [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                            :cidr_block (get-in cidr-block [:private az])
                                                            :availability_zone (stringify region az)
                                                            }]
                                 ["aws_route_table" subnet-name {:tags {:Name subnet-name}
                                                                 :route {:cidr_block "0.0.0.0/0"
                                                                         :nat_gateway_id (id-of "aws_nat_gateway" public-subnet-name)}}

                                  ]
                                 ["aws_route_table_association" subnet-name {:route_table_id (id-of "aws_route_table" subnet-name)
                                                                             :subnet_id (id-of "aws_subnet" subnet-name)
                                                                             }]])))))))

(defn -main []
  (to-file vpc-vpn-infra "vpc/vpc.tf"))
