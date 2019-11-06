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

package org.apache.spark.storage

import java.io.{FileOutputStream, IOException, RandomAccessFile}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel.MapMode

import com.google.common.io.Closeables

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils
import org.apache.spark.util.io.ChunkedByteBuffer

/**
 * Stores BlockManager blocks on disk.
 */
// DiskStore负责将Block存储到磁盘。DiskStore依赖于DiskBlockManager的服务
private[spark] class DiskStore(conf: SparkConf, diskManager: DiskBlockManager) extends Logging {

  /**
    * 什么是FileChannel的内存镜像映射方法？
    * 在Java NIO中，FileChannel的map方法所提供的快速读写技术，其实质上是将通道所连接的数据节点中的全部或部分数据
    * 直接映射到内存的一个Buffer中，而这个内存Buffer块就是节点数据的镜像，你直接对这个Buffer进行修改，会影响到节点
    * 数据。这个Buffer叫做MappedBuffer，即镜像Buffer。由于是内存镜像，因此处理速度快。
    */
  // 读取磁盘中的Block时，是直接读取,还是使用FileChannel的内存镜像映射方法读取的阈值
  private val minMemoryMapBytes = conf.getSizeAsBytes("spark.storage.memoryMapThreshold", "2m")

  // 获取给定BlockId所对应Block的大小
  def getSize(blockId: BlockId): Long = {
    diskManager.getFile(blockId.name).length
  }

  /**
   * Invokes the provided callback function to write the specific block.
   *
   * @throws IllegalStateException if the block already exists in the disk store.
   */
  // 此方法用于将BlockId所对应的Block写入磁盘
  def put(blockId: BlockId)(writeFunc: FileOutputStream => Unit): Unit = {
    // 判断给定BlockId所对应的Block文件是否存在
    if (contains(blockId)) {
      throw new IllegalStateException(s"Block $blockId is already present in the disk store")
    }
    logDebug(s"Attempting to put block $blockId")
    val startTime = System.currentTimeMillis
    // 获取BlockId所对应的Block文件，并打开文件输出流
    val file = diskManager.getFile(blockId)
    val fileOutputStream = new FileOutputStream(file)
    var threwException: Boolean = true
    try {
      // 调用回调函数writeFunc,对Block文件写入，当写入失败时，还需要调用remove方法删除BlockId所对应的Block文件
      writeFunc(fileOutputStream)
      threwException = false
    } finally {
      try {
        Closeables.close(fileOutputStream, threwException)
      } finally {
         if (threwException) {
          remove(blockId)
        }
      }
    }
    val finishTime = System.currentTimeMillis
    logDebug("Block %s stored as %s file on disk in %d ms".format(
      file.getName,
      Utils.bytesToString(file.length()),
      finishTime - startTime))
  }

  // 此方法用于将BlockId所对应的Block写入磁盘，Block的内容已经封装为ChunkedByteBuffer
  def putBytes(blockId: BlockId, bytes: ChunkedByteBuffer): Unit = {
    put(blockId) { fileOutputStream =>
      val channel = fileOutputStream.getChannel
      Utils.tryWithSafeFinally {
        bytes.writeFully(channel)
      } {
        channel.close()
      }
    }
  }

  // 此方法用于读取给定BlockId所对应的Block,并封装为ChunkedByteBuffer返回
  def getBytes(blockId: BlockId): ChunkedByteBuffer = {
    val file = diskManager.getFile(blockId.name)
    val channel = new RandomAccessFile(file, "r").getChannel
    Utils.tryWithSafeFinally {
      // For small files, directly read rather than memory map
      if (file.length < minMemoryMapBytes) {
        val buf = ByteBuffer.allocate(file.length.toInt)
        channel.position(0)
        while (buf.remaining() != 0) {
          if (channel.read(buf) == -1) {
            throw new IOException("Reached EOF before filling buffer\n" +
              s"offset=0\nfile=${file.getAbsolutePath}\nbuf.remaining=${buf.remaining}")
          }
        }
        buf.flip()
        new ChunkedByteBuffer(buf)
      } else {
        new ChunkedByteBuffer(channel.map(MapMode.READ_ONLY, 0, file.length))
      }
    } {
      channel.close()
    }
  }

  // 此方法用于删除给定BlockId所对应的Block文件。
  def remove(blockId: BlockId): Boolean = {
    val file = diskManager.getFile(blockId.name)
    if (file.exists()) {
      val ret = file.delete()
      if (!ret) {
        logWarning(s"Error deleting ${file.getPath()}")
      }
      ret
    } else {
      false
    }
  }

  // 此方法用于判断本地磁盘存储路径下是否包含给定BlockId所对应的Block文件。
  def contains(blockId: BlockId): Boolean = {
    val file = diskManager.getFile(blockId.name)
    file.exists()
  }
}
