(ns repl-tooling.repl-client.connection-test
  (:require [clojure.test :refer [testing async is]]
            [devcards.core :as cards]
            [check.core :refer [check]]
            [check.async :refer [async-test]]
            [clojure.core.async :as async]
            [repl-tooling.repl-client.connection :as c]
            [reagent.core :as r]))

(set! cards/test-timeout 8000)
(defonce state (atom nil))
(defn- buffer [] (some-> @state :buffer deref))

(defn check-string [regexp]
  (async/go-loop [n 0]
    (when (< n 10)
      (let [s (peek (buffer))]
        (if (re-find regexp (str s))
          s
          (do
            (async/<! (async/timeout 100))
            (recur (inc n))))))))

(cards/deftest buffer-treatment
  (let [buffer (atom [])
        lines (async/chan)
        frags (async/chan)]
    (async-test "treating buffer info" {:teardown (do
                                                    (async/close! lines)
                                                    (async/close! frags))}
      (c/treat-buffer! buffer #(async/put! lines (str %)) #(async/put! frags (str %)))

      (testing "emits line"
        (swap! buffer conj "foo\n")
        (check (async/<! lines) =expect=> "foo")
        (check (async/<! frags) =expect=> "foo\n")
        (check @buffer =expect=> []))

      (testing "emits complex line"
        (swap! buffer conj "foo")
        (swap! buffer conj "bar")
        (swap! buffer conj "b\nbaz")
        (check (async/<! lines) =expect=> "foobarb")
        (check @buffer =expect=> ["baz"]))

      (testing "emits fragments"
        (check (async/<! frags) =expect=> "foobarb\n")
        (check (async/<! frags) =expect=> "baz")
        (check @buffer =expect=> []))

      (testing "emits lines of already emitted frags"
        (swap! buffer conj "aar\n")
        (check (async/<! lines) =expect=> "bazaar")
        (check (async/<! frags) =expect=> "aar\n"))

      (testing "emits nil when closed connection"
        (swap! buffer conj :closed)
        (check (async/<! frags) =expect=> "")
        (check (async/<! lines) =expect=> "")))))

(cards/deftest eval-cycle
  (async done
    (let [buffer (atom [])
          output (async/chan)
          results (async/chan)
          control (c/treat-buffer! buffer identity identity)]

      (async/go
       (c/prepare-evals control
                        #(async/put! output (or % :closed))
                        #(async/put! results (or % :closed)))

       (testing "captures the result of output"
         (swap! buffer conj ":foobar")
         (check (async/<! output) =expect=> ":foobar"))

       (testing "captures results of simple evaluations"
         (swap! control update :pending-evals conj 'id01)
         (swap! buffer conj "[tooling$eval-res id01 \":foo\"]")
         (check (async/<! results) =expect=> '[id01 ":foo"])

         (swap! buffer conj "[tooling$eval-res id01 \":foo\"]")
         (check (async/<! output) =expect=> "[tooling$eval-res id01 \":foo\"]"))

       (testing "captures results of results mixed with stdout"
         (swap! control update :pending-evals conj 'id01)
         (swap! buffer conj "lol[tooling$eval-res id01 \":foo\"]")
         (check (async/<! results) =expect=> '[id01 ":foo"])
         (check (async/<! output) =expect=> "lol")

         (swap! control update :pending-evals conj 'id01)
         (swap! buffer conj "lol[tooling$eval-res id01 \":foo\"]bar")
         (check (async/<! results) =expect=> '[id01 ":foo"])
         (check (async/<! output) =expect=> "lol")
         (check (async/<! output) =expect=> "bar"))

       (testing "ignores prompt after a result"
         (swap! control assoc :ignore-prompt true)
         (swap! control update :pending-evals conj 'id01)
         (swap! buffer conj "lol[tooling$eval-res id01 \":foo\"]\nuser.cljs=> ")
         (check (async/<! results) =expect=> '[id01 ":foo"])
         (check (async/<! output) =expect=> "lol")

         (swap! control update :pending-evals conj 'id01)
         (swap! buffer conj "[tooling$eval-res id01 \":foo\"]user.cljs=> bar")
         (check (async/<! results) =expect=> '[id01 ":foo"])
         (check (async/<! output) =expect=> "bar"))

       (testing "captures output in different fragments"
         (swap! control update :pending-evals conj 'id01)
         (swap! buffer conj "[tooling$eval-res id01 \"[\n")
         (swap! buffer conj "1 2\n")
         (swap! buffer conj "]\"]")
         (check (async/<! results) =expect=> '[id01 "[\n1 2\n]"]))

       (async/close! output)
       (async/close! results)
       (done)))))

(cards/defcard-rg buffers
  (fn [buffer]
    [:div (pr-str @buffer)])
  (some-> @state :buffer)
  {:watch-atom true})
