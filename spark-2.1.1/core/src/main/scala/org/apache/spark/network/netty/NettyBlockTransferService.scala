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

package org.apache.spark.network.netty

import java.nio.ByteBuffer

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.network._
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.network.client.{RpcResponseCallback, TransportClientBootstrap, TransportClientFactory}
import org.apache.spark.network.sasl.{SaslClientBootstrap, SaslServerBootstrap}
import org.apache.spark.network.server._
import org.apache.spark.network.shuffle.{BlockFetchingListener, OneForOneBlockFetcher, RetryingBlockFetcher}
import org.apache.spark.network.shuffle.protocol.UploadBlock
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.storage.{BlockId, StorageLevel}
import org.apache.spark.util.Utils

/**
 * A BlockTransferService that uses Netty to fetch a set of blocks at at time.
 */
// NettyBlockTransferService 与 NettyRpcEnv的最大区别----使用的RpcHandler的实现类不同，
// NettyRpcEnv采用了NettyRpcHandler,而NettyBlockTransferService采用了NettyBlockRpcServer

// 如果没有部署外部的Shuffle服务，即spark.shuffle.service.enabled属性为false时，NettyBlockTransferService
// 不但通过OneForOneStreamManager与NettyBlockRpcServer对外提供Block上传与下载服务，也将作为默认的Shuffle客户端。
// NettyBlockTransferService作为Shuffle客户端，具有发起上传和下载请求并接受服务端响应的能力。NettyBlockTransferService
// 的两个方法----fetchBlocks 和 uploadBlock将帮组我们达到目的。
private[spark] class NettyBlockTransferService(
    conf: SparkConf,
    securityManager: SecurityManager,
    bindAddress: String,
    override val hostName: String,
    _port: Int,
    numCores: Int)
  extends BlockTransferService {

  // TODO: Don't use Java serialization, use a more cross-version compatible serialization format.
  private val serializer = new JavaSerializer(conf)
  private val authEnabled = securityManager.isAuthenticationEnabled()
  private val transportConf = SparkTransportConf.fromSparkConf(conf, "shuffle", numCores)

  private[this] var transportContext: TransportContext = _
  private[this] var server: TransportServer = _
  private[this] var clientFactory: TransportClientFactory = _
  private[this] var appId: String = _

  // NettyBlockTransferService只有在其init方法被调用，即被初始化之后才提供服务。BlockManager在初始化的时候，
  // 将调用NettyBlockTransferService的ini方法。
  override def init(blockDataManager: BlockDataManager): Unit = {
    // 创建NettyBlockRpcServer.NettyBlockRpcServer继承了我们熟悉的RpcHandler,服务端对客户端的Block读写
    // 请求的处理都交给了RpcHandler的实现类，因此NettyBlockRpcServer将处理Block块的RPC请求。
    val rpcHandler = new NettyBlockRpcServer(conf.getAppId, serializer, blockDataManager)

    // 准备客户端引导程序TransportClientBootstrap 和 服务端引导程序TransportServerBootstrap
    var serverBootstrap: Option[TransportServerBootstrap] = None
    var clientBootstrap: Option[TransportClientBootstrap] = None
    if (authEnabled) {
      serverBootstrap = Some(new SaslServerBootstrap(transportConf, securityManager))
      clientBootstrap = Some(new SaslClientBootstrap(transportConf, conf.getAppId, securityManager,
        securityManager.isSaslEncryptionEnabled()))
    }
    // 创建TransportContext
    transportContext = new TransportContext(transportConf, rpcHandler)
    // 创建传输客户端工厂TransportClientFactory
    clientFactory = transportContext.createClientFactory(clientBootstrap.toSeq.asJava)
    // 创建TransportServer
    server = createServer(serverBootstrap.toList)
    appId = conf.getAppId
    logInfo(s"Server created on ${hostName}:${server.getPort}")
  }

  /** Creates and binds the TransportServer, possibly trying multiple ports. */
  private def createServer(bootstraps: List[TransportServerBootstrap]): TransportServer = {
    def startService(port: Int): (TransportServer, Int) = {
      val server = transportContext.createServer(bindAddress, port, bootstraps.asJava)
      (server, server.getPort)
    }

    Utils.startServiceOnPort(_port, startService, conf, getClass.getName)._1
  }

  // 根据对发送获取远端Block的请求的分析，无论是请求一次还是异步多次重试，最后都落实到调用BlockFetchStarter的
    // createAndStart方法。BlockFetchStarter的createAndStart方法首先创建TransportClient,然后创建
    // OneForOneBlockFetcher并调用其start方法。
  override def fetchBlocks(
      host: String,
      port: Int,
      execId: String,
      blockIds: Array[String],
      listener: BlockFetchingListener): Unit = {
    logTrace(s"Fetch blocks from $host:$port (executor id $execId)")
    try {
      // 创建RetryingBlockFetcher.BlockFetchStarter的匿名实现类的实例blockFetchStarter,
      // 此匿名类实现了BlockFetchStarter接口的createAndStart方法
      val blockFetchStarter = new RetryingBlockFetcher.BlockFetchStarter {
        override def createAndStart(blockIds: Array[String], listener: BlockFetchingListener) {
          val client = clientFactory.createClient(host, port)
          new OneForOneBlockFetcher(client, appId, execId, blockIds.toArray, listener).start()
        }
      }

      // 获取spark.$module.io.maxRetries属性（NettyBlockTransferService的module为shuffle）
      // 的值作为下载请求的最大重试次数maxRetries
      val maxRetries = transportConf.maxIORetries()
      if (maxRetries > 0) {
        // Note this Fetcher will correctly handle maxRetries == 0; we avoid it just in case there's
        // a bug in this code. We should remove the if statement once we're sure of the stability.
        // 创建RetryingBlockFetcher并调用RetryingiBlockFetcher.start方法
        new RetryingBlockFetcher(transportConf, blockFetchStarter, blockIds, listener).start()
      } else {
        // 否则直接调用blockFetchStarter.createAndStart方法
        blockFetchStarter.createAndStart(blockIds, listener)
      }
    } catch {
      case e: Exception =>
        logError("Exception while beginning fetchBlocks", e)
        blockIds.foreach(listener.onBlockFetchFailure(_, e))
    }
  }

  override def port: Int = server.getPort

  override def uploadBlock(
      hostname: String,
      port: Int,
      execId: String,
      blockId: BlockId,
      blockData: ManagedBuffer,
      level: StorageLevel,
      classTag: ClassTag[_]): Future[Unit] = {
    // 创建一个空Promise,调用方此方将持有此Promise的Future
    val result = Promise[Unit]()
    // 创建TransportClient
    val client = clientFactory.createClient(hostname, port)

    // 将存储级别StorageLevel和类型标记classTag等元数据序列化
    // StorageLevel and ClassTag are serialized as bytes using our JavaSerializer.
    // Everything else is encoded using our binary protocol.
    val metadata = JavaUtils.bufferToArray(serializer.newInstance().serialize((level, classTag)))

    // ManagedBuffer.nioByteBuffer方法将Block的数据转换货这复制为Nio的ByteBuffer
    // Convert or copy nio buffer into array in order to serialize it.
    val array = JavaUtils.bufferToArray(blockData.nioByteBuffer())

    // 调用TransportClient的sendRpc方法发送RPC消息UploadBlock
    client.sendRpc(new UploadBlock(appId, execId, blockId.toString, metadata, array).toByteBuffer,
      new RpcResponseCallback {
        override def onSuccess(response: ByteBuffer): Unit = {
          logTrace(s"Successfully uploaded block $blockId")
          result.success((): Unit)
        }
        override def onFailure(e: Throwable): Unit = {
          logError(s"Error while uploading block $blockId", e)
          result.failure(e)
        }
      })

    result.future
  }

  override def close(): Unit = {
    if (server != null) {
      server.close()
    }
    if (clientFactory != null) {
      clientFactory.close()
    }
  }
}
