(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [stencil.core :as mustache]
            [clojure.pprint :refer [pprint]]
            ))

(letfn [(merge-in* [a b]
          (if (map? a)
            (merge-with merge-in* a b)
            b))]
  (defn merge-in
    "Merge multiple nested maps."
    [& args]
    (reduce merge-in* nil args)))

(defn output-of [type resource-name & values]
  (str "${"
       (name type) "."
       (name resource-name) "."
       (string/join "." (map name values))
       "}"))

(defn id-of [type name]
  (output-of type name "id"))

(defn resource [type name spec]
  {:resource
   {type
    {name
     spec}}})

(defn provider [type spec]
  {:provider
   {type
    spec}})

(defn resources [m]
  {:resource m})

(defn resource-seq [s]
  (apply merge-in (map (partial apply resource)
                       s)))

(def json-options {:key-fn name :pretty true})
(defn to-json [tfmap]
  (json/generate-string tfmap json-options))

(defn to-file [tfmap file-name]
  (println "Outputing to" file-name)
  (json/generate-stream tfmap (clojure.java.io/writer file-name) json-options))

(def azs [:a :b])

(def subnet-types [:public :private])

(def cidr-block {:public {:a "172.20.0.0/24"
                          :b "172.20.1.0/24"
                          :c "172.20.2.0/24"}
                 :private {:a "172.20.8.0/24"
                           :b "172.20.9.0/24"
                           :c "172.20.10.0/24"}
                 })

(defn stringify [& args]
  (apply str (map name args)))

(defn security-group [name spec & rules]
  (merge-in
   (resource "aws_security_group" name
             (merge {:name name
                     :tags {:Name name}}
                    spec))
   (resource-seq
    (for [rule rules]
      (let [defaults {:protocol "tcp"
                      :type "ingress"
                      :security_group_id (id-of "aws_security_group" name)}
            rule (merge defaults rule)
            suffix (str (hash rule))]
        ["aws_security_group_rule"
         (stringify name "-" suffix)
         rule])))))

(defn aws-instance [name spec]
  (resource "aws_instance" name (merge-in {:tags {:Name name}
                                           :instance_type "t2.micro"
                                           :key_name "ops"
                                           :monitoring true}
                                          spec)))

(def all-external "0.0.0.0/0")

(def vpc-name "sandpit")

(def region "eu-central-1")

(defn from-template [template-name vars]
  (mustache/render-file template-name vars))

(def infra (merge-in
            (resource "aws_vpc" vpc-name
                      {:tags {:Name vpc-name}
                       :cidr_block "172.20.0.0/20"})

            (aws-instance "vpn" {
                                 :user_data (from-template "vpn-config" {:range-start "172.20.0.0"
                                                                         :fallback-dns "172.20.0.2"})
                                 :subnet_id (id-of "aws_subnet" "public-b")
                                 :ami "ami-bc5b48d0"
                                 :vpc_security_group_ids [(id-of "aws_security_group" "vpn")
                                                          (id-of "aws_security_group" "allow_outbound")]
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
                                                  :vpc_id (id-of "aws_vpc" vpc-name) })

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
  (to-file infra "vpc/vpc.tf"))
