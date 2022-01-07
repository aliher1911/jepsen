(defproject cockroachdb "0.2.0"
  :description "Jepsen testing for CockroachDB"
  :url "http://cockroachlabs.com/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [jepsen "0.1.19"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.1.3"]]
  :jvm-opts ["-Xmx12g"
             "-XX:+UseConcMarkSweepGC"
             "-XX:+UseParNewGC"
             "-XX:+CMSParallelRemarkEnabled"
             "-XX:+AggressiveOpts"
             "-XX:+UseFastAccessorMethods"
             "-XX:MaxInlineLevel=32"
             "-XX:MaxRecursiveInlineLevel=2"
             "-server"]
  :main jepsen.cockroach.runner
  :aot [jepsen.cockroach.runner
        clojure.tools.logging.impl])
