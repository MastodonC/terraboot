(ns terraboot.core
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [stencil.core :as mustache]
            [clj-yaml.core :as yaml]
            [clojure.pprint :refer [pprint]]
            [terraboot.utils :refer :all]))


(def default-sgs ["allow_ssh" "allow_outbound"])

(def all-external "0.0.0.0/0")

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

(defn data
  ([type name spec]
   {:data
    {type
     {name spec}}})
  ([name-fn type name spec]
   (data type (name-fn name) (-> (if (:name spec)
                                   (assoc spec :name (name-fn (:name spec)))
                                   spec)
                                 (#(if (get-in % [:tags :Name])
                                     (assoc-in % [:tags :Name] (name-fn (get-in % [:tags :Name])))
                                     %))))))

(defn template-file
  [name content vars]
  (data "template_file" name
        {:template content
         :vars vars}))

(defn rendered-template-file
  [name]
  (str "${" (clojure.string/join "." ["data" "template_file" name "rendered"]) "}"))

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
                      (partial add-to-resources-if-present "aws_route_table")
                      (partial add-to-resources-if-present "aws_alb_target_group")) resources))))

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



(defn aws-instance [name spec]
  (let [default-sg-ids (map (partial id-of "aws_security_group") default-sgs)]
    (resource "aws_instance" name (-> {:tags {:Name name}
                                       :instance_type "t2.micro"
                                       :key_name "ops-terraboot"
                                       :monitoring true
                                       :subnet_id (id-of "aws_subnet" "private-a")}
                                      (merge-in spec)
                                      (update-in [:vpc_security_group_ids] concat default-sg-ids)))))


(defn private-public-subnets [{:keys [naming-fn az cidr-blocks public-route-table region]}]
  (let [public-subnet-name (naming-fn (stringify "public-" az))
        private-subnet-name (naming-fn (stringify "private-" az))
        nat-eip (stringify public-subnet-name "-nat")]
    (merge-in

     ;; public
     (resource "aws_subnet" public-subnet-name  {:tags {:Name public-subnet-name}
                                                 :cidr_block (:public cidr-blocks)
                                                 :availability_zone (stringify region az)})

     (resource "aws_route_table_association" public-subnet-name {:route_table_id public-route-table
                                                                 :subnet_id (id-of "aws_subnet" public-subnet-name)})


     (resource "aws_nat_gateway" public-subnet-name {:allocation_id (id-of "aws_eip" nat-eip)
                                                     :subnet_id  (id-of "aws_subnet" public-subnet-name)})
     (resource "aws_eip" nat-eip {:vpc true})

     ;; private
     (resource "aws_subnet" private-subnet-name {:tags {:Name private-subnet-name}
                                                 :cidr_block (:private cidr-blocks)
                                                 :availability_zone (stringify region az)})

     (resource "aws_route_table" private-subnet-name {:tags {:Name private-subnet-name}
                                                      :route {:cidr_block all-external
                                                              :nat_gateway_id (id-of "aws_nat_gateway" public-subnet-name)}})

     (resource "aws_route_table_association" private-subnet-name {:route_table_id (id-of "aws_route_table" private-subnet-name)
                                                                  :subnet_id (id-of "aws_subnet" private-subnet-name)}))))


