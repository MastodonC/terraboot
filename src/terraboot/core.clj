(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [stencil.core :as mustache]
            [clj-yaml.core :as yaml]
            [clojure.pprint :refer [pprint]]))

(def account-id "12345")
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

(defn arn-of [type name]
  (output-of type name "arn"))

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
        add-to-resources-if-present (fn [type resources]
                                      (if (get-in resources [:resource type])
                                        (update-in resources [:resource type] (fn [spec] (add-to-every-value-map spec :vpc_id vpc-id)))
                                        resources))]
    (apply merge-in
           (map (comp (partial add-to-resources-if-present "aws_security_group")
                      (partial add-to-resources-if-present "aws_internet_gateway")
                      (partial add-to-resources-if-present "aws_subnet")
                      (partial add-to-resources-if-present "aws_route_table")) resources))))

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
  (let [defaults {:cert_name false
                  :instances []
                  :lb_protocol "http"}
        spec (merge-in defaults spec)
        {:keys [health_check
                lb_protocol
                instances
                cert_name
                subnets]} spec
        secure_protocol (if (= lb_protocol "http")
                          "https"
                          "ssl")
        default-listener {:instance_port 80
                          :instance_protocol lb_protocol
                          :lb_port 80
                          :lb_protocol lb_protocol}

        listeners (if cert_name
                    [default-listener {
                                       :instance_port 80
                                       :instance_protocol lb_protocol
                                       :lb_port 443
                                       :lb_protocol secure_protocol
                                       :ssl_certificate_id (str "arn:aws:iam::" account-id ":server-certificate/" cert_name)}]
                    [default-listener])
        ]
    (let [elb-sg (str "elb_" name)
          allow-sg (str "allow_elb_" name)]
      (merge-in (security-group allow-sg {}
                                {:port 80
                                 :source_security_group_id (id-of "aws_security_group" elb-sg)
                                 })
                (security-group elb-sg {})
                (resource "aws_elb" name {:subnets subnets
                                          :security_groups [(id-of "aws_security_group" elb-sg)
                                                            (id-of "aws_security_group" "allow_external_http_https")
                                                            (id-of "aws_security_group" "allow_outbound")]
                                          :listener listeners
                                          :instances instances
                                          :health_check health_check
                                          :cross_zone_load_balancing true
                                          :idle_timeout 60
                                          :connection_draining true
                                          :connection_draining_timeout 60
                                          :tags {:Name name}})))))

(defn asg [name {:keys [sgs image_id user_data instance_type subnets role] :as spec}]
  (let [elb? (spec :elb)
        sgs (if elb?
              (conj sgs (str "allow_elb_" name))
              sgs)


        asg-config
        (merge-in
         (resource "aws_iam_instance_profile" name
                   {:name (str name "-profile")
                    :roles [(id-of "aws_iam_role" role)]})

         (resource "aws_launch_configuration" name
                   (merge {:name_prefix (str name "-")
                           :image_id image_id
                           :instance_type instance_type
                           :iam_instance_profile (id-of "aws_iam_instance_profile" name)
                           :user_data user_data
                           :lifecycle { :create_before_destroy true }
                           :key_name (get spec :key_name "ops-terraboot")
                           :security_groups (map #(id-of "aws_security_group" %) sgs)}
                          (spec :block-device)))

         (resource "aws_autoscaling_group" name
                   {:vpc_zone_identifier subnets
                    :name name
                    :max_size (spec :max_size)
                    :min_size (spec :min_size)
                    :health_check_type (spec :health_check_type)
                    :health_check_grace_period (spec :health_check_grace_period)
                    :launch_configuration (output-of "aws_launch_configuration" name "name")
                    :lifecycle { :create_before_destroy true }
                    :load_balancers (if elb? [(output-of "aws_elb" name "name")]
                                        [])
                    :tag {
                          :key "Name"
                          :value "autoscale-#{name}"
                          :propagate_at_launch true
                          }}))]
    (if elb?
      (merge-in asg-config (elb name (spec :elb)))
      asg-config)))

(defn policy [statement]
  (let [default-policy {"Version" "2012-10-17"
                        "Statement" {"Effect" "Allow"
                                     "Resource" "*"}}]
    (to-json (merge default-policy {"Statement" statement}))))

(def all-external "0.0.0.0/0")

(def region "eu-central-1")

(def azs [:a :b])

(defn from-template [template-name vars]
  (mustache/render-file template-name vars))

(defn snippet [path]
  (slurp (clojure.java.io/resource path)))
