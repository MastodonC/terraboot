(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [stencil.core :as mustache]
            [clj-yaml.core :as yaml]
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

(defn add-to-every-value-map
  [map key value]
  (reduce-kv (fn [m k v]
               (assoc m k (assoc v key value))) {} map))

(defn in-vpc
  [vpc-name & resources]
  (let [vpc-id (id-of "aws_vpc" vpc-name)
        add-to-resources-if-present (fn [resources type]
                                      (if (get-in resources [:resource type])
                                        (update-in [:resource "aws_security_group"] (fn [spec] (add-to-every-value-map spec :vpc_id vpc-id)))
                                        resources))]
    (apply merge-in
           (-> resources
               (add-to-resources-if-present "aws_security_group")
               (add-to-resources-if-present "aws_internet_gateway")
               (add-to-resources-if-present "aws_subnet")
               (add-to-resources-if-present "aws_route_table")))))

(def json-options {:key-fn name :pretty true})

(defn to-json [tfmap]
  (json/generate-string tfmap json-options))

(defn to-file [tfmap file-name]
  (println "Outputing to" file-name)
  (json/generate-stream tfmap (clojure.java.io/writer file-name) json-options))


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
            _ (println rule)
            port (:port rule)
            port-to-port-range (fn [rule] (if port (-> (assoc rule :from_port port :to_port port) (dissoc :port)) rule))
            rule (merge defaults (port-to-port-range rule))
            suffix (str (hash rule))]
        ["aws_security_group_rule"
         (stringify name "-" suffix)
         rule])))))

(defn aws-instance [name spec]
  (let [default-sgs ["allow_outbound"]
        default-sg-ids (map (partial id-of "aws_security_group") default-sgs)]
    (resource "aws_instance" name (-> {:tags {:Name name}
                                       :instance_type "t2.micro"
                                       :key_name "ops-terraboot"
                                       :monitoring true
                                       :subnet_id (id-of "aws_subnet" "private-a")}
                                      (merge-in spec)
                                      (update-in [:vpc_security_group_ids] concat default-sg-ids)))))

(defn elb [name spec]
  (resource "aws_elb" name (-> {:listeners [{:instance_port 80
                                             :lb_port 80
                                             :instance_protocol "http"
                                             :lb_protocol "http"}
                                            {:instance_port 443
                                             :instance_protocol "http"
                                             :lb_port 443
                                             :lb_protocol "http"}
                                            (merge-in spec)]})))
(def all-external "0.0.0.0/0")

(def region "eu-central-1")

(def azs [:a :b])

(defn from-template [template-name vars]
  (mustache/render-file template-name vars))

(defn snippet [path]
  (slurp (clojure.java.io/resource path)))
