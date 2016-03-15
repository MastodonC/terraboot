(defproject terraboot "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [cheshire "5.5.0"]
                 [stencil "0.5.0"]
                 [clj-yaml "0.4.0"]
                 [expectations "2.0.9"]
                 ]
  :jvm-opts ["-Xmx2048m"]
  :main terraboot.infra/-main)
