/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.rpc.netty

import java.io._
import java.net.{InetSocketAddress, URI}
import java.nio.ByteBuffer
import java.nio.channels.{Pipe, ReadableByteChannel, WritableByteChannel}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.Nullable

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.{DynamicVariable, Failure, Success, Try}
import scala.util.control.NonFatal

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.internal.Logging
import org.apache.spark.network.TransportContext
import org.apache.spark.network.client._
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.network.sasl.{SaslClientBootstrap, SaslServerBootstrap}
import org.apache.spark.network.server._
import org.apache.spark.rpc._
import org.apache.spark.serializer.{JavaSerializer, JavaSerializerInstance}
import org.apache.spark.util.{ThreadUtils, Utils}

private[netty] class NettyRpcEnv(
    val conf: SparkConf,
    javaSerializerInstance: JavaSerializerInstance,
    host: String,
    securityManager: SecurityManager) extends RpcEnv(conf) with Logging {

  // TransportConf是RPC框架中的配置类，由于RPC环境RpcEnv的底层也依赖于数据总线，因此需要创建TransportConf
  private[netty] val transportConf = SparkTransportConf.fromSparkConf(
    conf.clone.set("spark.rpc.io.numConnectionsPerPeer", "1"),
    "rpc",
    conf.getInt("spark.rpc.io.threads", 0))

  private val dispatcher: Dispatcher = new Dispatcher(this)

  // NettyStreamManager用于提供NettyRpcEnv的文件流服务
  private val streamManager = new NettyStreamManager(this)

  // TransportContext是NettyRpcEnv提供服务端与客户端能力的前提
  private val transportContext = new TransportContext(transportConf,
    new NettyRpcHandler(dispatcher, this, streamManager))


  private def createClientBootstraps(): java.util.List[TransportClientBootstrap] = {
    if (securityManager.isAuthenticationEnabled()) {
      java.util.Arrays.asList(new SaslClientBootstrap(transportConf, "", securityManager,
        securityManager.isSaslEncryptionEnabled()))
    } else {
      java.util.Collections.emptyList[TransportClientBootstrap]
    }
  }

  // clientFactory用于常规的发送请求和接受响应
  private val clientFactory = transportContext.createClientFactory(createClientBootstraps())

  /**
   * A separate client factory for file downloads. This avoids using the same RPC handler as
   * the main RPC context, so that events caused by these clients are kept isolated from the
   * main RPC traffic.
   *
   * It also allows for different configuration of certain properties, such as the number of
   * connections per peer.
   */
  // fileDownloadFactory则用于文件下载，由于RpcEnv本身并不需要从远端下载文件，所以这里只声明了变量
  // fileDownloadFactory，并未进一步对其初始化。
  @volatile private var fileDownloadFactory: TransportClientFactory = _

  // timeoutScheduler用于处理请求超时的调度器。timeoutScheduler的类型实际是ScheduledExecutorService，比起使用Timer组件，
  // ScheduledExecutorService更加稳定，比如线程挂掉后，ScheduledExecuctorService会重启一个新的线程定时检查请求是否超时。
  val timeoutScheduler = ThreadUtils.newDaemonSingleThreadScheduledExecutor("netty-rpc-env-timeout")

  // clientConnectionExecutor一个用于异步处理TransportClientFactory.createClient方法调用的线程池。这个线程池的大小默认为64
  // 可以使用spark.rpc.connect.threads属性进行配置

  // Because TransportClientFactory.createClient is blocking, we need to run it in this thread pool
  // to implement non-blocking send/ask.
  // TODO: a non-blocking TransportClientFactory.createClient in future
  private[netty] val clientConnectionExecutor = ThreadUtils.newDaemonCachedThreadPool(
    "netty-rpc-connection",
    conf.getInt("spark.rpc.connect.threads", 64))

  // TransportServer在此时并未实例化，那么他在何时实例化呢？在之前我们介绍过启动RpcEnv的偏函数startNettyRpcEnv,
  // startNettyRpcEnv将负责回调NettyRpcEnv的startServer方法
  @volatile private var server: TransportServer = _

  private val stopped = new AtomicBoolean(false)

  /**
   * A map for [[RpcAddress]] and [[Outbox]]. When we are connecting to a remote [[RpcAddress]],
   * we just put messages to its [[Outbox]] to implement a non-blocking `send` method.
   */
    // RpcAddress与Outbox的映射关系缓存。每次远端发送请求时，此请求消息首先放入此远端地址对应的Outbox，然后使用线程异步发送
  private val outboxes = new ConcurrentHashMap[RpcAddress, Outbox]()

  /**
   * Remove the address's Outbox and stop it.
   */
  private[netty] def removeOutbox(address: RpcAddress): Unit = {
    val outbox = outboxes.remove(address)
    if (outbox != null) {
      outbox.stop()
    }
  }

  // TransportServer初始化并且启动后，可以利用NettyRpcHandler和NettyStreamManager对外提供服务
  def startServer(bindAddress: String, port: Int): Unit = {
    val bootstraps: java.util.List[TransportServerBootstrap] =
      if (securityManager.isAuthenticationEnabled()) {
        java.util.Arrays.asList(new SaslServerBootstrap(transportConf, securityManager))
      } else {
        java.util.Collections.emptyList()
      }
    // 创建TransportServer
    server = transportContext.createServer(bindAddress, port, bootstraps)
    // 向Dispatcher注册RpcEndpointVerifier。RpcEndpointVerifier用于校验指定名称的RpcEndpoint是否存在。
    // RpcEndpointVerifier在Dispatcher中注册名为endpoint-verifier，
    dispatcher.registerRpcEndpoint(
      RpcEndpointVerifier.NAME, new RpcEndpointVerifier(this, dispatcher))
  }

  @Nullable
  override lazy val address: RpcAddress = {
    if (server != null) RpcAddress(host, server.getPort()) else null
  }

  override def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef = {
    dispatcher.registerRpcEndpoint(name, endpoint)
  }

  // 向远端NettyRpcEnv询问指定名称的RpcEndpoint的NettyRpcEndpointRef
  def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef] = {
    val addr = RpcEndpointAddress(uri)
    val endpointRef = new NettyRpcEndpointRef(conf, addr, this)

    // 向远端NettyRpcEnv中的RpcEndpointVerifier发送RpcEndpointVerifier.CheckExistence(endpointRef.name)消息，
    // 查看endpointRef.name是否存在
    val verifier = new NettyRpcEndpointRef(
      conf, RpcEndpointAddress(addr.rpcAddress, RpcEndpointVerifier.NAME), this)
    verifier.ask[Boolean](RpcEndpointVerifier.CheckExistence(endpointRef.name)).flatMap { find =>
      if (find) {
        Future.successful(endpointRef)
      } else {
        Future.failed(new RpcEndpointNotFoundException(uri))
      }
    }(ThreadUtils.sameThread)
  }

  // 调用dispatcher.stop方法
  override def stop(endpointRef: RpcEndpointRef): Unit = {
    require(endpointRef.isInstanceOf[NettyRpcEndpointRef])
    dispatcher.stop(endpointRef)
  }

  // postToOutbox用于向远端节点上的RpcEndpoint发送消息
  private def postToOutbox(receiver: NettyRpcEndpointRef, message: OutboxMessage): Unit = {
    // 如果NettyRpcEndpointRef中的TransportClient不为空，则直接调用OutboxMessage的sendWith方法
    if (receiver.client != null) {
      message.sendWith(receiver.client)
    } else {
      require(receiver.address != null,
        "Cannot send message to client endpoint with no listen address.")
      // 获取NettyRpcEndpointRef的远端RpcEndpoint地址所对应的的Outbox
      val targetOutbox = {
        val outbox = outboxes.get(receiver.address)
        if (outbox == null) {
          // 如果outboxes中没有相应的Outbox,则需要新建Outbox并放入outboxes缓存中
          val newOutbox = new Outbox(this, receiver.address)
          val oldOutbox = outboxes.putIfAbsent(receiver.address, newOutbox)
          if (oldOutbox == null) {
            newOutbox
          } else {
            oldOutbox
          }
        } else {
          outbox
        }
      }
      if (stopped.get) {
        // 如果当前NettyRpcEnv已经处于停止状态，则将上面得到的outbox从outboxs中移除,并调用Outbox的stop方法停止Outbox
        // It's possible that we put `targetOutbox` after stopping. So we need to clean it.
        outboxes.remove(receiver.address)
        targetOutbox.stop()
      } else {
        // 如果当前NettyRpcEnv还未停止，则调用outbox.send方法发送消息
        targetOutbox.send(message)
      }
    }
  }

  // 和ask差不多，只是少了回调
  private[netty] def send(message: RequestMessage): Unit = {
    val remoteAddr = message.receiver.address
    if (remoteAddr == address) {
      // Message to a local RPC endpoint.
      try {
        dispatcher.postOneWayMessage(message)
      } catch {
        case e: RpcEnvStoppedException => logWarning(e.getMessage)
      }
    } else {
      // Message to a remote RPC endpoint.
      postToOutbox(message.receiver, OneWayOutboxMessage(serialize(message)))
    }
  }

  private[netty] def createClient(address: RpcAddress): TransportClient = {
    clientFactory.createClient(address.host, address.port)
  }

  private[netty] def ask[T: ClassTag](message: RequestMessage, timeout: RpcTimeout): Future[T] = {
    val promise = Promise[Any]()
    val remoteAddr = message.receiver.address

    def onFailure(e: Throwable): Unit = {
      if (!promise.tryFailure(e)) {
        logWarning(s"Ignored failure: $e")
      }
    }

    def onSuccess(reply: Any): Unit = reply match {
      case RpcFailure(e) => onFailure(e)
      case rpcReply =>
        if (!promise.trySuccess(rpcReply)) {
          logWarning(s"Ignored message: $reply")
        }
    }

    try {
      // 如果请求消息的接受者的地址与当前NettyRpcEnv的地址相同（则说明处理请求的RpcEndpoint位于本地的NettyRpcEnv中），
      // 那么新建Promise对象，并且给Promise的future设置完成时的回到函数，
      if (remoteAddr == address) {
        val p = Promise[Any]()
        p.future.onComplete {
          case Success(response) => onSuccess(response)
          case Failure(e) => onFailure(e)
        }(ThreadUtils.sameThread)

        // 发送消息最终通过调用本地Dispatcher的poseLocalMessage方法
        dispatcher.postLocalMessage(message, p)
      } else {
        // 如果请求消息的接收者的地址与当前NettyRpcEnv的地址不同（则说明处理请求的RpcEndpoint位于其他节点的NettyRpcEnv中），
        // 那么将message序列化，并与onFailure、onSuccess方法一道封装为RpcOutboxMessage类型的消息。
        val rpcMessage = RpcOutboxMessage(serialize(message),
          onFailure,
          (client, response) => onSuccess(deserialize[Any](client, response)))

        // 最后调用postToOutBox将消息投递出去
        postToOutbox(message.receiver, rpcMessage)
        promise.future.onFailure {
          case _: TimeoutException => rpcMessage.onTimeout()
          case _ =>
        }(ThreadUtils.sameThread)
      }

      // 使用timeoutScheduler设置一个定时器，用于超时处理。
      val timeoutCancelable = timeoutScheduler.schedule(new Runnable {
        override def run(): Unit = {
          onFailure(new TimeoutException(s"Cannot receive any reply in ${timeout.duration}"))
        }
      }, timeout.duration.toNanos, TimeUnit.NANOSECONDS)
      promise.future.onComplete { v =>
        timeoutCancelable.cancel(true)
      }(ThreadUtils.sameThread)
    } catch {
      case NonFatal(e) =>
        onFailure(e)
    }
    promise.future.mapTo[T].recover(timeout.addMessageIfTimeout)(ThreadUtils.sameThread)
  }

  private[netty] def serialize(content: Any): ByteBuffer = {
    javaSerializerInstance.serialize(content)
  }

  private[netty] def deserialize[T: ClassTag](client: TransportClient, bytes: ByteBuffer): T = {
    NettyRpcEnv.currentClient.withValue(client) {
      deserialize { () =>
        javaSerializerInstance.deserialize[T](bytes)
      }
    }
  }

  override def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef = {
    dispatcher.getRpcEndpointRef(endpoint)
  }

  override def shutdown(): Unit = {
    cleanup()
  }

  override def awaitTermination(): Unit = {
    dispatcher.awaitTermination()
  }

  private def cleanup(): Unit = {
    if (!stopped.compareAndSet(false, true)) {
      return
    }

    val iter = outboxes.values().iterator()
    while (iter.hasNext()) {
      val outbox = iter.next()
      outboxes.remove(outbox.address)
      outbox.stop()
    }
    if (timeoutScheduler != null) {
      timeoutScheduler.shutdownNow()
    }
    if (dispatcher != null) {
      dispatcher.stop()
    }
    if (server != null) {
      server.close()
    }
    if (clientFactory != null) {
      clientFactory.close()
    }
    if (clientConnectionExecutor != null) {
      clientConnectionExecutor.shutdownNow()
    }
    if (fileDownloadFactory != null) {
      fileDownloadFactory.close()
    }
  }

  override def deserialize[T](deserializationAction: () => T): T = {
    NettyRpcEnv.currentEnv.withValue(this) {
      deserializationAction()
    }
  }

  override def fileServer: RpcEnvFileServer = streamManager

  override def openChannel(uri: String): ReadableByteChannel = {
    val parsedUri = new URI(uri)
    require(parsedUri.getHost() != null, "Host name must be defined.")
    require(parsedUri.getPort() > 0, "Port must be defined.")
    require(parsedUri.getPath() != null && parsedUri.getPath().nonEmpty, "Path must be defined.")

    val pipe = Pipe.open()
    val source = new FileDownloadChannel(pipe.source())
    try {
      val client = downloadClient(parsedUri.getHost(), parsedUri.getPort())
      val callback = new FileDownloadCallback(pipe.sink(), source, client)
      client.stream(parsedUri.getPath(), callback)
    } catch {
      case e: Exception =>
        pipe.sink().close()
        source.close()
        throw e
    }

    source
  }

  // 需要下载文件的RpcEnv会调用downloadClient方法创建TransportClientFactory,并用此TransportClientFactory创建下载
  // 所需的传输客户端TransportClient
  private def downloadClient(host: String, port: Int): TransportClient = {
    if (fileDownloadFactory == null) synchronized {
      if (fileDownloadFactory == null) {
        val module = "files"
        val prefix = "spark.rpc.io."
        val clone = conf.clone()

        // Copy any RPC configuration that is not overridden in the spark.files namespace.
        conf.getAll.foreach { case (key, value) =>
          if (key.startsWith(prefix)) {
            val opt = key.substring(prefix.length())
            clone.setIfMissing(s"spark.$module.io.$opt", value)
          }
        }

        val ioThreads = clone.getInt("spark.files.io.threads", 1)
        val downloadConf = SparkTransportConf.fromSparkConf(clone, module, ioThreads)
        val downloadContext = new TransportContext(downloadConf, new NoOpRpcHandler(), true)
        fileDownloadFactory = downloadContext.createClientFactory(createClientBootstraps())
      }
    }
    fileDownloadFactory.createClient(host, port)
  }

  private class FileDownloadChannel(source: ReadableByteChannel) extends ReadableByteChannel {

    @volatile private var error: Throwable = _

    def setError(e: Throwable): Unit = {
      error = e
      source.close()
    }

    override def read(dst: ByteBuffer): Int = {
      Try(source.read(dst)) match {
        case Success(bytesRead) => bytesRead
        case Failure(readErr) =>
          if (error != null) {
            throw error
          } else {
            throw readErr
          }
      }
    }

    override def close(): Unit = source.close()

    override def isOpen(): Boolean = source.isOpen()

  }

  private class FileDownloadCallback(
      sink: WritableByteChannel,
      source: FileDownloadChannel,
      client: TransportClient) extends StreamCallback {

    override def onData(streamId: String, buf: ByteBuffer): Unit = {
      while (buf.remaining() > 0) {
        sink.write(buf)
      }
    }

    override def onComplete(streamId: String): Unit = {
      sink.close()
    }

    override def onFailure(streamId: String, cause: Throwable): Unit = {
      logDebug(s"Error downloading stream $streamId.", cause)
      source.setError(cause)
      sink.close()
    }

  }
}

