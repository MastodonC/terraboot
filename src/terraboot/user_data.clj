(ns terraboot.user-data
  (:require [terraboot.core :refer :all]
            [terraboot.utils :refer :all]))

(def beats-user-data
  {:coreos      {:units [{:name "filebeat.service" :command "start" :content (snippet "systemd/filebeat.service")}
                         {:name "metricbeat.service" :command "start" :content (snippet "systemd/metricbeat.service")}
                         {:name "dcos-journalctl-file.service" :command "start" :content (snippet "systemd/dcos-journalctl-file.service")}
                         {:name "copy-bins.service" :command "start" :content (snippet "systemd/copy-bins.service")}]}
   :write_files [{:path    "/etc/beats/filebeat.yml"
                  :content (snippet "system-files/filebeat.yml")}
                 {:path    "/etc/beats/metricbeat.yml"
                  :content (snippet "system-files/metricbeat.yml")}]})

(def dockerd-logging
  {:write_files [{:path        "/etc/systemd/system/docker.service.d/journald-logging.conf"
                  :content     (snippet "system-files/dockerd-journald-logging.conf")
                  :permissions "0655"}]})