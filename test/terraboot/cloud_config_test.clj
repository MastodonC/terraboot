
(ns terraboot.cloud-config-test
  (:require [terraboot.cloud-config :refer :all]
            [expectations :refer :all]
            [clj-yaml.core :as yaml]))

(def file1 {"/path/to/file1"
            {:permissions "600"}})

(def file2 {"/path/to/file2"
            "poop"})

(def merged (merge file1 file2))

(def result1 [{:path "/path/to/file1"
                :permissions "600"}])

(def result2 [{:path "/path/to/file2"
                :content "poop"}])

(def merged-result (concat result1
                           result2))

(expect (files->write-files file1)
        result1)

(expect (files->write-files file2)
        result2)

(expect (files->write-files merged)
        merged-result)

(def extras {:users ["default"]
             :packages ["openvn"]})

(expect (yaml/parse-string (cloud-config (merge {:files merged}
                                                extras)))
        (merge {:write_files merged-result}
               extras))