(defn account-elb-listener[account-number]
  (fn [{:keys [port lb-port protocol lb-protocol cert-name cert-id]}]
    (let [iam-cert-id (str "arn:aws:iam::" account-number ":server-certificate/" cert-name)
          cert (if cert-name iam-cert-id cert-id)
          add-cert-if-present #(if cert (assoc % :ssl_certificate_id cert) %)]
      (add-cert-if-present {:instance_port port
                            :instance_protocol protocol
                            :lb_port (or lb-port port)
                            :lb_protocol (or lb-protocol protocol)}))))

(defn elb [name cluster-resource spec]
  (let [defaults {:instances []
                  :internal false}
        spec (merge-in defaults spec)
        {:keys [health_check
                lb_protocol
                instances
                cert_name
                subnets
                security-groups
                internal
                account-number
                listeners]} spec]
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

(defn alb-target-group
  [cluster-resource
   {:keys [protocol port name health_check]}]
  (cluster-resource "aws_alb_target_group" name
                    {:name name
                     :protocol protocol
                     :port port
                     :health_check (or health_check [])}))
;; health_check https://www.terraform.io/docs/providers/aws/r/alb_target_group.html# sensible defaults

(defn alb-listener
  [name-fn {:keys [account-number protocol lb-port port alb-name name ssl-policy cert]}]
  (let [cluster-resource (partial resource name-fn)
        cluster-output-of (fn [type name output] (output-of type (name-fn name) output))
        ssl-cert-arn (str "arn:aws:iam::" account-number ":server-certificate/" cert)
        add-cert-if-present #(if cert (assoc % :certificate_arn ssl-cert-arn) %)
        alb-arn (cluster-output-of "aws_alb" alb-name "arn")]
    (cluster-resource "aws_alb_listener" name
                      (add-cert-if-present {:load_balancer_arn alb-arn
                                            :port (or lb-port port) ;; lb-port is optional
                                            :protocol protocol
                                            :default_action {:target_group_arn (cluster-output-of "aws_alb_target_group" name "arn")
                                                             :type "forward"}}))))

(defn alb
  [name-fn
   {:keys [account-number
           name
           subnets
           cluster-resource
           listeners
           security-groups]}]
  (let [cluster-resource (partial resource name-fn)]
    (merge-in
     (cluster-resource "aws_alb" name
                       {:name name
                        :security_groups security-groups
                        :subnets subnets})
     (apply merge-in
            (mapv #(alb-target-group
                    cluster-resource
                    (select-keys % [:name :port :protocol :health_check])) listeners))
     (apply merge-in
            (mapv #(alb-listener
                    name-fn
                    (merge {:account-number account-number
                            :alb-name name}
                           (select-keys % [:name :port :protocol :ssl-policy :cert]))) listeners)))))

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
                            :target_group_arns (mapv #(cluster-output-of "aws_alb_target_group" (:name %) "arn") (:listeners (spec :alb)))
                            :tag {:key "Name"
                                  :value (name-fn name)
                                  :propagate_at_launch true
                                  }}))]

    (merge-in asg-config
              (apply merge-in (map #(elb (:name %) cluster-resource %) (spec :elb)))
              (apply merge-in (map #(alb name-fn %) (spec :alb))))))

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

(defn vpc-unique-fn
  "namespacing vpc resources"
  [vpc-name]
  (fn [name] (str vpc-name "-" name)))

(defn cluster-identifier
  [vpc-name cluster-name]
  (str vpc-name "-" cluster-name))

(defn cluster-unique-fn
  "namespacing cluster resources"
  [vpc-name cluster-name]
  (let [cluster-identifier (cluster-identifier vpc-name cluster-name)]
    (fn [name] (str cluster-identifier "-" name))))

(defn output-of-fn
  [naming-fn]
  (fn [type name & values] (apply (partial output-of type (naming-fn name)) values)))

(defn id-of-fn
  [naming-fn]
  (fn [type name] (id-of type (naming-fn name))))

;; From http://docs.aws.amazon.com/general/latest/gr/rande.html#s3_region
(def s3-endpoints
  {"us-east-1"      "s3.amazonaws.com"
   "us-west-1"      "s3-us-west-1.amazonaws.com"
   "us-west-2"      "s3-us-west-2.amazonaws.com"
   "ap-south-1"     "s3.ap-south-1.amazonaws.com"
   "ap-northeast-2" "s3.ap-northeast-2.amazonaws.com"
   "ap-southeast-1" "s3-ap-southeast-1.amazonaws.com"
   "ap-southeast-2" "s3-ap-southeast-2.amazonaws.com"
   "ap-northeast-1" "s3-ap-northeast-1.amazonaws.com"
   "eu-central-1"   "s3.eu-central-1.amazonaws.com"
   "eu-west-1"      "s3-eu-west-1.amazonaws.com"
   "sa-east-1"      "s3-sa-east-1.amazonaws.com"})

(defn remote-state [region bucket profile name]
  (data "terraform_remote_state" name
        {:backend "s3"
         :config {:bucket bucket
                  :profile profile
                  :encrypt true
                  :key (str name ".tfstate")
                  :region region
                  :endpoint (get s3-endpoints region)}}))

(defn remote-output-of [module name]
  (str "${" (clojure.string/join "." ["data"  "terraform_remote_state" module name]) "}"))
