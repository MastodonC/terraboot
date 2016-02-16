(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [stencil.core :as mustache]
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

(def azs [:a :b])

(def subnet-types [:public :private])

(def cidr-block {:public {:a "172.20.0.0/24"
                          :b "172.20.1.0/24"
                          :c "172.20.2.0/24"}
                 :private {:a "172.20.8.0/24"
                           :b "172.20.9.0/24"
                           :c "172.20.10.0/24"}
                 })

(defn stringify [& args]
  (apply str (map name args)))

(defn security-group [name spec & rules]
  (merge-in
   (resource "aws_security_group" name
             (merge {:name name
                     :tags {:Name name}}
                    spec))
   (resource-seq
    (for [rule rules]
      (let [defaults {:protocol "tcp"
                      :type "ingress"
                      :security_group_id (id-of "aws_security_group" name)}
            rule (merge defaults rule)
            suffix (str (hash rule))]
        ["aws_security_group_rule"
         (stringify name "-" suffix)
         rule])))))

(defn aws-instance [name spec]
  (let [default-sgs ["allow_outbound"]
        default-sg-ids (map (partial id-of "aws_security_group") default-sgs)]
    (resource "aws_instance" name (-> {:tags {:Name name}
                                       :instance_type "t2.micro"
                                       :key_name "ops"
                                       :monitoring true}
                                      (merge-in spec)
                                      (update-in [:vpc_security_group_ids] concat default-sg-ids)))))

(def all-external "0.0.0.0/0")

(def region "eu-central-1")

(defn from-template [template-name vars]
  (mustache/render-file template-name vars))
