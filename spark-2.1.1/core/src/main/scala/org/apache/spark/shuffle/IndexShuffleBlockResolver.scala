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

package org.apache.spark.shuffle

import java.io._

import com.google.common.io.ByteStreams

import org.apache.spark.{SparkConf, SparkEnv}
import org.apache.spark.internal.Logging
import org.apache.spark.io.NioBufferedFileInputStream
import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer}
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.shuffle.IndexShuffleBlockResolver.NOOP_REDUCE_ID
import org.apache.spark.storage._
import org.apache.spark.util.Utils

/**
 * Create and maintain the shuffle blocks' mapping between logic block and physical file location.
 * Data of shuffle blocks from the same map task are stored in a single consolidated data file.
 * The offsets of the data blocks in the data file are stored in a separate index file.
 *
 * We use the name of the shuffle data's shuffleBlockId with reduce ID set to 0 and add ".data"
 * as the filename postfix for data file, and ".index" as the filename postfix for index file.
 *
 */
// Note: Changes to the format in this file should be kept in sync with
// org.apache.spark.network.shuffle.ExternalShuffleBlockResolver#getSortBasedShuffleBlockData().
// IndexShuffleBlockResolver用于创建和维护ShuffleBlock与物理文件位置之间的映射关系
private[spark] class IndexShuffleBlockResolver(
    conf: SparkConf,
    _blockManager: BlockManager = null)
  extends ShuffleBlockResolver
  with Logging {

  private lazy val blockManager = Option(_blockManager).getOrElse(SparkEnv.get.blockManager)

  // 即与Shuffle相关的TransportConf。有了transportConf，就可以方便地对Shuffle客户端传输线程数
  // （spark.shuffle.io.clientThreads属性）和Shuffle服务端传输线程数（spark.shuffle.io.serverThreads属性）进行读取。
  private val transportConf = SparkTransportConf.fromSparkConf(conf, "shuffle")

  // 此方法用于获取Shuffle数据文件。其实质上是调用BlockManager的子组件DiskBlockManager的getFile方法获取ShuffleDataBlockId
  def getDataFile(shuffleId: Int, mapId: Int): File = {
    blockManager.diskBlockManager.getFile(ShuffleDataBlockId(shuffleId, mapId, NOOP_REDUCE_ID))
  }

  // 此方法用于获取Shuffle索引文件。其实质是调用BlockManager的子组件DiskBlockManager的getFile方法获取ShuffleIndexBlockId
  private def getIndexFile(shuffleId: Int, mapId: Int): File = {
    blockManager.diskBlockManager.getFile(ShuffleIndexBlockId(shuffleId, mapId, NOOP_REDUCE_ID))
  }

  /**
   * Remove data file and index file that contain the output data from one map.
   * */
  // 此方法用于删除Shuffle过程中包含指定map任务输出数据的Shuffle数据文件和索引文件
  def removeDataByMap(shuffleId: Int, mapId: Int): Unit = {
    // 获取指定Shuffle中指定map任务输出的数据文件，然后删除
    var file = getDataFile(shuffleId, mapId)
    if (file.exists()) {
      if (!file.delete()) {
        logWarning(s"Error deleting data ${file.getPath()}")
      }
    }

    // 获取指定Shuffle中指定map任务输出的索引文件，然后删除
    file = getIndexFile(shuffleId, mapId)
    if (file.exists()) {
      if (!file.delete()) {
        logWarning(s"Error deleting index ${file.getPath()}")
      }
    }
  }

  /**
   * Check whether the given index and data files match each other.
   * If so, return the partition lengths in the data file. Otherwise return null.
   */
  private def checkIndexAndDataFile(index: File, data: File, blocks: Int): Array[Long] = {
    // the index file should have `block + 1` longs as offset.
    if (index.length() != (blocks + 1) * 8) {
      return null
    }
    val lengths = new Array[Long](blocks)
    // Read the lengths of blocks
    val in = try {
      new DataInputStream(new NioBufferedFileInputStream(index))
    } catch {
      case e: IOException =>
        return null
    }
    try {
      // Convert the offsets into lengths of each block
      var offset = in.readLong()
      if (offset != 0L) {
        return null
      }
      var i = 0
      while (i < blocks) {
        val off = in.readLong()
        lengths(i) = off - offset
        offset = off
        i += 1
      }
    } catch {
      case e: IOException =>
        return null
    } finally {
      in.close()
    }

    // the size of data file should match with index file
    if (data.length() == lengths.sum) {
      lengths
    } else {
      null
    }
  }

  /**
   * Write an index file with the offsets of each block, plus a final offset at the end for the
   * end of the output file. This will be used by getBlockData to figure out where each block
   * begins and ends.
   *
   * It will commit the data and index file as an atomic operation, use the existing ones, or
   * replace them with new ones.
   *
   * Note: the `lengths` will be updated to match the existing index file if use the existing ones.
   * */
  // 此方法用于将每个Block的偏移量写入索引文件，并在最后增加一个表示输出文件末尾的偏移量
  def writeIndexFileAndCommit(
      shuffleId: Int,
      mapId: Int,
      lengths: Array[Long],
      dataTmp: File): Unit = {
    // 获取指定Shuffle中指定map任务输出的索引文件
    val indexFile = getIndexFile(shuffleId, mapId)
    // 根据索引文件获取临时索引文件的路径
    val indexTmp = Utils.tempFileWith(indexFile)
    try {
      val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(indexTmp)))
      Utils.tryWithSafeFinally {
        // We take in lengths of each block, need to convert it to offsets.
        var offset = 0L
        out.writeLong(offset)
        // 遍历每个Block的长度，并作为偏移量写入临时索引文件
        for (length <- lengths) {
          offset += length
          out.writeLong(offset)
        }
      } {
        out.close()
      }

      // 获取指定Shuffle中指定map任务输出的数据文件
      val dataFile = getDataFile(shuffleId, mapId)
      // There is only one IndexShuffleBlockResolver per executor, this synchronization make sure
      // the following check and rename are atomic.
      synchronized {
        // 对给定的索引文件和数据文件是否匹配进行检查。checkIndexAndDataFile方法最后将返回索引文件中各个partition的长度数据existingLengths
        val existingLengths = checkIndexAndDataFile(indexFile, dataFile, lengths.length)
        if (existingLengths != null) {
          // Another attempt for the same task has already written our map outputs successfully,
          // so just use the existing partition lengths and delete our temporary map outputs.
          // 如果existingLengths不等于null，则将existingLengths更新到调用writeIndexFileAndCommit方法时传入的lengths数组，
          System.arraycopy(existingLengths, 0, lengths, 0, lengths.length)
          // 并将临时索引文件indexTmp和临时数据文件dataTmp删除
          if (dataTmp != null && dataTmp.exists()) {
            dataTmp.delete()
          }
          indexTmp.delete()
        } else {
          // 索引文件和数据文件匹配，将临时的索引文件和数据文件作为正式的索引文件和数据文件
          // This is the first successful attempt in writing the map outputs for this task,
          // so override any existing index and data files with the ones we wrote.
          // 如果existingLengths等于null，则说明是为map任务中间结果输出的第一次成功尝试，
          // 因而需要将indexTmp重命名为indexFile，将dataTmp重命名为dataFile
          if (indexFile.exists()) {
            indexFile.delete()
          }
          if (dataFile.exists()) {
            dataFile.delete()
          }
          if (!indexTmp.renameTo(indexFile)) {
            throw new IOException("fail to rename file " + indexTmp + " to " + indexFile)
          }
          if (dataTmp != null && dataTmp.exists() && !dataTmp.renameTo(dataFile)) {
            throw new IOException("fail to rename file " + dataTmp + " to " + dataFile)
          }
        }
      }
    } finally {
      if (indexTmp.exists() && !indexTmp.delete()) {
        logError(s"Failed to delete temporary index file at ${indexTmp.getAbsolutePath}")
      }
    }
  }

  // 用于获取指定的ShuffleBlockId对应的数据
  override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
    // The block is actually going to be a range of a single map output file for this map, so
    // find out the consolidated file, then the offset within that from our index
    // 调用getIndexFile方法获取指定Shuffle中指定map任务输出的索引文件
    val indexFile = getIndexFile(blockId.shuffleId, blockId.mapId)

    // 读取indexFile的输入流
    val in = new DataInputStream(new FileInputStream(indexFile))
    try {
      // 由于索引文件中按顺序写入了每个reduce任务所需的map中间输出数据文件的偏移量，
      // 因此跳过与当前ShuffleBlockId对应的reduce任务无关的字节
      ByteStreams.skipFully(in, blockId.reduceId * 8)

      // 读入索引文件中当前ShuffleBlockId对应的reduce任务所需map数据的偏移量和长度，
      // 构造并返回FileSegmentManagerBuffer
      val offset = in.readLong()
      val nextOffset = in.readLong()
      new FileSegmentManagedBuffer(
        transportConf,
        getDataFile(blockId.shuffleId, blockId.mapId),
        offset,
        nextOffset - offset)
    } finally {
      in.close()
    }
  }

  override def stop(): Unit = {}
}

private[spark] object IndexShuffleBlockResolver {
  // No-op reduce ID used in interactions with disk store.
  // The disk store currently expects puts to relate to a (map, reduce) pair, but in the sort
  // shuffle outputs for several reduces are glommed into a single file.
  val NOOP_REDUCE_ID = 0
}
