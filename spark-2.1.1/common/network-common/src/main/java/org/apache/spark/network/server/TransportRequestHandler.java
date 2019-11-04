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

import com.google.common.base.Throwables;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.buffer.NioManagedBuffer;
import org.apache.spark.network.client.RpcResponseCallback;
import org.apache.spark.network.client.TransportClient;
import org.apache.spark.network.protocol.ChunkFetchFailure;
import org.apache.spark.network.protocol.ChunkFetchRequest;
import org.apache.spark.network.protocol.ChunkFetchSuccess;
import org.apache.spark.network.protocol.Encodable;
import org.apache.spark.network.protocol.OneWayMessage;
import org.apache.spark.network.protocol.RequestMessage;
import org.apache.spark.network.protocol.RpcFailure;
import org.apache.spark.network.protocol.RpcRequest;
import org.apache.spark.network.protocol.RpcResponse;
import org.apache.spark.network.protocol.StreamFailure;
import org.apache.spark.network.protocol.StreamRequest;
import org.apache.spark.network.protocol.StreamResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

import static org.apache.spark.network.util.NettyUtils.getRemoteAddress;

/**
 * A handler that processes requests from clients and writes chunk data back. Each handler is
 * attached to a single Netty channel, and keeps track of which streams have been fetched via this
 * channel, in order to clean them up if the channel is terminated (see #channelUnregistered).
 *
 * The messages should have been processed by the pipeline setup by {@link TransportServer}.
 */
public class TransportRequestHandler extends MessageHandler<RequestMessage> {
  private static final Logger logger = LoggerFactory.getLogger(TransportRequestHandler.class);

  /** The Netty channel that this handler is associated with. */
  private final Channel channel;

  /** Client on the same channel allowing us to talk back to the requester. */
  private final TransportClient reverseClient;

  /** Handles all RPC messages. */
  private final RpcHandler rpcHandler;

  /** Returns each chunk part of a stream. */
  private final StreamManager streamManager;

  public TransportRequestHandler(
      Channel channel,
      TransportClient reverseClient,
      RpcHandler rpcHandler) {
    this.channel = channel;
    this.reverseClient = reverseClient;
    this.rpcHandler = rpcHandler;
    this.streamManager = rpcHandler.getStreamManager();
  }

  @Override
  public void exceptionCaught(Throwable cause) {
    rpcHandler.exceptionCaught(cause, reverseClient);
  }

  @Override
  public void channelActive() {
    rpcHandler.channelActive(reverseClient);
  }

  @Override
  public void channelInactive() {
    if (streamManager != null) {
      try {
        streamManager.connectionTerminated(channel);
      } catch (RuntimeException e) {
        logger.error("StreamManager connectionTerminated() callback failed.", e);
      }
    }
    rpcHandler.channelInactive(reverseClient);
  }

  // TransportRequestHandler 处理以上4种RequestMessage消息，最终的处理都由RpcHandler及其内部组件完成。
  // 除了OneWayMessage消息外，其余三种消息都是调用respond方法响应客户端。
  @Override
  public void handle(RequestMessage request) {
    if (request instanceof ChunkFetchRequest) {
      processFetchRequest((ChunkFetchRequest) request);
    } else if (request instanceof RpcRequest) {
      processRpcRequest((RpcRequest) request);
    } else if (request instanceof OneWayMessage) {
      processOneWayMessage((OneWayMessage) request);
    } else if (request instanceof StreamRequest) {
      processStreamRequest((StreamRequest) request);
    } else {
      throw new IllegalArgumentException("Unknown request type: " + request);
    }
  }

