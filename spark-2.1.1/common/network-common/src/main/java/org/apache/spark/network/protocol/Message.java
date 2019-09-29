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

package org.apache.spark.network.protocol;

import io.netty.buffer.ByteBuf;

import org.apache.spark.network.buffer.ManagedBuffer;

/** An on-the-wire transmittable message. */
// 实现Encodedable接口的类将可以转换到一个ByteBuf中，多个对象将被存储到预先分配的单个ByteBuffer，
// 所以这里的encodeLength 用于返回转换的对象数量，
public interface Message extends Encodable {
  /** Used to identify this request type. */
  // 返回消息的类型
  Type type();

  /** An optional body for the message. */
  // 返回消息中可选的内容体
  ManagedBuffer body();

  /** Whether to include the body of the message in the same frame as the message. */
  // 用于判断消息的主体是否包含在消息的同一帧中。
  boolean isBodyInFrame();

  /** Preceding every serialized Message is its type, which allows us to deserialize it. */
  enum Type implements Encodable {
    ChunkFetchRequest(0), ChunkFetchSuccess(1), ChunkFetchFailure(2),
    RpcRequest(3), RpcResponse(4), RpcFailure(5),
    StreamRequest(6), StreamResponse(7), StreamFailure(8),
    OneWayMessage(9), User(-1);

    private final byte id;

    Type(int id) {
      assert id < 128 : "Cannot have more than 128 message types";
      this.id = (byte) id;
    }

    public byte id() { return id; }

    @Override public int encodedLength() { return 1; }

    @Override public void encode(ByteBuf buf) { buf.writeByte(id); }

    public static Type decode(ByteBuf buf) {
      byte id = buf.readByte();
      switch (id) {
        case 0: return ChunkFetchRequest;
        case 1: return ChunkFetchSuccess;
        case 2: return ChunkFetchFailure;
        case 3: return RpcRequest;
        case 4: return RpcResponse;
        case 5: return RpcFailure;
        case 6: return StreamRequest;
        case 7: return StreamResponse;
        case 8: return StreamFailure;
        case 9: return OneWayMessage;
        case -1: throw new IllegalArgumentException("User type messages cannot be decoded.");
        default: throw new IllegalArgumentException("Unknown message type: " + id);
      }
    }
  }
}