private[netty] object NettyRpcEnv extends Logging {
  /**
   * When deserializing the [[NettyRpcEndpointRef]], it needs a reference to [[NettyRpcEnv]].
   * Use `currentEnv` to wrap the deserialization codes. E.g.,
   *
   * {{{
   *   NettyRpcEnv.currentEnv.withValue(this) {
   *     your deserialization codes
   *   }
   * }}}
   */
  private[netty] val currentEnv = new DynamicVariable[NettyRpcEnv](null)

  /**
   * Similar to `currentEnv`, this variable references the client instance associated with an
   * RPC, in case it's needed to find out the remote address during deserialization.
   */
  private[netty] val currentClient = new DynamicVariable[TransportClient](null)

}

private[rpc] class NettyRpcEnvFactory extends RpcEnvFactory with Logging {

  def create(config: RpcEnvConfig): RpcEnv = {
    val sparkConf = config.conf
    // Use JavaSerializerInstance in multiple threads is safe. However, if we plan to support
    // KryoSerializer in future, we have to use ThreadLocal to store SerializerInstance

    // 创建javaSerializerInstance。此实例将用于RPC传输对象的序列化
    val javaSerializerInstance =
      new JavaSerializer(sparkConf).newInstance().asInstanceOf[JavaSerializerInstance]

    // 创建NettyRpcEnv.创建NettyRpcEnv其实就是对内部各个子组件TransportConf、Dispatcher、TransportContext、
    // TransportClientFactory、TransportServer的实例化过程。
    val nettyEnv =
      new NettyRpcEnv(sparkConf, javaSerializerInstance, config.advertiseAddress,
        config.securityManager)

    // 启动NettyRpcEnv.
    if (!config.clientMode) {
      // 这一步首先定义一个偏函数startNettyRpcEnv,其函数实际为执行NettyRpcEnv的startServer方法
      val startNettyRpcEnv: Int => (NettyRpcEnv, Int) = { actualPort =>
        nettyEnv.startServer(config.bindAddress, actualPort)
        (nettyEnv, nettyEnv.address.port)
      }

      // 最后再启动NettyRpcEnv之后返回NettyRpcEnv及服务最终使用的端口。
      try {
        Utils.startServiceOnPort(config.port, startNettyRpcEnv, sparkConf, config.name)._1
      } catch {
        case NonFatal(e) =>
          nettyEnv.shutdown()
          throw e
      }
    }
    nettyEnv
  }
}

