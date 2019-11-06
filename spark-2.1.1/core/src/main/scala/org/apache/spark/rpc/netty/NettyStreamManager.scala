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

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer}
import org.apache.spark.network.server.StreamManager
import org.apache.spark.rpc.RpcEnvFileServer
import org.apache.spark.util.Utils

/**
 * StreamManager implementation for serving files from a NettyRpcEnv.
 *
 * Three kinds of resources can be registered in this manager, all backed by actual files:
 *
 * - "/files": a flat list of files; used as the backend for [[SparkContext.addFile]].
 * - "/jars": a flat list of files; used as the backend for [[SparkContext.addJar]].
 * - arbitrary directories; all files under the directory become available through the manager,
 *   respecting the directory's hierarchy.
 *
 * Only streaming (openStream) is supported.
 */
// NettyStreamManager 中定义了三个文件与目录缓存，根据这三个缓存结构，可以提供对普通文件、Jar文件及目录的下载支持。
// NettyStreamManager中一共提供了两类方法，一类用于添加缓存，一类用于支持文件流的读取。
private[netty] class NettyStreamManager(rpcEnv: NettyRpcEnv)
  extends StreamManager with RpcEnvFileServer {

  private val files = new ConcurrentHashMap[String, File]()
  private val jars = new ConcurrentHashMap[String, File]()
  private val dirs = new ConcurrentHashMap[String, File]()

  override def getChunk(streamId: Long, chunkIndex: Int): ManagedBuffer = {
    throw new UnsupportedOperationException()
  }


  // openStream从缓存中获取文件后，将TransportConf及File等信息封装为FileSegmentManagedBuffer并返回。
  // 我们通过对FileSegmentManagedBuffer实现的了解，我们知道FileSegmentManagedBuffer封装了对文件的流处理细节。
  // 由于NettyStreamManager只实现了StreamManager的openStream方法，根据对TransportRequestHandler的handle
  // 方法和processStreamRequest方法的介绍，我们知道NettyStreamManager将只提供对StreamRequest类型消息的处理。
  // 各个Executor节点就可以使用Driver节点的RpcEnv提供NettyStreamManger，从Driver将Jar包或文件下载到Executor
  // 节点上供任务执行
  override def openStream(streamId: String): ManagedBuffer = {
    val Array(ftype, fname) = streamId.stripPrefix("/").split("/", 2)
    val file = ftype match {
      case "files" => files.get(fname)
      case "jars" => jars.get(fname)
      case other =>
        val dir = dirs.get(ftype)
        require(dir != null, s"Invalid stream URI: $ftype not found.")
        new File(dir, fname)
    }

    if (file != null && file.isFile()) {
      new FileSegmentManagedBuffer(rpcEnv.transportConf, file, 0, file.length())
    } else {
      null
    }
  }

  override def addFile(file: File): String = {
    val existingPath = files.putIfAbsent(file.getName, file)
    require(existingPath == null || existingPath == file,
      s"File ${file.getName} was already registered with a different path " +
        s"(old path = $existingPath, new path = $file")
    s"${rpcEnv.address.toSparkURL}/files/${Utils.encodeFileNameToURIRawPath(file.getName())}"
  }

  override def addJar(file: File): String = {
    val existingPath = jars.putIfAbsent(file.getName, file)
    require(existingPath == null || existingPath == file,
      s"File ${file.getName} was already registered with a different path " +
        s"(old path = $existingPath, new path = $file")
    s"${rpcEnv.address.toSparkURL}/jars/${Utils.encodeFileNameToURIRawPath(file.getName())}"
  }

  override def addDirectory(baseUri: String, path: File): String = {
    val fixedBaseUri = validateDirectoryUri(baseUri)
    require(dirs.putIfAbsent(fixedBaseUri.stripPrefix("/"), path) == null,
      s"URI '$fixedBaseUri' already registered.")
    s"${rpcEnv.address.toSparkURL}$fixedBaseUri"
  }

}
