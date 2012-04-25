(ns link.codec
  (:refer-clojure :exclude [byte float double])
  (:import [java.nio ByteBuffer])
  (:import [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers])
  (:import [org.jboss.netty.channel
            Channels
            ChannelDownstreamHandler
            ChannelUpstreamHandler
            ChannelEvent
            MessageEvent
            ChannelHandlerContext]))

(defmacro defcodec [sym encoder-fn decoder-fn]
  `(defn ~sym [& options#]
     {:encoder (partial ~encoder-fn options#)
      :decoder (partial ~decoder-fn options#)}))

;; macro to improve codec readability
(defmacro encoder [args & body]
  `(fn ~args ~@body))

(defmacro decoder [args & body]
  `(fn ~args ~@body))

(defmacro primitive-codec [sname size writer-fn reader-fn]
  `(defcodec ~sname
     (encoder [_# data# ^ChannelBuffer buffer#]
              (. buffer# ~writer-fn data#)
              buffer#)
     (decoder [_# ^ChannelBuffer buffer#]
              (if (>= (.readableBytes buffer#) ~size)
                (. buffer# ~reader-fn)))))

(primitive-codec byte 1 writeByte readByte)
(primitive-codec int16 2 writeShort readShort)
(primitive-codec uint16 2 writeShort readUnsignedShort)
(primitive-codec int24 3 writeMedium readMedium)
(primitive-codec uint24 3 writeMedium readUnsignedMedium)
(primitive-codec int32 4 writeInt readInt)
(primitive-codec uint32 4 writeInt readUnsignedInt)
(primitive-codec int64 8 writeLong readLong)
(primitive-codec float 4 writeFloat readFloat)
(primitive-codec double 8 writeDouble readDouble)

(defn- find-delimiter [^ChannelBuffer src ^bytes delim]
  (loop [sindex (.readerIndex src) dindex 0]
    (if (= sindex (.writerIndex src))
      -1
      (if (= ^Byte (.getByte src sindex) ^Byte (aget delim dindex))
        (if (= dindex (- (alength delim) 1))
          (+ (- sindex (.readerIndex src)) 1)
          (recur (inc sindex) (inc dindex)))
        (recur (inc sindex) 0)))))

(defcodec string
  (encoder [options ^String data ^ChannelBuffer buffer]
           (let [{:keys [prefix encoding delimiter]} options
                 encoding (name encoding)
                 bytes (.getBytes (or data "") encoding)]
             (cond
              ;; length prefix string
              (nil? delimiter)
              (do
                ((:encoder prefix) (alength bytes) buffer)
                (.writeBytes buffer ^bytes bytes))
              ;; delimiter based string
              (nil? prefix)
              (do
                (.writeBytes buffer ^bytes bytes)
                (.writeBytes buffer ^bytes
                             (.getBytes ^String delimiter encoding)))))
           buffer)
  (decoder [options ^ChannelBuffer buffer]
           (let [{:keys [prefix encoding delimiter]} options
                 encoding (name encoding)]
             (cond
              ;; length prefix string
              (nil? delimiter)
              (do
                (if-let [byte-length ((:decoder prefix) buffer)]
                  (if-not (> byte-length (.readableBytes buffer))
                    (let [bytes (byte-array byte-length)]
                      (.readBytes buffer ^bytes bytes)
                      (String. bytes encoding)))))

              ;; delimiter based string
              (nil? prefix)
              (do
                (let [dbytes (.getBytes ^String delimiter encoding)
                      dlength (find-delimiter buffer dbytes)
                      slength (- dlength (alength ^bytes dbytes))
                      sbytes (byte-array slength)]
                  (.readBytes buffer ^bytes sbytes)
                  (String. sbytes encoding)))))))

(defcodec byte-block
  (encoder [options ^ByteBuffer data ^ChannelBuffer buffer]
           (let [{prefix :prefix} options
                 byte-length (if (nil? data) 0 (.remaining data))]
             ((:encoder prefix) byte-length buffer)
             (if-not (nil? data)
               (.writeBytes buffer ^ByteBuffer data))
             buffer))
  (decoder [options ^ChannelBuffer buffer]
           (let [{prefix :prefix} options
                 byte-length ((:decoder prefix) buffer)]
             (if-not (or (nil? byte-length)
                         (> byte-length (.readableBytes buffer)))
               (let [local-buffer (ByteBuffer/allocate byte-length)]
                 (.readBytes buffer ^ByteBuffer local-buffer)
                 (.rewind local-buffer)
                 local-buffer)))))

(def ^{:private true} reversed-map
  (memoize
   (fn [m]
     (apply hash-map (mapcat #(vector (val %) (key %)) m)))))

(defcodec enum
  (encoder [options data ^ChannelBuffer buffer]
           (let [[codec mapping] options
                 value (get mapping data)]
             ((:encoder codec) value buffer)))
  (decoder [options ^ChannelBuffer buffer]
           (let [[codec mapping] options
                 mapping (reversed-map mapping)
                 value ((:decoder codec) buffer)]
             (get mapping value))))

(defcodec header
  (encoder [options data ^ChannelBuffer buffer]
           (let [[enumer children] options
                 head (first data)
                 body (second data)
                 body-codec (get children head)]
             ((:encoder enumer) head buffer)
             ((:encoder body-codec) body buffer)
             buffer))
  (decoder [options ^ChannelBuffer buffer]
           (let [[enumer children] options
                 head ((:decoder enumer) buffer)
                 body (and head ;; body is nil if head is nil
                       ((:decoder (get children head)) buffer))]
             (if-not (nil? body)
               [head body]))))

(defcodec frame
  (encoder [options data ^ChannelBuffer buffer]
           (let [codecs options]
             (dorun (map #((:encoder %1) %2 buffer) codecs data))
             buffer))
  (decoder [options ^ChannelBuffer buffer]
           (let [codecs options]
             (loop [c codecs r []]
               (if (empty? c)
                 r
                 (if-let [r0 ((:decoder (first c)) buffer)] 
                   (recur (rest c) (conj r r0))))))))

(defn encode
  ([codec data] (encode codec data (ChannelBuffers/dynamicBuffer)))
  ([codec data ^ChannelBuffer buffer]
     ((:encoder codec) data buffer)))

(defn decode [codec ^ChannelBuffer buffer]
  ((:decoder codec) buffer))

(defn netty-encoder [codec]
  (reify ChannelDownstreamHandler
    (^void handleDownstream [this
                       ^ChannelHandlerContext ctx
                       ^ChannelEvent e]
      (if-not (instance? MessageEvent e)
        (.sendDownstream ctx e)
        (do
          (let [data (.getMessage ^MessageEvent e)
                buffer (encode codec data)]
            (Channels/write ctx (.getFuture e) buffer
                            (.getRemoteAddress ^MessageEvent e))))))))

(defn netty-decoder [codec]
  (let [cumulation (ChannelBuffers/dynamicBuffer)]
    (reify ChannelUpstreamHandler
      (^void handleUpstream [this
                             ^ChannelHandlerContext ctx
                             ^ChannelEvent e]
       (if-not (instance? MessageEvent e)
         (.sendUpstream ctx e)
         (let [in (.getMessage ^MessageEvent e)]
           (.writeBytes ^ChannelBuffer cumulation ^ChannelBuffer in)
           (loop []
             (when (.readable cumulation)
               (.markReaderIndex cumulation)
               (if-let [data (decode codec cumulation)]
                 (do
                   (Channels/fireMessageReceived
                    ctx data (.getRemoteAddress ^MessageEvent e))
                   (recur))
                 (do
                   (.resetReaderIndex cumulation)))))
           (.discardReadBytes cumulation)))))))

