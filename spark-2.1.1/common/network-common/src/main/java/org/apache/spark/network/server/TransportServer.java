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

package org.apache.spark.network.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.apache.spark.network.TransportContext;
import org.apache.spark.network.util.IOMode;
import org.apache.spark.network.util.JavaUtils;
import org.apache.spark.network.util.NettyUtils;
import org.apache.spark.network.util.TransportConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Server for the efficient, low-level streaming service.
 */
public class TransportServer implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(TransportServer.class);

  private final TransportContext context;
  private final TransportConf conf;
  private final RpcHandler appRpcHandler;
  private final List<TransportServerBootstrap> bootstraps;

  private ServerBootstrap bootstrap;
  private ChannelFuture channelFuture;
  private int port = -1;

  /**
   * Creates a TransportServer that binds to the given host and the given port, or to any available
   * if 0. If you don't want to bind to any special host, set "hostToBind" to null.
   * */
  public TransportServer(
      TransportContext context,
      String hostToBind,
      int portToBind,
      RpcHandler appRpcHandler,
      List<TransportServerBootstrap> bootstraps) {
  	// 参数传递的TransportContext的引用
    this.context = context;
    // 指TransportConf，这里通过调用TransportContext的getConf获取
    this.conf = context.getConf();
    // RPC 请求处理器RpcHandler
    this.appRpcHandler = appRpcHandler;
    // 参数传递的TransportServerBootstrap
    this.bootstraps = Lists.newArrayList(Preconditions.checkNotNull(bootstraps));

    try {
      init(hostToBind, portToBind);
    } catch (RuntimeException e) {
      JavaUtils.closeQuietly(this);
      throw e;
    }
  }

  public int getPort() {
    if (port == -1) {
      throw new IllegalStateException("Server not initialized");
    }
    return port;
  }

  private void init(String hostToBind, int portToBind) {

  	// 根据Netty的API文档，Netty服务端需同时创建bossGroup和workerGroup
    IOMode ioMode = IOMode.valueOf(conf.ioMode());
    EventLoopGroup bossGroup =
      NettyUtils.createEventLoop(ioMode, conf.serverThreads(), conf.getModuleName() + "-server");
    EventLoopGroup workerGroup = bossGroup;

    // 创建一个汇集ByteBuf但对本地线程缓存禁用的分配器
    PooledByteBufAllocator allocator = NettyUtils.createPooledByteBufAllocator(
      conf.preferDirectBufs(), true /* allowCache */, conf.serverThreads());

    // 创建Netty的服务端根引导程序并对其进行配置
    bootstrap = new ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(NettyUtils.getServerChannelClass(ioMode))
      .option(ChannelOption.ALLOCATOR, allocator)
      .childOption(ChannelOption.ALLOCATOR, allocator);

    if (conf.backLog() > 0) {
      bootstrap.option(ChannelOption.SO_BACKLOG, conf.backLog());
    }

    if (conf.receiveBuf() > 0) {
      bootstrap.childOption(ChannelOption.SO_RCVBUF, conf.receiveBuf());
    }

    if (conf.sendBuf() > 0) {
      bootstrap.childOption(ChannelOption.SO_SNDBUF, conf.sendBuf());
    }

    // 为根引导程序设置管道初始化回调函数，此回调函数首先设置TransportServerBootstrap到根引导程序中，
	  // 然后调用TransportContext的initializePipeline方法初始化Channel的pipeline。
    bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(SocketChannel ch) throws Exception {
        RpcHandler rpcHandler = appRpcHandler;
        for (TransportServerBootstrap bootstrap : bootstraps) {
          rpcHandler = bootstrap.doBootstrap(ch, rpcHandler);
        }
        context.initializePipeline(ch, rpcHandler);
      }
    });

    // 给根引导程序绑定 Socket 的监听端口，最后返回监听的端口
    InetSocketAddress address = hostToBind == null ?
        new InetSocketAddress(portToBind): new InetSocketAddress(hostToBind, portToBind);
    channelFuture = bootstrap.bind(address);
    channelFuture.syncUninterruptibly();

    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort();
    logger.debug("Shuffle server started on port: {}", port);
  }

  @Override
  public void close() {
    if (channelFuture != null) {
      // close is a local operation and should finish within milliseconds; timeout just to be safe
      channelFuture.channel().close().awaitUninterruptibly(10, TimeUnit.SECONDS);
      channelFuture = null;
    }
    if (bootstrap != null && bootstrap.group() != null) {
      bootstrap.group().shutdownGracefully();
    }
    if (bootstrap != null && bootstrap.childGroup() != null) {
      bootstrap.childGroup().shutdownGracefully();
    }
    bootstrap = null;
  }
}
