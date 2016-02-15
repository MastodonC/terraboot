(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [clojure.pprint :refer [pprint]]))

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

(def azs [:a :b :c])

(def subnet-types [:public :private])

(def cidr-block { :public {:a "172.20.0.0/24"
                           :b "172.20.1.0/24"
                           :c "172.20.2.0/24"}
                  :private {:a "172.20.8.0/24"
                            :b "172.20.9.0/24"
                            :c "172.20.10.0/24"}
                  })

(defn stringify [& args]
  (apply str (map name args)))

(defn security-group [name spec]
  (resource "aws_security_group" name
            (merge {:Name name}
                   spec)))

(def vpc-name "sandpit")

(def infra (merge-in
            (resource "aws_vpc" vpc-name
                      {:tags {:Name vpc-name}
                       :cidr_block "172.20.0.0/20"})

            (resource "aws_internet_gateway" vpc-name
                      {:vpc_id (id-of "aws_vpc" vpc-name)})

            (resource-seq
             (for [az azs
                   subnet-type subnet-types]
               (let [subnet-name (stringify vpc-name "-" subnet-type "-" az)]
                 ["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                            :vpc_id (id-of "aws_vpc" vpc-name)
                                            :cidr_block (get-in cidr-block [subnet-type az])
                                           }])))


            ))

(defn -main []
  (to-file infra "vpc/vpc.tf"))
