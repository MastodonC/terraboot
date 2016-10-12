(ns terraboot.public-dns
  (:require [terraboot.core :as core]
            [terraboot.utils :as utils]))

(defn public-route53-record [dns-zone dns-zone-id prefix spec]
  (let [name (str prefix "." dns-zone)]
    (core/resource "aws_route53_record" (core/safe-name name)
                   (merge
                    {:zone_id dns-zone-id
                     :name name
                     :type "A"}
                    (if (:alias spec) {} {:ttl "300"})
                    spec))))

(defn vpc-public-dns
  [dns-zone dns-zone-id vpc-name]
  (let [route53-record (partial public-route53-record dns-zone dns-zone-id)
        vpc-output-of (core/output-of-fn (core/vpc-unique-fn vpc-name))]
    (utils/merge-in
     (route53-record "vpn" {:records [(vpc-output-of "aws_instance" "vpn" "public_ip")]})
     (route53-record "logstash" {:records [(vpc-output-of "aws_eip" "logstash" "public_ip")]})
     (route53-record "grafana" {:type "CNAME"
                                :records [(core/output-of "aws_elb" "grafana" "dns_name")]})
     (route53-record "influxdb" {:records [(core/output-of "aws_eip" "influxdb" "public_ip")]})
     (route53-record "alerts" {:type "CNAME"
                               :records [(core/output-of "aws_elb" "alerts" "dns_name")]}))))

(defn cluster-public-dns
  [dns-zone dns-zone-id vpc-name cluster-name]
  (let [route53-record (partial public-route53-record dns-zone dns-zone-id)
        cluster-identifier (core/cluster-identifier vpc-name cluster-name)
        cluster-unique (core/cluster-unique-fn vpc-name cluster-name)
        cluster-output-of (core/output-of-fn cluster-unique)]
    (utils/merge-in
     (route53-record (cluster-unique "deploy")
                     {:alias {:name (cluster-output-of "aws_alb" "public-apps" "dns_name")
                              :zone_id (cluster-output-of "aws_alb" "public-apps" "zone_id")
                              :evaluate_target_health true}})
     (route53-record cluster-identifier
                     {:alias {:name (cluster-output-of "aws_alb" "public-apps" "dns_name")
                              :zone_id (cluster-output-of "aws_alb" "public-apps" "zone_id")
                              :evaluate_target_health true}}))))
