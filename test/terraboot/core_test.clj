(ns terraboot.core-test
  (:require
   [terraboot.core :refer :all]
   [clj-yaml.core :as yaml]
   [expectations :refer :all]))

(expect (security-group "allow_outbound" {}
                        {:type "egress"
                         :from_port 0
                         :to_port 0
                         :protocol -1
                         :cidr_blocks [all-external]
                         })
        {:resource {"aws_security_group"
                    {"allow_outbound"
                     {:name "allow_outbound",
                      :tags {:Name "allow_outbound"}}},
                    "aws_security_group_rule"
                    {"allow_outbound-357201181"
                     {:protocol -1, :type "egress", :security_group_id "${aws_security_group.allow_outbound.id}", :from_port 0, :to_port 0, :cidr_blocks ["0.0.0.0/0"]}}}})

(expect (security-group "allow_external_http_https" {}
                        {:port 80
                         :cidr_blocks [all-external]
                         }
                        {:port 443
                         :cidr_blocks [all-external]})
        {:resource {"aws_security_group" {"allow_external_http_https" {:name "allow_external_http_https", :tags {:Name "allow_external_http_https"}}}, "aws_security_group_rule" {"allow_external_http_https--901603019" {:protocol "tcp", :type "ingress", :security_group_id "${aws_security_group.allow_external_http_https.id}", :from_port 80, :to_port 80, :cidr_blocks ["0.0.0.0/0"]}, "allow_external_http_https--347778063" {:protocol "tcp", :type "ingress", :security_group_id "${aws_security_group.allow_external_http_https.id}", :from_port 443, :to_port 443, :cidr_blocks ["0.0.0.0/0"]}}}})