/**
 * The NettyRpcEnv version of RpcEndpointRef.
 *
 * This class behaves differently depending on where it's created. On the node that "owns" the
 * RpcEndpoint, it's a simple wrapper around the RpcEndpointAddress instance.
 *
 * On other machines that receive a serialized version of the reference, the behavior changes. The
 * instance will keep track of the TransportClient that sent the reference, so that messages
 * to the endpoint are sent over the client connection, instead of needing a new connection to
 * be opened.
 *
 * The RpcAddress of this ref can be null; what that means is that the ref can only be used through
 * a client connection, since the process hosting the endpoint is not listening for incoming
 * connections. These refs should not be shared with 3rd parties, since they will not be able to
 * send messages to the endpoint.A server that responds to requests submitted by the [[RestSubmissionClient]].
  * This is intended to be embedded in the standalone Master and used in cluster mode only.
 *
 * @param conf Spark configuration.
 * @param endpointAddress The address where the endpoint is listening.
 * @param nettyEnv The RpcEnv associated with this ref.
 */
private[netty] class NettyRpcEndpointRef(
    @transient private val conf: SparkConf,
    endpointAddress: RpcEndpointAddress,
    @transient @volatile private var nettyEnv: NettyRpcEnv)
  extends RpcEndpointRef(conf) with Serializable with Logging {

  // 类型为TransportClient。NettyRpcEndpointRef将利用此TransportClient向远端RpcEndpoint发送请求
  @transient @volatile var client: TransportClient = _

  // 远端RpcEndpoint的地址RpcEndpointAddress
  private val _address = if (endpointAddress.rpcAddress != null) endpointAddress else null
  // 远端RpcEndpoint的名称
  private val _name = endpointAddress.name

  override def address: RpcAddress = if (_address != null) _address.rpcAddress else null

  private def readObject(in: ObjectInputStream): Unit = {
    in.defaultReadObject()
    nettyEnv = NettyRpcEnv.currentEnv.value
    client = NettyRpcEnv.currentClient.value
  }

  private def writeObject(out: ObjectOutputStream): Unit = {
    out.defaultWriteObject()
  }

  override def name: String = _name

  // 首先将message封装为RequestMessage，然后调用NettyRpcEnv的ask方法
  override def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T] = {
    nettyEnv.ask(RequestMessage(nettyEnv.address, this, message), timeout)
  }

  //  首先将message封装为RequestMessage，然后调用NettyRpcEnv的send方法
  override def send(message: Any): Unit = {
    require(message != null, "Message is null")
    nettyEnv.send(RequestMessage(nettyEnv.address, this, message))
  }

  override def toString: String = s"NettyRpcEndpointRef(${_address})"

  def toURI: URI = new URI(_address.toString)

  final override def equals(that: Any): Boolean = that match {
    case other: NettyRpcEndpointRef => _address == other._address
    case _ => false
  }

  final override def hashCode(): Int = if (_address == null) 0 else _address.hashCode()
}

