(ns terraboot.vpc
  (:require [terraboot.core :refer :all]))

(def vpc-name "sandpit")

(def vpc-vpn-infra
  (merge-in
            (resource "aws_vpc" vpc-name
                      {:tags {:Name vpc-name}
                       :cidr_block "172.20.0.0/20"})

            (aws-instance "vpn" {
                                 :user_data (from-template "vpn-config" {:range-start "172.20.0.0"
                                                                         :fallback-dns "172.20.0.2"})
                                 :subnet_id (id-of "aws_subnet" "public-b")
                                 :ami "ami-bc5b48d0"
                                 :vpc_security_group_ids [(id-of "aws_security_group" "vpn")
                                                          ]
                                 :associate_public_ip_address true
                                 })

            (security-group "allow_outbound" {:vpc_id (id-of "aws_vpc" vpc-name)}
                            {:type "egress"
                             :from_port 0
                             :to_port 0
                             :protocol -1
                             :cidr_blocks [all-external]
                             })

            (security-group "vpn" {:vpc_id (id-of "aws_vpc" vpc-name)}
                            {:from_port 22
                             :to_port 22
                             :cidr_blocks [all-external]}
                            {:from_port 1194
                             :to_port 1194
                             :protocol "udp"
                             :cidr_blocks [all-external]}
                            )

            (security-group "allow_external_http_https" {:vpc_id (id-of "aws_vpc" vpc-name)}
                            {:from_port 80
                             :to_port 80
                             :cidr_blocks ["0.0.0.0/0"]
                             }
                            {:from_port 443
                             :to_port 443
                             :cidr_blocks ["0.0.0.0/0"]})

            (resource "aws_internet_gateway" vpc-name
                      {:vpc_id (id-of "aws_vpc" vpc-name)})

            (resource "aws_route_table" "public" {:tags { :Name "public"}
                                                  :vpc_id (id-of "aws_vpc" vpc-name)})

            ;; Public Subnets
            (resource-seq
             (apply concat
                    (for [az azs]
                      (let [subnet-name (stringify "public" "-" az)
                            nat-eip (stringify subnet-name "-nat")]
                        [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                    :vpc_id (id-of "aws_vpc" vpc-name)
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
                                                    :vpc_id (id-of "aws_vpc" vpc-name)
                                                    :cidr_block (get-in cidr-block [:private az])
                                                    :availability_zone (stringify region az)
                                                    }]
                         ["aws_route_table" subnet-name {:tags {:Name subnet-name}
                                                         :vpc_id (id-of "aws_vpc" vpc-name)
                                                         :route {:cidr_block "0.0.0.0/0"
                                                                 :nat_gateway_id (id-of "aws_nat_gateway" public-subnet-name)}}

                          ]
                         ["aws_route_table_association" subnet-name {:route_table_id (id-of "aws_route_table" subnet-name)
                                                                     :subnet_id (id-of "aws_subnet" subnet-name)
                                                                     }]
                         ])
                      )))))

(defn -main []
  (to-file vpc-vpn-infra "vpc/vpc.tf"))
