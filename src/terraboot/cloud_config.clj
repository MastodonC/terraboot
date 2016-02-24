(ns terraboot.cloud-config
  (:require
            [clj-yaml.core :as yaml]))

(defn add-beginning [s beginning]
  (str beginning s))

(defn cloud-config [{:keys [files] :as m}]
  (-> m
      (yaml/generate-string :dumper-options {:flow-style :block})
      (add-beginning "#cloud-config\n")))
