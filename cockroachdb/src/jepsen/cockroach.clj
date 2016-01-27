(ns jepsen.cockroach
    "Tests for CockroachDB"
    (:require [clojure.tools.logging :refer :all]
            [clj-ssh.ssh :as ssh]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
             [core :as jepsen]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [model :as model]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.ubuntu :as ubuntu]))

(defn eval!
  "Evals a sql string from the command line."
  [s]
  (c/exec "/home/ubuntu/sql.sh" s))

(defn setup-db!
  "Set up the jepsen database in the cluster."
  [node]
  (eval! "drop database if exists jepsen;")
  (eval! "create database jepsen;")
  (eval! "create table jepsen.test (name char(1), val int, primary key (name));")
  (eval! "insert into jepsen.test values ('a', 0);")
  )

(defn stop!
  "Remove the jepsen database from the cluster."
  [node]
  (eval! "drop database if exists jepsen;"))

(def log-files
  ["/home/ubuntu/logs/cockroach.stderr"])

(defn str->int [str]
  (let [n (read-string str)]
    (if (number? n) n nil)))

(defn db
  "Sets up and tears down CockroachDB."
  [version]
  (reify db/DB
    (setup! [_ test node]

      (c/exec "/home/ubuntu/restart.sh")
      (jepsen/synchronize test)
      (if (= node (jepsen/primary test)) (setup-db! node))

      (info node "Setup complete")
      (Thread/sleep 1000))

    (teardown! [_ test node]

      (if (= node (jepsen/primary test)) (stop! node))
      (jepsen/synchronize test)
      (apply c/exec :truncate :-c :--size 0 log-files)
      )
    
    db/LogFiles
    (log-files [_ test node] log-files)))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn ssh-sql
  "Evals a sql string from the command line."
  [conn s]
  (ssh/ssh conn {:cmd (str "/home/ubuntu/sql.sh " s)}))

(defn client-ssh
  "A client for a single compare-and-set register, using sql cli over ssh"
  [conn]
  (reify client/Client
    (setup! [_ test node]
      (let [agent (ssh/ssh-agent {})
            host (name node)
            conn (ssh/session agent host {:username "ubuntu" :strict-host-key-checking :no})]
           (ssh/connect conn)
           (client-ssh conn)))

    (invoke! [this test op]
      (timeout 2000 (assoc op :type :info, :error :timeout)
               (case (:f op)
                 :read (let [res (ssh-sql conn "begin \"set transaction isolation level serializable\" \"select val from jepsen.test\"  commit")
                             out (str/split-lines (str/trim-newline (:out res)))]
                          (if (zero? (:exit res))
                            (assoc op :type :ok, :value (str->int (nth out 4)))
                            (assoc op :type :info, :error (str "sql error: " (str/join " " out)))))
                 :write (let [res (ssh-sql conn (str "begin \"set transaction isolation level serializable\" \"update jepsen.test set val=" (:value op) " where name='a'\" commit"))
                             out (str/split-lines (str/trim-newline (:out res)))]
                          (if (and (zero? (:exit res)) (= (last out) "OK"))
                            (assoc op :type :ok)
                            (assoc op :type :info, :error (str "sql error: " (str/join " " out)))))
                 :cas (let [[value' value] (:value op)
                            res (ssh-sql conn (str "begin "
                                                   "\"set transaction isolation level serializable\" "
                                                   "\"select val from jepsen.test\" "
                                                   "\"update jepsen.test set val=" value " where name='a' and val=" value' "\" "
                                                   "\"select val from jepsen.test\" "
                                                   " commit"))
                            out (str/split-lines (str/trim-newline (:out res)))]
                        (if (zero? (:exit res))
                          (assoc op :type (if (and (= (last out) "OK")
                                                   (= (str->int (nth out 4)) value')
                                                   (= (str->int (nth out 8)) value)) :ok :fail), :error (str "sql: " (str/join " " out)))
                          (assoc op :type :info, :error (str "sql error: " (str/join " " out)))))
                 ))
      )
      

    (teardown! [_ test]
    	   (ssh/disconnect conn))
    ))

(defn client-test
  [version]
  (assoc tests/noop-test
         :nodes [:n1l :n2l :n3l :n4l :n5l]
         :name    "cockroachdb"
         :os      ubuntu/os
         :db      (db version)
         :client  (client-ssh nil)
         :nemesis (nemesis/partition-random-halves)
         :generator (->> (gen/mix [r w cas])
                         (gen/stagger 1)
                         (gen/clients)
                         (gen/nemesis
                          (gen/seq (cycle [(gen/sleep 5)
                                           {:type :info, :f :start}
                                           (gen/sleep 5)
                                           {:type :info, :f :stop}])))
                         (gen/time-limit 30))
         :model   (model/cas-register 0)
         :checker (checker/compose
                   {:perf   (checker/perf)
                    :linear checker/linearizable})
))