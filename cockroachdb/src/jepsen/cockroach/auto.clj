(ns jepsen.cockroach.auto
  "Cockroach automation functions, for starting, stopping, etc."
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen.util :as util]
            [jepsen [core :as jepsen]
                    [control :as c :refer [|]]]
            [jepsen.nemesis.time :as nt]
            [jepsen.cockroach.time :as ct]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian])
  (:import (java.io File)))

(def cockroach-user "User to run cockroachdb as" "cockroach")

(def tcpdump "Local path to tcpdump binary" "/usr/sbin/tcpdump")

(def jdbc-mode
  "Possible values:
  :pg-local  Send the test SQL to a PostgreSQL database.
  :cdb-local  Send the test SQL to a preconfigured local CockroachDB instance.
  :cdb-cluster Send the test SQL to the CockroachDB cluster set up by the framework."
  :cdb-cluster)

;; CockroachDB user and db name for jdbc-mode = :cdb-*
(def db-user "root")
(def db-passwd "dummy")
(def dbname "jepsen") ; will get created automatically

;; Ports
(def db-port 26257)
(def http-port 8080)

;; Paths
(def working-path "Home directory for cockroach setup" "/var/lib/cockroach/store")
(def cockroach "Cockroach binary" (str working-path "/cockroach"))
(def store-path "Cockroach data dir" (str working-path "/cockroach-data"))
(def pidfile "Cockroach PID file" (str working-path "/pid"))

; Logs
(def log-path "Log directory" (str working-path "/logs"))
(def verlog "Version log file" (str log-path "/version.txt"))
(def pcaplog "pcap log file" (str log-path "/trace.pcap"))
(def errlog (str log-path "/cockroach.stderr"))
(def log-files (if (= jdbc-mode :cdb-cluster) [errlog verlog pcaplog] []))

(def insecure
  "Whether to start the CockroachDB cluster in insecure mode (SSL disabled). May be useful to capture the network traffic between client and server."
  true)

;; Extra command-line arguments to give to `cockroach start`
(def cockroach-start-arguments
  (concat [:start
           :--port      db-port
           :--http-port http-port
           ;; ... other arguments here ...
           ]
          (if insecure [:--insecure] [])))

;; Extra command-line arguments to give to `cockroach init`
(def cockroach-init-arguments
  (concat [:init
           ;; ... other arguments here ...
           ]
          (if insecure [:--insecure] [])))

(defn control-addr
  "Address of the Jepsen control node, as seen by the local node. Used to
  filter packet captures."
  []
  ; We drop any sudo binding here to make sure we aren't looking at a subshell
  (let [line (binding [c/*sudo* nil]
               (c/exec :env | :grep "SSH_CLIENT"))
        matches (re-find #"SSH_CLIENT=(.+?)\s" line)]
    (assert matches)
    (matches 1)))

(defn packet-capture!
  "Starts a packet capture."
  [node]
  (info node "Starting packet capture (filtering on" (control-addr) ")...")
  (c/su (c/exec :start-stop-daemon
                :--start :--background
                :--exec tcpdump
                :--
                :-w pcaplog :host (control-addr)
                :and :port db-port)))

(defn save-version!
  "Logs cockroach version to a local file."
  [node]
  (c/sudo cockroach-user
          (c/exec cockroach :version :> verlog (c/lit "2>&1"))))

(defn wrap-env
  [env cmd]
  ["env" env cmd])

(defmacro csql! [& body]
  "Execute SQL statements using the cockroach sql CLI."
  `(c/cd working-path
         (c/exec
          (concat
           [cockroach :sql]
           (if insecure [:--insecure] nil)
           [:-e ~@body]
           [:>> errlog (c/lit "2>&1")]))))

(defn set-replication-zone!
  "Sets the replication zone on the given node."
  [zone config]
  (csql! (str
    "alter range " zone " configure zone using "
    (str/join ", "
      (map (fn [[k v]] (str (name k) " = " v)) config)))))

(defn install!
  "Installs CockroachDB on the given node. Test should include a :tarball url
  the tarball."
  [test node]
  (c/su
   (debian/install [:tcpdump :ntpdate])
   (cu/ensure-user! cockroach-user)
   (cu/install-archive! (:tarball test) working-path false)
   (c/exec :mkdir :-p working-path)
   (c/exec :mkdir :-p log-path)
   (c/exec :chown :-R (str cockroach-user ":" cockroach-user) working-path)
   (ct/install!))
  (nt/install!)
  (info node "Cockroach installed"))

(defn cockroach-start-cmdline
  "Construct the command line to start a CockroachDB node."
  [& extra-args]
  (concat
   [;;:env
    ;;:COCKROACH_TIME_UNTIL_STORE_DEAD=5s
    :start-stop-daemon
    :--start :--background
    :--make-pidfile
    :--remove-pidfile
    :--pidfile pidfile
    :--no-close
    :--chuid cockroach-user
    :--chdir working-path
    :--exec (c/expand-path cockroach)
    :--]
   cockroach-start-arguments
   extra-args
   [:--logtostderr :>> errlog (c/lit "2>&1")]))

(defn cockroach-init-cmdline
  "Construct the command line to initialize a CockroachDB cluster."
  []
  (concat
   [(c/expand-path cockroach)]
   cockroach-init-arguments))

(defn startcmd
  "The command to start cockroach for a given test"
  [test node]
  (let [join [(->> (:nodes test)
                     (remove #{node})
                     (map name)
                     (str/join ",")
                     (str "--join="))]]
    (wrap-env [(str "COCKROACH_LINEARIZABLE="
                    (if (:linearizable test) "true" "false"))
               (str "COCKROACH_MAX_OFFSET=" "250ms")]
              (cockroach-start-cmdline join (str "--advertise-addr=" node)))))

(defn start!
  "Start cockroachdb on node."
  [test node]
  (c/sudo cockroach-user
          (if (not= "" (try
                         (c/exec :pgrep :cockroach)
                         (catch RuntimeException e "")))
            (info node "Cockroach already running.")
            (do (info node "Starting CockroachDB...")
                (c/trace (c/exec (startcmd test node)))
                (info node "Cockroach started"))))
  :started)

(defn initcmd
  "The command to initialize cockroach for a given test"
  []
  (cockroach-init-cmdline))

(defn init!
  "Initialize cockroachdb cluster"
  [node]
  (c/sudo cockroach-user
          (do (info node "Initializing CockroachDB...")
              (c/trace (c/exec (initcmd)))
              (info node "Cockroach initialized")))
  :initialized)

(defn kill!
  "Kills cockroach on node."
  [test node]
  (util/meh (c/su (c/exec :killall :-9 :cockroach)))
  (info node "Cockroach killed.")
  :killed)
