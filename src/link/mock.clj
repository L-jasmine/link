(ns link.mock
  (:require [link.core :refer :all])
  (:import [io.netty.util.concurrent Future]))

(deftype MockChannel [chid local-addr remote-addr msgs stopped?]
  LinkMessageChannel
  (id [this] chid)
  (send! [this msg]
    (swap! msgs conj msg))
  (send!* [this msg cb]
    (swap! msgs conj msg)
    (when cb
      (cb (reify Future
            (isSuccess [_] true)))))
  (close! [this]
    (reset! stopped? true))
  (valid? [this]
    (not @stopped?))
  (channel-addr [this] local-addr)
  (remote-addr [this] remote-addr)

  clojure.lang.IDeref
  (deref [this] @msgs))

(defn mock-channel [{local-addr :channel-addr
                     remote-addr :remote-addr
                     chid :channel-id}]
  (MockChannel. chid local-addr remote-addr (atom []) (atom false)))
