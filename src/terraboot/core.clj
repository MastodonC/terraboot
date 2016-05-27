(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [stencil.core :as mustache]
            [clj-yaml.core :as yaml]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]))

(def account-id "165664414043")
(def default-sgs ["allow_ssh" "allow_outbound"])

(def ubuntu "ami-9b9c86f7")
(def current-coreos-ami "ami-1807e377")
(def ec2-ami "ami-bc5b48d0")

(letfn [(sensitive-merge-in*
          [mfns]
          (fn [a b]
            (if (map? a)
              (do (when-let [dups (seq (set/intersection (set (keys a)) (set (keys b))))]
                    (throw (Exception. (str "Duplicate keys: " dups))))
                  (merge-with ((first mfns) (rest mfns)) a b))
              b)))
        (merge-in*
          [mfns]
          (fn [a b]
            (if (map? a)
              (merge-with ((first mfns) (rest mfns)) a b)
              b)))
        (merge-with-fn-seq
          [fn-seq]
          (partial merge-with
                   ((first fn-seq) (rest fn-seq))))]
  ;; todo rediscover duplicates but not for the :output tree
  (def merge-in
    (merge-with-fn-seq (repeat merge-in*))))

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

(defn resource
  ([type name spec]
   {:resource
    {type
     {name
      spec}}})
  ([name-fn type name spec]
   (resource type (name-fn name) (-> (if (:name spec)
                                       (assoc spec :name (name-fn (:name spec)))
                                       spec)
                                     (#(if (get-in % [:tags :Name])
                                         (assoc-in % [:tags :Name] (name-fn (get-in % [:tags :Name])))
                                         %))))))