  // 用来处理ChunkFetchRequest类型的消息
  private void processFetchRequest(final ChunkFetchRequest req) {
    if (logger.isTraceEnabled()) {
      logger.trace("Received req from {} to fetch block {}", getRemoteAddress(channel),
        req.streamChunkId);
    }

    ManagedBuffer buf;
    try {
    	// 校验客户端是否有权限从给定的流中读取
      streamManager.checkAuthorization(reverseClient, req.streamChunkId.streamId);
      // 将一个流和一条（只能是一条）客户端TCP的TCP连接关联起来，这可以保证对于单个的流只会有一个客户端读取。流关闭之后就永远不能够重用了
      streamManager.registerChannel(channel, req.streamChunkId.streamId);
      // 获取单个的块（块被封装为ManagedBuffer）。由于单个的流只能与单个的TCP连接相关联，因此getChunk方法不能为了某个特殊的流而并行调用。
      buf = streamManager.getChunk(req.streamChunkId.streamId, req.streamChunkId.chunkIndex);
    } catch (Exception e) {
      logger.error(String.format("Error opening block %s for request from %s",
        req.streamChunkId, getRemoteAddress(channel)), e);
      respond(new ChunkFetchFailure(req.streamChunkId, Throwables.getStackTraceAsString(e)));
      return;
    }

    // 将ManagedBuffer和流的块Id封装为ChunkFetchSuccess后，调用respond方法返回给客户端
    respond(new ChunkFetchSuccess(req.streamChunkId, buf));
  }

  // // 用来处理StreamRequest类型的消息
  private void processStreamRequest(final StreamRequest req) {
    ManagedBuffer buf;
    try {
    	// 调用StreamManager的openStream方法将获取到的流数据封装为ManagerBuffer
      buf = streamManager.openStream(req.streamId);
    } catch (Exception e) {
      logger.error(String.format(
        "Error opening stream %s for request from %s", req.streamId, getRemoteAddress(channel)), e);
      respond(new StreamFailure(req.streamId, Throwables.getStackTraceAsString(e)));
      return;
    }

    // 不管成功或失败时调用respond方法想客户端响应
    if (buf != null) {
      respond(new StreamResponse(req.streamId, buf.size(), buf));
    } else {
      respond(new StreamFailure(req.streamId, String.format(
        "Stream '%s' was not found.", req.streamId)));
    }
  }

  // 处理RpcRequest类型的消息
  private void processRpcRequest(final RpcRequest req) {
    try {
    	// 将RpcRequest消息的内容体、发送消息的客户端、一个RpcResponseCallback类型的匿名内部类 作为参数参数传递给了RpcHandler的receive方法。
	    // 这就是说真正用于处理RpcRequest消息的是RpcHandler，而非TransportRequestHandler。所有继承RpcHandler的子类都需要在其receive方法的
	    // 具体实现中回调RpcResponseCallback的onSuccess或onFailure方法。从RpcResponseCallback的实现来看，无论处理结果成功还是失败，都将
	    // 调用respond方法对客户端进行响应。
      rpcHandler.receive(reverseClient, req.body().nioByteBuffer(), new RpcResponseCallback() {
        @Override
        public void onSuccess(ByteBuffer response) {
          respond(new RpcResponse(req.requestId, new NioManagedBuffer(response)));
        }

        @Override
        public void onFailure(Throwable e) {
          respond(new RpcFailure(req.requestId, Throwables.getStackTraceAsString(e)));
        }
      });
    } catch (Exception e) {
      logger.error("Error while invoking RpcHandler#receive() on RPC id " + req.requestId, e);
      respond(new RpcFailure(req.requestId, Throwables.getStackTraceAsString(e)));
    } finally {
      req.body().release();
    }
  }

  // processOneWayMessage 方法的实现与 processRpcRequest非常相似，只是不对客户端进行任何响应
  private void processOneWayMessage(OneWayMessage req) {
    try {
    	//
      rpcHandler.receive(reverseClient, req.body().nioByteBuffer());
    } catch (Exception e) {
      logger.error("Error while invoking RpcHandler#receive() for one-way message.", e);
    } finally {
      req.body().release();
    }
  }

  /**
   * Responds to a single message with some Encodable object. If a failure occurs while sending,
   * it will be logged and the channel closed.
   */
  // respond方法中实际调用了Channel的writeAndFlush方法来响应客户端。
  private void respond(final Encodable result) {
    final SocketAddress remoteAddress = channel.remoteAddress();
    channel.writeAndFlush(result).addListener(
      new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
          if (future.isSuccess()) {
            logger.trace("Sent result {} to client {}", result, remoteAddress);
          } else {
            logger.error(String.format("Error sending result %s to %s; closing connection",
              result, remoteAddress), future.cause());
            channel.close();
          }
        }
      }
    );
  }
}
