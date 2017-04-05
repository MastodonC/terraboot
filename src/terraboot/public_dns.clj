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
     (route53-record "vpn" {:records [(vpc-output-of "aws_eip" "vpn" "public_ip")]}))))
