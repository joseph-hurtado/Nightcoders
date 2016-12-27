(ns nightcoders.build
  (:require [clojure.string :as str]
            [org.httpkit.server :refer [send! with-channel on-receive on-close]])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io PipedWriter PipedReader PrintWriter]))

(defonce state (atom {}))

(defn remove-returns [^String s]
  (str/escape s {\return ""}))

(defn pipe-into-console! [in-pipe channel]
  (let [ca (char-array 256)]
    (.start
      (Thread.
        (fn []
          (loop []
            (when-let [read (try (.read in-pipe ca)
                              (catch Exception _))]
              (when (pos? read)
                (let [s (remove-returns (String. ca 0 read))]
                  (send! channel s)
                  (Thread/sleep 100) ; prevent thread from being flooded
                  (recur))))))))))

(defn create-pipes []
  (let [out-pipe (PipedWriter.)
        in (LineNumberingPushbackReader. (PipedReader. out-pipe))
        pout (PipedWriter.)
        out (PrintWriter. pout)
        in-pipe (PipedReader. pout)]
    {:in in :out out :in-pipe in-pipe :out-pipe out-pipe}))

(defn start-repl-thread! [channel pipes]
  (let [{:keys [in-pipe out]} pipes]
    (pipe-into-console! in-pipe channel)
    (.start
      (Thread.
        (fn []
          (binding [*out* out
                    *err* out]
            (try
              (println "Hello, world!")
              (catch Exception e (some-> (.getMessage e) println))
              (finally (println "=== Finished ==="))))))))
  pipes)

(defn status-request [request user-id project-id]
  (with-channel request channel
    (on-close channel
      (fn [status]
        (when-let [{:keys [in-pipe]} (get @state channel)]
          (.close in-pipe))
        (swap! state update user-id dissoc project-id)))
    (on-receive channel
      (fn [text]
        (when-not (get @state channel)
          (->> (create-pipes)
               (start-repl-thread! channel)
               (swap! state assoc-in [user-id project-id])))))))

