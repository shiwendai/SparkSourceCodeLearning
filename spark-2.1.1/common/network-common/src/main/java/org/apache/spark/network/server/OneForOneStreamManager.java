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
import io.netty.channel.Channel;
import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.client.TransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StreamManager which allows registration of an Iterator&lt;ManagedBuffer&gt;, which are
 * individually fetched as chunks by the client. Each registered buffer is one chunk.
 */
public class OneForOneStreamManager extends StreamManager {
  private static final Logger logger = LoggerFactory.getLogger(OneForOneStreamManager.class);

  // 用于生成数据流的标识
  private final AtomicLong nextStreamId;

  // 维护StreamId 和 StreamState之间映射关系的缓存
  private final ConcurrentHashMap<Long, StreamState> streams;

  /** State of a single stream. */
  // OneForOneStreamManager使用StreamState来维护流的状态
  private static class StreamState {
  	// 请求流所属的应用程序ID。此属性只有在ExternalShuffleClient启用后才会用到。
    final String appId;

    // ManagedBuffer的缓冲
    final Iterator<ManagedBuffer> buffers;

    // 与当前流相关的Channel
    // The channel associated to the stream
    Channel associatedChannel = null;

    // 为了保证客户端按顺序每次请求一个块，所以用此属性跟踪客户端当前接收到的ManagedBuffer的索引
    // Used to keep track of the index of the buffer that the user has retrieved, just to ensure
    // that the caller only requests each chunk one at a time, in order.
    int curChunk = 0;

    StreamState(String appId, Iterator<ManagedBuffer> buffers) {
      this.appId = appId;
      this.buffers = Preconditions.checkNotNull(buffers);
    }
  }

  public OneForOneStreamManager() {
    // For debugging purposes, start with a random stream id to help identifying different streams.
    // This does not need to be globally unique, only unique to this class.
    nextStreamId = new AtomicLong((long) new Random().nextInt(Integer.MAX_VALUE) * 1000);
    streams = new ConcurrentHashMap<>();
  }

  // 此方法用于注册管道，其实际的作用是将一个流和一条（只能是一条）客户端的TCP连接关联起来，这可以保证对于单个的流
  // 只会有一个客户端读取。流关闭之后就永远不能够重用了。
  @Override
  public void registerChannel(Channel channel, long streamId) {
    if (streams.containsKey(streamId)) {
      streams.get(streamId).associatedChannel = channel;
    }
  }

  // 此方法用于获取单个的块（块被封装为ManagerBuffer）
  @Override
  public ManagedBuffer getChunk(long streamId, int chunkIndex) {
  	// 从streams中获取StreamState.
    StreamState state = streams.get(streamId);
    if (chunkIndex != state.curChunk) {
    	// 如果要获取的块的索引与StreamState的curChunk属性不相等，这说明顺序有问题
      throw new IllegalStateException(String.format(
        "Received out-of-order chunk index %s (expected %s)", chunkIndex, state.curChunk));
    } else if (!state.buffers.hasNext()) {
    	// 如果要获取的块的索引超出了buffer缓冲的大小，则说明请求了一个超出范围的块。
      throw new IllegalStateException(String.format(
        "Requested chunk index beyond end %s", chunkIndex));
    }

    // 将StreamState的curChunk加1，为下次接收请求做好准备
    state.curChunk += 1;

    // 从buffers缓冲中获取ManagedBuffer.
    ManagedBuffer nextChunk = state.buffers.next();

    // 如果buffers缓冲已经迭代到了末端，那么说明当前流的块已经全部被客户端获取了，需要将streamId与对应的StreamState从Streams中移走
    if (!state.buffers.hasNext()) {
      logger.trace("Removing stream id {}", streamId);
      streams.remove(streamId);
    }

    // 返回获取的ManagedBuffer
    return nextChunk;
  }

  @Override
  public void connectionTerminated(Channel channel) {
    // Close all streams which have been associated with the channel.
    for (Map.Entry<Long, StreamState> entry: streams.entrySet()) {
      StreamState state = entry.getValue();
      if (state.associatedChannel == channel) {
        streams.remove(entry.getKey());

        // Release all remaining buffers.
        while (state.buffers.hasNext()) {
          state.buffers.next().release();
        }
      }
    }
  }

  // 用以校验客户端是否有权限从给定的流中读取
  @Override
  public void checkAuthorization(TransportClient client, long streamId) {
    if (client.getClientId() != null) {
      StreamState state = streams.get(streamId);
      Preconditions.checkArgument(state != null, "Unknown stream ID.");
      if (!client.getClientId().equals(state.appId)) {
        throw new SecurityException(String.format(
          "Client %s not authorized to read stream %d (app %s).",
          client.getClientId(),
          streamId,
          state.appId));
      }
    }
  }

  /**
   * Registers a stream of ManagedBuffers which are served as individual chunks one at a time to
   * callers. Each ManagedBuffer will be release()'d after it is transferred on the wire. If a
   * client connection is closed before the iterator is fully drained, then the remaining buffers
   * will all be release()'d.
   *
   * If an app ID is provided, only callers who've authenticated with the given app ID will be
   * allowed to fetch from this stream.
   */
  // 此方法用于向OneForOneStreamManager的streams缓存中注册流
  public long registerStream(String appId, Iterator<ManagedBuffer> buffers) {
    long myStreamId = nextStreamId.getAndIncrement();
    streams.put(myStreamId, new StreamState(appId, buffers));
    return myStreamId;
  }

}