(defn output
  [output-name type resource-name value]
  {:output
   {output-name
    {:value (output-of type resource-name value)}}})

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
  [vpc-id & resources]
  (let [add-to-resources-if-present (fn [type resources]
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



(defn scoped-security-group
  [name-fn name spec & rules]
  (merge-in
   (resource "aws_security_group" (name-fn name)
             (merge {:name (name-fn name)
                     :tags {:Name (name-fn name)}}
                    spec))
   (resource-seq
    (for [rule rules]
      (let [defaults {:protocol "tcp"
                      :type "ingress"
                      :security_group_id (id-of "aws_security_group" (name-fn name))}

            port-to-port-range (fn [rule] (if-let [port (:port rule)]
                                            (-> rule
                                                (assoc :from_port port :to_port port)
                                                (dissoc :port))
                                            rule))

            allow-all-sg (fn [rule] (if-let [allow-all-sg-id (:allow-all-sg rule)]
                                      (-> rule
                                          (assoc :from_port 0 :to_port 0)
                                          (assoc :protocol -1)
                                          (assoc :source_security_group_id allow-all-sg-id)
                                          (dissoc :allow-all-sg))
                                      rule))
            rule (-> (merge defaults rule)
                     port-to-port-range
                     allow-all-sg)
            suffix (str (hash rule))]
        ["aws_security_group_rule"
         (stringify (name-fn name) "-" suffix)
         rule])))))

(def security-group
  (partial scoped-security-group identity))

(defn safe-name [s]
  (string/replace s #"\." "__"))

(def dns-zone "mastodonc.net")
(def dns-zone-id "Z1EFD0WXZUIXYT")

(defn route53_record [prefix spec]
  (let [name (str prefix "." dns-zone)]
    (resource "aws_route53_record" (safe-name name)
              (merge
               {:zone_id dns-zone-id
                :name name
                :type "A"}
               (if (:alias spec) {} {:ttl "300"})
               spec))))

(defn aws-instance [name spec]
  (let [default-sg-ids (map (partial id-of "aws_security_group") default-sgs)]
    (resource "aws_instance" name (-> {:tags {:Name name}
                                       :instance_type "t2.micro"
                                       :key_name "ops-terraboot"
                                       :monitoring true
                                       :subnet_id (id-of "aws_subnet" "private-a")}
                                      (merge-in spec)
                                      (update-in [:vpc_security_group_ids] concat default-sg-ids)))))


(defn elb-listener [{:keys [port lb_port protocol lb_protocol]}]
  {:instance_port port
   :instance_protocol protocol
   :lb_port (or lb_port port)
   :lb_protocol (or lb_protocol protocol)})

(defn elb [name cluster-resource spec]
  (let [defaults {:cert_name false
                  :instances []
                  :lb_protocol "http"
                  :internal false}
        spec (merge-in defaults spec)
        {:keys [health_check
                lb_protocol
                instances
                cert_name
                subnets
                security-groups
                internal]} spec
        secure_protocol (if (= lb_protocol "http")
                          "https"
                          "ssl")
        default-listener {:instance_port 80
                          :instance_protocol lb_protocol
                          :lb_port 80
                          :lb_protocol lb_protocol}

        default-listeners (if cert_name
                            [default-listener {
                                               :instance_port 80
                                               :instance_protocol lb_protocol
                                               :lb_port 443
                                               :lb_protocol secure_protocol
                                               :ssl_certificate_id (str "arn:aws:iam::" account-id ":server-certificate/" cert_name)}]
                            [default-listener])
        listeners (concat default-listeners (:listeners spec))]
    (cluster-resource "aws_elb" name {:name name
                                      :subnets subnets
                                      :security_groups security-groups
                                      :listener listeners
                                      :instances instances
                                      :health_check health_check
                                      :cross_zone_load_balancing true
                                      :idle_timeout 60
                                      :connection_draining true
                                      :connection_draining_timeout 60
                                      :internal internal
                                      :tags {:Name name}})))

(defn asg [name
           name-fn
           {:keys [sgs
                   image_id
                   user_data
                   instance_type
                   subnets
                   role
                   public_ip
                   root_block_device_size] :as spec}]
  (let [size-disk-if-present (fn [root_block_device_size map]
                               (if root_block_device_size
                                 (assoc map :root_block_device {:volume_size root_block_device_size})
                                 map))
        root_block_device (get spec :root_block_device {})
        cluster-resource (partial resource name-fn)
        cluster-id-of (fn [type name] (id-of type (name-fn name)))
        cluster-output-of (fn [type name & values] (apply (partial output-of type (name-fn name)) values))
        asg-config
        (merge-in
         (cluster-resource "aws_iam_instance_profile" name
                           {:name (str name "-profile")
                            :roles [(id-of "aws_iam_role" role)]})

         (cluster-resource "aws_launch_configuration" name
                           (size-disk-if-present root_block_device_size
                                                 {:name_prefix (str (name-fn name) "-")
                                                  :image_id image_id
                                                  :instance_type instance_type
                                                  :iam_instance_profile (cluster-id-of "aws_iam_instance_profile" name)
                                                  :user_data user_data
                                                  :lifecycle { :create_before_destroy true }
                                                  :key_name (get spec :key_name "ops-terraboot")
                                                  :security_groups sgs
                                                  :associate_public_ip_address (or public_ip false)}

                                                 )
                           )

         (cluster-resource "aws_autoscaling_group" name
                           {:vpc_zone_identifier subnets
                            :name name
                            :max_size (spec :max_size)
                            :min_size (spec :min_size)
                            :health_check_type (spec :health_check_type)
                            :health_check_grace_period (spec :health_check_grace_period)
                            :launch_configuration (cluster-output-of "aws_launch_configuration" name "name")
                            :lifecycle { :create_before_destroy true }
                            :load_balancers (mapv #(cluster-output-of "aws_elb" (:name %) "name") (:elb spec))
                            :tag {
                                  :key "Name"
                                  :value (str "autoscale-" name)
                                  :propagate_at_launch true
                                  }}))]
    (merge-in asg-config
              (apply merge-in (map #(elb (:name %) cluster-resource %) (spec :elb))))))

(def default-assume-policy
  (to-json {"Statement" [{"Action" ["sts:AssumeRole"]
                          "Effect" "Allow"
                          "Principal" {"Service" ["ec2.amazonaws.com"]}}]
            "Version" "2012-10-17" }))

(defn iam-role [name name-fn & policies]
  (let [cluster-resource (partial resource name-fn)
        cluster-id-of (fn [type name] (id-of type (name-fn name)))]
    (merge-in
     (cluster-resource "aws_iam_role" name
                       {:name name
                        :assume_role_policy default-assume-policy
                        :path "/"})
     (apply merge-in (mapv
                      #(cond
                         (:policy %) (cluster-resource "aws_iam_role_policy" (:name %)
                                                       (assoc % :role (cluster-id-of "aws_iam_role" name)))
                         (:policy_arn %) (cluster-resource "aws_iam_policy_attachment" (:name %)
                                                           (assoc % :roles [(cluster-id-of "aws_iam_role" name)])))
                      policies)))

    ))
(defn policy [statement]
  (let [default-policy {"Version" "2012-10-17"
                        "Statement" {"Effect" "Allow"
                                     "Resource" "*"}}]
    (to-json (merge-in default-policy {"Statement" statement}))))

(def all-external "0.0.0.0/0")

(def region "eu-central-1")

(def azs [:a :b])

(defn from-template [template-name vars]
  (mustache/render-file template-name vars))

(defn snippet [path]
  (slurp (clojure.java.io/resource path)))

(defn database [{:keys [name subnet] :as spec}]
  (merge-in
   (resource "aws_db_parameter_group" name
             {:name name
              :family "postgres9.4"
              :description "RDS parameter group"
              })
   (resource "aws_db_instance" name
             {:allocated_storage 10
              :engine "postgres"
              :engine_version "9.4.7"
              :instance_class "db.t2.small"
              :identifier name
              :username "kixi"
              :password "abcdefgh12" ;; TO CHANGE
              :parameter_group_name name
              :vpc_security_group_ids [(id-of "aws_security_group" "allow_outbound")
                                       (id-of "aws_security_group" (str "db-" name))]
              :db_subnet_group_name (id-of "aws_db_subnet_group" subnet)
              })

   (security-group (str "uses-db-" name) {})

   (security-group (str "db-" name) {}
                   {:port 5432
                    :source_security_group_id (id-of "aws_security_group" (str "uses-db-" name))})))

(defn remote-state [name]
  (resource "terraform_remote_state" name
            {:backend "s3"
             :config {:bucket "terraboot"
                      :key (str name ".tf")
                      :region region}}))

(defn remote-output-of [module name]
  (output-of (str "terraform_remote_state." module) "output" name))
