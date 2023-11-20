(ns puppetlabs.trapperkeeper.services.webserver.jetty10-websockets
  (:import (clojure.lang IFn)
           (org.eclipse.jetty.websocket.api WebSocketAdapter Session)
           (org.eclipse.jetty.websocket.server JettyWebSocketServlet JettyWebSocketServletFactory JettyWebSocketCreator JettyServerUpgradeRequest JettyServerUpgradeResponse)
           (java.security.cert X509Certificate)
           (java.time Duration)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.nio ByteBuffer))

  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.websocket-session :refer [WebSocketProtocol]]
            [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]))

(def WebsocketHandlers
  {(schema/optional-key :on-connect) IFn
   (schema/optional-key :on-error) IFn
   (schema/optional-key :on-close) IFn
   (schema/optional-key :on-text) IFn
   (schema/optional-key :on-bytes) IFn})

(defprotocol WebSocketSend
  (-send! [x ws] "How to encode content sent to the WebSocket clients"))

(extend-protocol WebSocketSend
  (Class/forName "[B")
  (-send! [ba ws]
    (-send! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-send! [bb ws]
    (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb)))

  String
  (-send! [s ws]
    (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s))))

(extend-protocol WebSocketProtocol
  WebSocketAdapter
  (send! [this msg]
    (try
      (-send! msg this)
      (catch Exception e
        (log/error e "Caught exception on sending message via websocket."))))
  (close!
    ([this]
     (try
       ;; Close this side
       (.. this (getSession) (close))
       ;; Then wait for remote side to close
       (.. this (awaitClosure))
       (catch Exception e
         (log/error e "Caught exception on attempting normal close of websocket."))))
    ([this code reason]
     (try
       (.. this (getSession) (close code reason))
       (.. this (awaitClosure))
       (catch Exception e
         (log/error e "Caught exception on attempting close of websocket with code {0} and reason {1}." code reason)))))
  (disconnect [this]
    (when-let [session (.getSession this)]
      (.disconnect session)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (ssl? [this]
    (.. this (getSession) (getUpgradeRequest) (isSecure)))
  (peer-certs [this]
    (.. this (getCerts)))
  (request-path [this]
    (.. this (getRequestPath)))
  (idle-timeout! [this ms]
    (let [duration-from-ms (Duration/ofMillis ms)]
      (.. this (getSession) (setIdleTimeout ^Duration duration-from-ms))))
  (connected? [this]
    (. this (isConnected))))

(definterface CertGetter
  (^Object getCerts [])
  (^String getRequestPath []))

(definterface ClosureLatchSyncer
  (^Object awaitClosure []))

(defn no-handler
  [event & args]
  (log/debug (i18n/trs "No handler defined for websocket event ''{0}'' with args: ''{1}''"
                       event args)))

(def client-count (atom 0))
(defn extract-CN-from-certs
  [x509certs]
  (when (not-empty x509certs)
    (.getSubjectX500Principal (first x509certs))))

(schema/defn ^:always-validate proxy-ws-adapter :- WebSocketAdapter
  [handlers :- WebsocketHandlers
   x509certs :- [X509Certificate]
   requestPath :- String
   closureLatch :- CountDownLatch]
  (let [client-id (swap! client-count inc)
        certname (extract-CN-from-certs x509certs)
        {:keys [on-connect on-error on-text on-close on-bytes]
         :or {on-connect (partial no-handler :on-connect)
              on-error   (partial no-handler :on-error)
              on-text    (partial no-handler :on-text)
              on-close   (partial no-handler :on-close)
              on-bytes   (partial no-handler :on-bytes)}} handlers]
    (proxy [WebSocketAdapter CertGetter ClosureLatchSyncer] []
      (onWebSocketConnect [^Session session]
        (log/tracef "%d on-connect certname:%s uri:%s" client-id certname requestPath)
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketConnect session))
        (let [on-connect-result (on-connect this)]
          (log/tracef "%d exiting on-connect" client-id)
          on-connect-result))
      (onWebSocketError [^Throwable e]
        (log/tracef e "%d on-error certname:%s uri:%s" client-id certname requestPath)
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketError e))
        (let [on-error-result (on-error this e)]
          (log/tracef "%d exiting on-error" client-id)
          on-error-result))
      (onWebSocketText [^String message]
        (log/tracef "%d on-text certname:%s uri:%s" client-id certname requestPath)
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketText message))
        (let [on-text-result (on-text this message)]
          (log/tracef "%d exiting on-text" client-id)
          on-text-result))
      (onWebSocketClose [statusCode ^String reason]
        (log/tracef "%d on-close certname:%s uri:%s" client-id certname requestPath)
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketClose statusCode reason))
        (.countDown closureLatch)
        (let [on-close-result (on-close this statusCode reason)]
          (log/tracef "%d exiting on-close" client-id)
          on-close-result))
      (onWebSocketBinary [^bytes payload offset len]
        (log/tracef "%d on-binary certname:%s uri:%s" client-id certname requestPath)
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketBinary payload offset len))
        (let [on-bytes-result (on-bytes this payload offset len)]
          (log/tracef "%d exiting on-binary" client-id)
          on-bytes-result))
      (awaitClosure []
        (try
          (let [timeout-in-seconds 30]
            (when-not (.await closureLatch timeout-in-seconds TimeUnit/SECONDS)
              (log/info (i18n/trs "{0} timed out after awaiting closure of websocket from remote for {1} seconds at request path {2}." client-id timeout-in-seconds requestPath))))
          (catch InterruptedException e
            (log/info e (i18n/trs "{0} thread was interrupted when awaiting closure of websocket from remote at request path {1}." client-id requestPath)))))
      (getCerts [] x509certs)
      (getRequestPath [] requestPath))))

(schema/defn ^:always-validate proxy-ws-creator :- JettyWebSocketCreator
  [handlers :- WebsocketHandlers]
  (reify JettyWebSocketCreator
    (createWebSocket [_this ^JettyServerUpgradeRequest req ^JettyServerUpgradeResponse _res]
      (let [x509certs (vec (.. req (getCertificates)))
            requestPath (.. req (getRequestPath))
            ;; A simple gate to synchronize closure on server and client.
            closureLatch (CountDownLatch. 1)]
        (proxy-ws-adapter handlers x509certs requestPath closureLatch)))))

(schema/defn JettyWebSocketServletInstance :- JettyWebSocketServlet
  [handlers]
  (proxy [JettyWebSocketServlet] []
    (configure [^JettyWebSocketServletFactory factory]
        (.setCreator factory (proxy-ws-creator handlers)))))
