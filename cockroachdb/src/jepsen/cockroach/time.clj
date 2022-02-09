(ns jepsen.cockroach.time
  "Overrides for time functions to workaround bugs in jepsen packages."
  (:require [clojure.tools.logging :refer :all]
            [jepsen
             [control :as c]]
            [jepsen.nemesis.time :as nt]
            [jepsen.os.debian :as debian]))

(defn install!
  "Install time manipulation utils"
  []
  (c/su
   (debian/install [:build-essential])
   (nt/compile-resource! "bumptime.c" "bumptime")
   (nt/compile-resource! "strobetime.c" "strobetime")))

(defn bump-time!
  "Adjust clocks"
  [delta]
  (c/su (c/exec "/opt/jepsen/bumptime" delta)))

(defn strobe-time!
  "Strobe time back and forth"
  [delta period duration]
  (c/su (c/exec "/opt/jepsen/strobetime" delta period duration)))

(def ntpserver "time.google.com")

(defn reset-clock!
  "Reset clock on this host. Logs output."
  []
  (info c/*host* "clock reset:" (c/su (c/exec :ntpdate :-b ntpserver))))

(defn reset-clocks!
  "Reset all clocks on all nodes in a test"
  [test]
  (c/with-test-nodes test (reset-clock!)))
