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

import org.apache.spark.network.client.RpcResponseCallback;
import org.apache.spark.network.client.TransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Handler for sendRPC() messages sent by {@link org.apache.spark.network.client.TransportClient}s.
 */
public abstract class RpcHandler {

  private static final RpcResponseCallback ONE_WAY_CALLBACK = new OneWayRpcCallback();

  /**
   * Receive a single RPC message. Any exception thrown while in this method will be sent back to
   * the client in string form as a standard RPC failure.
   *
   * This method will not be called in parallel for a single TransportClient (i.e., channel).
   *
   * @param client A channel client which enables the handler to make requests back to the sender
   *               of this RPC. This will always be the exact same object for a particular channel.
   * @param message The serialized bytes of the RPC.
   * @param callback Callback which should be invoked exactly once upon success or failure of the
   *                 RPC.
   */

  // 这是一个抽象方法，用来接收单一的RPC消息，具体处理逻辑需要子类去实现。RpcResponseCallback用于对请求处理
  // 结束后进行回调，无论处理结果是成功还是失败，RpcResponseCallback都会被调用一次。
  public abstract void receive(
      TransportClient client,
      ByteBuffer message,
      RpcResponseCallback callback);

  /**
   * Returns the StreamManager which contains the state about which streams are currently being
   * fetched by a TransportClient.
   */
  // 获取StreamManager，StreamManager可以从流中获取单个块，因此它也包含着当前正在被TransportClient获取的流的状态
  public abstract StreamManager getStreamManager();

  /**
   * Receives an RPC message that does not expect a reply. The default implementation will
   * call "{@link #receive(TransportClient, ByteBuffer, RpcResponseCallback)}" and log a warning if
   * any of the callback methods are called.
   *
   * @param client A channel client which enables the handler to make requests back to the sender
   *               of this RPC. This will always be the exact same object for a particular channel.
   * @param message The serialized bytes of the RPC.
   */

  // 从OneWayRpcCallback的实现可以看出，其onSuccess 和 onFailure只是打印日志，并没有针对客户端做恢复处理
  public void receive(TransportClient client, ByteBuffer message) {
    receive(client, message, ONE_WAY_CALLBACK);
  }

  /**
   * Invoked when the channel associated with the given client is active.
   */
  // 当与给定客户端相关联的channel处于活动状态时调用
  public void channelActive(TransportClient client) { }

  /**
   * Invoked when the channel associated with the given client is inactive.
   * No further requests will come from this client.
   */
  // 当与给定客户端相关联的channel处于非活动状态时调用
  public void channelInactive(TransportClient client) { }

  // 当channel产生异常时调用
  public void exceptionCaught(Throwable cause, TransportClient client) { }

  private static class OneWayRpcCallback implements RpcResponseCallback {

    private static final Logger logger = LoggerFactory.getLogger(OneWayRpcCallback.class);

    @Override
    public void onSuccess(ByteBuffer response) {
      logger.warn("Response provided for one-way RPC.");
    }

    @Override
    public void onFailure(Throwable e) {
      logger.error("Error response provided for one-way RPC.", e);
    }

  }

}
