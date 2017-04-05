(ns terraboot.public-dns
  (:require [terraboot.core :as core]
            [terraboot.utils :as utils]
            [clojure.string :as string :refer [join replace]]))

(defn public-route53-record [root-dns dns-zone-id prefix spec]
  (let [name (str prefix "." root-dns)]
    (core/resource "aws_route53_record" (core/safe-name name)
                   (merge
                    {:zone_id dns-zone-id
                     :name name
                     :type "A"}
                    (if (:alias spec) {} {:ttl "300"})
                    spec))))

(defn public-dns-zone
  [environment-dns environment-dns-identifier]
  (core/resource "aws_route53_zone" environment-dns-identifier
                 {:name environment-dns
                  :comment (str "public routes for " environment-dns)}))

(defn vpc-public-dns
  [{:keys [root-dns root-dns-zone-id environment project vpc-name]}]
  (let [environment-dns (string/join "." [environment project root-dns])
        environment-dns-identifier (string/replace environment-dns #"\." "_")]
    (utils/merge-in
     (public-dns-zone environment-dns environment-dns-identifier)
     (public-route53-record root-dns
                            root-dns-zone-id
                            (string/join "." [environment project])
                            {:type "NS"
                             :records (mapv #(core/output-of "aws_route53_zone" environment-dns-identifier (string/join  "." ["name_server" %])) (range 0 4))})
     (public-route53-record environment-dns
                            (core/id-of "aws_route53_zone" environment-dns-identifier)
                            "vpn"
                            {:records [(core/output-of "aws_eip" (str vpc-name "-vpn") "public_ip")]}))))
