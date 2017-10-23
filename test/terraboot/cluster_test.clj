(ns terraboot.cluster-test
  (:require [terraboot.cluster :refer :all]
            [terraboot.core :refer :all]
            [clojure.test :refer :all]))

(deftest bucket-policy-test
  (is (= {"Statement" {"Effect" "Allow"
                       "Resource" ["arn:aws:s3:::rick"
                                   "arn:aws:s3:::rick/*"
                                   "arn:aws:s3:::morty"
                                   "arn:aws:s3:::morty/*"]
                       "Action" ["s3:AbortMultipartUpload"
                                 "s3:DeleteObject"
                                 "s3:GetBucketAcl"
                                 "s3:GetBucketPolicy"
                                 "s3:GetObject"
                                 "s3:GetObjectAcl"
                                 "s3:ListBucket"
                                 "s3:ListBucketMultipartUploads"
                                 "s3:ListMultipartUploadParts"
                                 "s3:PutObject"
                                 "s3:PutObjectAcl"]}
          "Version" "2012-10-17"}
         (with-redefs [to-json identity]
           (bucket-policy ["rick" "morty"])))))