/**
 * The message that is sent from the sender to the receiver.
 */
private[netty] case class RequestMessage(
    senderAddress: RpcAddress, receiver: NettyRpcEndpointRef, content: Any)

/**
 * A response that indicates some failure happens in the receiver side.
 */
private[netty] case class RpcFailure(e: Throwable)

/**
 * Dispatches incoming RPCs to registered endpoints.
 *
 * The handler keeps track of all client instances that communicate with it, so that the RpcEnv
 * knows which `TransportClient` instance to use when sending RPCs to a client endpoint (i.e.,
 * one that is not listening for incoming connections, but rather needs to be contacted via the
 * client socket).
 *
 * Events are sent on a per-connection basis, so if a client opens multiple connections to the
 * RpcEnv, multiple connection / disconnection events will be created for that client (albeit
 * with different `RpcAddress` information).
 */
private[netty] class NettyRpcHandler(
    dispatcher: Dispatcher,
    nettyEnv: NettyRpcEnv,
    streamManager: StreamManager) extends RpcHandler with Logging {

  // A variable to track the remote RpcEnv addresses of all clients
  private val remoteAddresses = new ConcurrentHashMap[RpcAddress, RpcAddress]()

  override def receive(
      client: TransportClient,
      message: ByteBuffer,
      callback: RpcResponseCallback): Unit = {
    // 调用internalReceive方法将ByteBuffer类型的message转换为RequestMessage
    val messageToDispatch = internalReceive(client, message)
    // 调用Dispatcher的postRemoteMessage方法将消息转换为RpcMessage后放入Inbox的消息列表。
    dispatcher.postRemoteMessage(messageToDispatch, callback)
  }

  override def receive(
      client: TransportClient,
      message: ByteBuffer): Unit = {
    val messageToDispatch = internalReceive(client, message)
    dispatcher.postOneWayMessage(messageToDispatch)
  }

  private def internalReceive(client: TransportClient, message: ByteBuffer): RequestMessage = {
    // 从TransportClient中获取远端地址RpcAddress
    val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
    assert(addr != null)
    val clientAddr = RpcAddress(addr.getHostString, addr.getPort)

    // 调用NettyRpcEnv的deserialize方法对客户端发送的序列化消息进行反序列化
    val requestMessage = nettyEnv.deserialize[RequestMessage](client, message)

    if (requestMessage.senderAddress == null) {
      // Create a new message with the socket address of the client as the sender.
      // 如果反序列化得到的请求消息requestMessage中没有发送者的地址信息，则使用从TransportClient中获取远端地址RpcAddress，
      // 并和requestMessge的接受者（NettyRpcEndpointRef）、requestMessage的内容构成RequestMessage
      RequestMessage(clientAddr, requestMessage.receiver, requestMessage.content)
    } else {
      // The remote RpcEnv listens to some port, we should also fire a RemoteProcessConnected for
      // the listening address
      // 如果反序列化得到的请求消息requestMessage中包含了发送者的地址信息，则将TransportClient中获取远端地址RpcAddress与
      // requestMessage中的发送地址之间的映射放入缓存remoteAddress.
      val remoteEnvAddress = requestMessage.senderAddress
      if (remoteAddresses.putIfAbsent(clientAddr, remoteEnvAddress) == null) {
        // 调用dispatcher.postToAll向endpoints缓存的所用EndpointData的inbox中放入RemoteProcessConnected消息。
        dispatcher.postToAll(RemoteProcessConnected(remoteEnvAddress))
      }
      // 返回requestMessage
      requestMessage
    }
  }

  override def getStreamManager: StreamManager = streamManager

  override def exceptionCaught(cause: Throwable, client: TransportClient): Unit = {
    val addr = client.getChannel.remoteAddress().asInstanceOf[InetSocketAddress]
    if (addr != null) {
      val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
      dispatcher.postToAll(RemoteProcessConnectionError(cause, clientAddr))
      // If the remove RpcEnv listens to some address, we should also fire a
      // RemoteProcessConnectionError for the remote RpcEnv listening address
      val remoteEnvAddress = remoteAddresses.get(clientAddr)
      if (remoteEnvAddress != null) {
        dispatcher.postToAll(RemoteProcessConnectionError(cause, remoteEnvAddress))
      }
    } else {
      // If the channel is closed before connecting, its remoteAddress will be null.
      // See java.net.Socket.getRemoteSocketAddress
      // Because we cannot get a RpcAddress, just log it
      logError("Exception before connecting to the client", cause)
    }
  }

  override def channelActive(client: TransportClient): Unit = {
    val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
    assert(addr != null)
    val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
    dispatcher.postToAll(RemoteProcessConnected(clientAddr))
  }

  override def channelInactive(client: TransportClient): Unit = {
    val addr = client.getChannel.remoteAddress().asInstanceOf[InetSocketAddress]
    if (addr != null) {
      val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
      nettyEnv.removeOutbox(clientAddr)
      dispatcher.postToAll(RemoteProcessDisconnected(clientAddr))
      val remoteEnvAddress = remoteAddresses.remove(clientAddr)
      // If the remove RpcEnv listens to some address, we should also  fire a
      // RemoteProcessDisconnected for the remote RpcEnv listening address
      if (remoteEnvAddress != null) {
        dispatcher.postToAll(RemoteProcessDisconnected(remoteEnvAddress))
      }
    } else {
      // If the channel is closed before connecting, its remoteAddress will be null. In this case,
      // we can ignore it since we don't fire "Associated".
      // See java.net.Socket.getRemoteSocketAddress
    }
  }
}
