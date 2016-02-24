(ns terraboot.cloud-config
  (:require
            [clj-yaml.core :as yaml]))

(defn files->write-files [files]
  (for [[path spec] files]
    (if (string? spec)
      {:content spec
       :path path}
      (assoc spec :path path))))

(defn add-beginning [s beginning]
  (str beginning s))

(defn cloud-config [{:keys [files] :as m}]
  (-> m
      (assoc :write_files (files->write-files files))
      (dissoc :files)
      (yaml/generate-string :dumper-options {:flow-style :block})
      (add-beginning "#cloud-config\n")))
