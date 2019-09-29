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

package org.apache.spark.network.shuffle;

import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.client.ChunkReceivedCallback;
import org.apache.spark.network.client.RpcResponseCallback;
import org.apache.spark.network.client.TransportClient;
import org.apache.spark.network.shuffle.protocol.BlockTransferMessage;
import org.apache.spark.network.shuffle.protocol.OpenBlocks;
import org.apache.spark.network.shuffle.protocol.StreamHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Simple wrapper on top of a TransportClient which interprets each chunk as a whole block, and
 * invokes the BlockFetchingListener appropriately. This class is agnostic to the actual RPC
 * handler, as long as there is a single "open blocks" message which returns a ShuffleStreamHandle,
 * and Java serialization is used.
 *
 * Note that this typically corresponds to a
 * {@link org.apache.spark.network.server.OneForOneStreamManager} on the server side.
 */
// 客户端在需要下载远端节点的Block时，将创建OneForOneBlockFetcher，然后使用OneForOneBlockFetcher完成Block的下载。
public class OneForOneBlockFetcher {
  private static final Logger logger = LoggerFactory.getLogger(OneForOneBlockFetcher.class);

  // 用于向服务端发送请求的TransportClient
  private final TransportClient client;
  // OpenBlocks将携带远端节点的appId,executorId,BlockIds数组，这表示从远端的哪个实例获取那些Block，并且知道是哪个远端Executor生成的Block
  private final OpenBlocks openMessage;
  // 与openMessage的blockIds属性一致
  private final String[] blockIds;
  // 将在获取Block成功或失败时被回调
  private final BlockFetchingListener listener;
  // 获取块成功或失败时回调，配合BlockFetchingListener使用
  private final ChunkReceivedCallback chunkCallback;
  // 客户端给服务端发发送OpenBlocks消息后，服务端会在OneForOneStreamManager的streams缓存中缓存从存储体系中读取到的ManagedBuffer序列，
	// 并生成与ManagedBuffer序列对应的streamId,然后将streamId和ManagedBuffer序列的大小封装为StreamHandle消息返回给客户端，客户端的
	// streamHandle属性将持有此StreamHandle消息
  private StreamHandle streamHandle = null;

  public OneForOneBlockFetcher(
      TransportClient client,
      String appId,
      String execId,
      String[] blockIds,
      BlockFetchingListener listener) {
    this.client = client;
    this.openMessage = new OpenBlocks(appId, execId, blockIds);
    this.blockIds = blockIds;
    this.listener = listener;
    this.chunkCallback = new ChunkCallback();
  }

  /** Callback invoked on receipt of each chunk. We equate a single chunk to a single block. */
  private class ChunkCallback implements ChunkReceivedCallback {
    @Override
    public void onSuccess(int chunkIndex, ManagedBuffer buffer) {
      // On receipt of a chunk, pass it upwards as a block.
      listener.onBlockFetchSuccess(blockIds[chunkIndex], buffer);
    }

    @Override
    public void onFailure(int chunkIndex, Throwable e) {
      // On receipt of a failure, fail every block from chunkIndex onwards.
      String[] remainingBlockIds = Arrays.copyOfRange(blockIds, chunkIndex, blockIds.length);
      failRemainingBlocks(remainingBlockIds, e);
    }
  }

  /**
   * Begins the fetching process, calling the listener with every block fetched.
   * The given message will be serialized with the Java serializer, and the RPC must return a
   * {@link StreamHandle}. We will send all fetch requests immediately, without throttling.
   */
  // OneForOneBlockFetcher的start方法用于获取远端BLock
  public void start() {
  	// 校验要获取的Block的数量
    if (blockIds.length == 0) {
      throw new IllegalArgumentException("Zero-sized blockIds array");
    }

    // 1.调用TransportClient的sendRpc方法发送openMessage(即openBlocks)消息，并向客户端的outstandingRpcs缓存注册匿名的RpcResponseCallback实现
	  // 2.客户端等待接收服务端的响应(此时服务端正在进行处理)
	  // 3.当客户端接收到服务端的响应是，将从outstandingRpcs缓存取出RpcResponseCallback实现，以服务端的响应类别（RpcResponse或RpcFailure）分别
	  //  回调此处的onSuccess和onFailure方法
    client.sendRpc(openMessage.toByteBuffer(), new RpcResponseCallback() {
      @Override
      public void onSuccess(ByteBuffer response) {
        try {
        	// 先将服务端的response反序列化为StreamHandle类型
          streamHandle = (StreamHandle) BlockTransferMessage.Decoder.fromByteBuffer(response);
          logger.trace("Successfully opened blocks {}, preparing to fetch chunks.", streamHandle);

          // Immediately request all chunks -- we expect that the total size of the request is
          // reasonable due to higher level chunking in [[ShuffleBlockFetcherIterator]].
	        // 然后根据StreamHandle的numChunks属性的大小，按照索引由低到高，遍历调用TransportClient的fetchChunk方法逐个获取Block
          for (int i = 0; i < streamHandle.numChunks; i++) {
            client.fetchChunk(streamHandle.streamId, i, chunkCallback);
          }
        } catch (Exception e) {
          logger.error("Failed while starting block fetches after success", e);
          failRemainingBlocks(blockIds, e);
        }
      }

      @Override
      public void onFailure(Throwable e) {
        logger.error("Failed while starting block fetches", e);
        failRemainingBlocks(blockIds, e);
      }
    });
  }

  /** Invokes the "onBlockFetchFailure" callback for every listed block id. */
  private void failRemainingBlocks(String[] failedBlockIds, Throwable e) {
    for (String blockId : failedBlockIds) {
      try {
        listener.onBlockFetchFailure(blockId, e);
      } catch (Exception e2) {
        logger.error("Error in block fetch failure callback", e2);
      }
    }
  }
}
