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

package org.apache.spark.broadcast

import java.io._
import java.nio.ByteBuffer
import java.util.zip.Adler32

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.Random

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.io.CompressionCodec
import org.apache.spark.serializer.Serializer
import org.apache.spark.storage.{BlockId, BroadcastBlockId, StorageLevel}
import org.apache.spark.util.{ByteBufferInputStream, Utils}
import org.apache.spark.util.io.{ChunkedByteBuffer, ChunkedByteBufferOutputStream}

/**
 * A BitTorrent-like implementation of [[org.apache.spark.broadcast.Broadcast]].
 *
 * The mechanism is as follows:
 *
 * The driver divides the serialized object into small chunks and
 * stores those chunks in the BlockManager of the driver.
 *
 * On each executor, the executor first attempts to fetch the object from its BlockManager. If
 * it does not exist, it then uses remote fetches to fetch the small chunks from the driver and/or
 * other executors if available. Once it gets the chunks, it puts the chunks in its own
 * BlockManager, ready for other executors to fetch from.
 *
 * This prevents the driver from being the bottleneck in sending out multiple copies of the
 * broadcast data (one per executor).
 *
 * When initialized, TorrentBroadcast objects read SparkEnv.get.conf.
 *
 * @param obj object to broadcast
 * @param id A unique identifier for the broadcast variable.
 */
private[spark] class TorrentBroadcast[T: ClassTag](obj: T, id: Long)
  extends Broadcast[T](id) with Logging with Serializable {

  /**
   * Value of the broadcast object on executors. This is reconstructed by [[readBroadcastBlock]],
   * which builds this value by reading blocks from the driver and/or other executors.
   *
   * On the driver, if the value is required, it is read lazily from the block manager.
   */

    // 从Executor或者Driver上读取的广播块的值。_value是通过调用readBroadcastBlock方法获得广播对象。
    // 由于_value是个lazy及val修饰的属性，因此在构造TorrentBroadcast实例的时候不会调用readBroadcastBlock方法，
    // 而是等到明确需要使用_value的值
  @transient private lazy val _value: T = readBroadcastBlock()

  /** The compression codec to use, or None if compression is disabled */
  // 用于广播对象的压缩编码器。
  @transient private var compressionCodec: Option[CompressionCodec] = _

  /** Size of each block. Default value is 4MB.  This value is only read by the broadcaster. */
  // 每个块的大小，它是只读属性，默认4M
  @transient private var blockSize: Int = _

  private def setConf(conf: SparkConf) {
    compressionCodec = if (conf.getBoolean("spark.broadcast.compress", true)) {
      Some(CompressionCodec.createCodec(conf))
    } else {
      None
    }
    // Note: use getSizeAsKb (not bytes) to maintain compatibility if no units are provided
    blockSize = conf.getSizeAsKb("spark.broadcast.blockSize", "4m").toInt * 1024
    checksumEnabled = conf.getBoolean("spark.broadcast.checksum", true)
  }
  setConf(SparkEnv.get.conf)

  // 广播Id,broadcastId实际是样例类BroadcastBlockId，其中，Id是由BroadManager的原子变量nextBroadcastId自增产生的
  private val broadcastId = BroadcastBlockId(id)

  // 广播变量包含的块的数量，由于numBlocks是个val修饰的不可变属性，因此在构造TorrentBroadcast实例的时候会
  // 调用writeBlocks方法将广播对象写入存储体系
  /** Total number of blocks this broadcast variable contains. */
  private val numBlocks: Int = writeBlocks(obj)

  // 是否给广播块生成校验和
  /** Whether to generate checksum for blocks or not. */
  private var checksumEnabled: Boolean = false

  // 用于存储每个广播块的校验和的数组
  /** The checksum for all the blocks. */
  private var checksums: Array[Int] = _

  override protected def getValue() = {
    _value
  }

  private def calcChecksum(block: ByteBuffer): Int = {
    val adler = new Adler32()
    if (block.hasArray) {
      adler.update(block.array, block.arrayOffset + block.position, block.limit - block.position)
    } else {
      val bytes = new Array[Byte](block.remaining())
      block.duplicate.get(bytes)
      adler.update(bytes)
    }
    adler.getValue.toInt
  }

  /**
   * Divide the object into multiple blocks and put those blocks in the block manager.
   *
   * @param value the object to divide
   * @return number of blocks this broadcast variable is divided into
   */
  private def writeBlocks(value: T): Int = {
    import StorageLevel._
    // Store a copy of the broadcast variable in the driver so that tasks run on the driver
    // do not create a duplicate copy of the broadcast variable's value.
    // 获取当前SparkEnv的BlockManager组件
    val blockManager = SparkEnv.get.blockManager
    // 调用BlockManager的putSingle方法将广播对象写入本地的存储体系。当Spark以local模式运行时，则会将
    // 广播对象写入Driver本地的存储体系,以便于任务也可在Driver上执行。由于MEMORY_AND_DISK对应的StorageLevel
    // 的_replication属性固定为1，因此此处只会将广播对象写入Driver或Executor本地存储体系。
    if (!blockManager.putSingle(broadcastId, value, MEMORY_AND_DISK, tellMaster = false)) {
      throw new SparkException(s"Failed to store $broadcastId in BlockManager")
    }

    // 调用TorrentBroadcast.blockifyObject方法，将对象转换成一系列的块。每个块的大小有blockSize决定，使用当前
    // SparkEnv中的JavaSerializer组件进行序列化，使用TorrentBroadcast自身的compressionCodec进行压缩
    val blocks =
      TorrentBroadcast.blockifyObject(value, blockSize, SparkEnv.get.serializer, compressionCodec)

    // 如果需要给分片广播块生成校验和，则创建blocks.length大小的checksums数组
    if (checksumEnabled) {
      checksums = new Array[Int](blocks.length)
    }

    // 对每个块进行如下处理
    blocks.zipWithIndex.foreach { case (block, i) =>
      if (checksumEnabled) {
        // 给分片广播块生成校验和
        checksums(i) = calcChecksum(block)
      }
      // 给当前分片广播块生成分片的BroadcastBlockId，
      val pieceId = BroadcastBlockId(id, "piece" + i)
      // 获取当前块数据
      val bytes = new ChunkedByteBuffer(block.duplicate())
      // 调用blockManager.putBytes方法将分片广播块以序列化方式写入Driver本地的存储体系。
      if (!blockManager.putBytes(pieceId, bytes, MEMORY_AND_DISK_SER, tellMaster = true)) {
        throw new SparkException(s"Failed to store $pieceId of $broadcastId in local BlockManager")
      }
    }
    // 返回块的数量
    blocks.length
  }

  // readBlocks()方法可以从Driver、Executor的存储体系中获取块
  /** Fetch torrent blocks from the driver and/or other executors. */
  private def readBlocks(): Array[ChunkedByteBuffer] = {
    // Fetch chunks of data. Note that all these chunks are stored in the BlockManager and reported
    // to the driver, so other executors can pull these chunks from this executor as well.
    // 获取数据块。 请注意，所有这些块都存储在BlockManager中并报告给driver，因此其他Executor也可以从此执行程序中提取这些块。

    // 新建用于存储每个分片广播的数组blocks，并获取当前SparkEnv的BlockManger组件
    val blocks = new Array[ChunkedByteBuffer](numBlocks)
    val bm = SparkEnv.get.blockManager

    // 对各个广播分片进行随机洗牌，避免对广播块的获取出现“热点”,提升性能。
    for (pid <- Random.shuffle(Seq.range(0, numBlocks))) {

      val pieceId = BroadcastBlockId(id, "piece" + pid)
      logDebug(s"Reading piece $pieceId of $broadcastId")
      // First try getLocalBytes because there is a chance that previous attempts to fetch the
      // broadcast blocks have already fetched some of the blocks. In that case, some blocks
      // would be available locally (on this executor).
      // 首先从本地的存储系统中获取序列化的分片广播块，如果本地可以获取到，则将分片广播块放入blocks,并且调用releaseLock释放此分片广播块的锁
      bm.getLocalBytes(pieceId) match {
        case Some(block) =>
          blocks(pid) = block
          releaseLock(pieceId)
        case None =>
          // 如果本地没有，则调用BlockManager的getRemoteBytes方法从远端的存储体系中获取分片广播块。
          bm.getRemoteBytes(pieceId) match {
            case Some(b) =>
              // 对于获取的分片广播块再次调用calcChecksum方法计算校验和，并将此校验和与调用writeBlock方法时存入的checksums数组的校验和进行比较
              if (checksumEnabled) {
                val sum = calcChecksum(b.chunks(0))
                // 如果不相等，说明块的数据有损坏，此时抛出异常
                if (sum != checksums(pid)) {
                  throw new SparkException(s"corrupt remote block $pieceId of $broadcastId:" +
                    s" $sum != ${checksums(pid)}")
                }
              }

              // 如果校验和相同，则调用BlockManger的putBytes方法将分片广播写入本地存储体系，以便于当前Executor的其他任务不用再次获取分片广播块。
              // We found the block from remote executors/driver's BlockManager, so put the block
              // in this executor's BlockManager.
              if (!bm.putBytes(pieceId, b, StorageLevel.MEMORY_AND_DISK_SER, tellMaster = true)) {
                throw new SparkException(
                  s"Failed to store $pieceId of $broadcastId in local BlockManager")
              }
              // 最后将分片广播块放入blocks中
              blocks(pid) = b
            case None =>
              throw new SparkException(s"Failed to get $pieceId of $broadcastId")
          }
      }
    }
    // 返回blocks中的所有分片广播块
    blocks
  }

  /**
   * Remove all persisted state associated with this Torrent broadcast on the executors.
   */
  override protected def doUnpersist(blocking: Boolean) {
    TorrentBroadcast.unpersist(id, removeFromDriver = false, blocking)
  }

  /**
   * Remove all persisted state associated with this Torrent broadcast on the executors
   * and driver.
   */
  override protected def doDestroy(blocking: Boolean) {
    TorrentBroadcast.unpersist(id, removeFromDriver = true, blocking)
  }

  /** Used by the JVM when serializing this object. */
  private def writeObject(out: ObjectOutputStream): Unit = Utils.tryOrIOException {
    assertValid()
    out.defaultWriteObject()
  }

  private def readBroadcastBlock(): T = Utils.tryOrIOException {
    TorrentBroadcast.synchronized {
      setConf(SparkEnv.get.conf)

      // 获取当前SparkEnv的BlockManager组件
      val blockManager = SparkEnv.get.blockManager

      // 调用BlockManager.getLocalValues方法从本地的存储系统中获取广播对象，即通过BlockManager的putSingle方法写入存储体系的广播对象，
      blockManager.getLocalValues(broadcastId) match {
        // 如果从本地的存储体系中可以获取广播对象，则调用releaseLock方法（这个锁保证当块被一个运行中的任务使用时，不能被其他任务再次使用，
        // 但是当任务完成时，则应该释放这个锁），释放当前块的锁并返回此广播对象
        case Some(blockResult) =>
          if (blockResult.data.hasNext) {
            val x = blockResult.data.next().asInstanceOf[T]
            releaseLock(broadcastId)
            x
          } else {
            throw new SparkException(s"Failed to get locally stored broadcast data: $broadcastId")
          }
        // 如果从本地的存储体系中没有获取到广播对象，那么说明数据是通过BlockManger的putBytes方法以序列化方式写入到存储体系的。
        case None =>
          logInfo("Started reading broadcast variable " + id)
          val startTimeMs = System.currentTimeMillis()

          // 此时首先调用readBlocks方法从Driver或Executor的存储体系中获取广播块
          val blocks = readBlocks().flatMap(_.getChunks())
          logInfo("Reading broadcast variable " + id + " took" + Utils.getUsedTimeMs(startTimeMs))

          // 然后调用TorrentBroadcast.unBlockifyObject方法，将一系列的分片广播块转换回原来的广播对象
          val obj = TorrentBroadcast.unBlockifyObject[T](
            blocks, SparkEnv.get.serializer, compressionCodec)
          // Store the merged copy in BlockManager so other tasks on this executor don't
          // need to re-fetch it.
          val storageLevel = StorageLevel.MEMORY_AND_DISK
          // 最后再次调用blockManager.putSingle方法将广播对象写入本地存储体系，以便于当前Executor的其他任务不用再次获取广播对象。
          if (!blockManager.putSingle(broadcastId, obj, storageLevel, tellMaster = false)) {
            throw new SparkException(s"Failed to store $broadcastId in BlockManager")
          }
          obj
      }
    }
  }

  /**
   * If running in a task, register the given block's locks for release upon task completion.
   * Otherwise, if not running in a task then immediately release the lock.
   */
  private def releaseLock(blockId: BlockId): Unit = {
    val blockManager = SparkEnv.get.blockManager
    Option(TaskContext.get()) match {
      case Some(taskContext) =>
        taskContext.addTaskCompletionListener(_ => blockManager.releaseLock(blockId))
      case None =>
        // This should only happen on the driver, where broadcast variables may be accessed
        // outside of running tasks (e.g. when computing rdd.partitions()). In order to allow
        // broadcast variables to be garbage collected we need to free the reference here
        // which is slightly unsafe but is technically okay because broadcast variables aren't
        // stored off-heap.
        blockManager.releaseLock(blockId)
    }
  }

}


private object TorrentBroadcast extends Logging {

  def blockifyObject[T: ClassTag](
      obj: T,
      blockSize: Int,
      serializer: Serializer,
      compressionCodec: Option[CompressionCodec]): Array[ByteBuffer] = {
    val cbbos = new ChunkedByteBufferOutputStream(blockSize, ByteBuffer.allocate)
    val out = compressionCodec.map(c => c.compressedOutputStream(cbbos)).getOrElse(cbbos)
    val ser = serializer.newInstance()
    val serOut = ser.serializeStream(out)
    Utils.tryWithSafeFinally {
      serOut.writeObject[T](obj)
    } {
      serOut.close()
    }
    cbbos.toChunkedByteBuffer.getChunks()
  }

  def unBlockifyObject[T: ClassTag](
      blocks: Array[ByteBuffer],
      serializer: Serializer,
      compressionCodec: Option[CompressionCodec]): T = {
    require(blocks.nonEmpty, "Cannot unblockify an empty array of blocks")
    val is = new SequenceInputStream(
      blocks.iterator.map(new ByteBufferInputStream(_)).asJavaEnumeration)
    val in: InputStream = compressionCodec.map(c => c.compressedInputStream(is)).getOrElse(is)
    val ser = serializer.newInstance()
    val serIn = ser.deserializeStream(in)
    val obj = Utils.tryWithSafeFinally {
      serIn.readObject[T]()
    } {
      serIn.close()
    }
    obj
  }

  /**
   * Remove all persisted blocks associated with this torrent broadcast on the executors.
   * If removeFromDriver is true, also remove these persisted blocks on the driver.
   */
  def unpersist(id: Long, removeFromDriver: Boolean, blocking: Boolean): Unit = {
    logDebug(s"Unpersisting TorrentBroadcast $id")
    SparkEnv.get.blockManager.master.removeBroadcast(id, removeFromDriver, blocking)
  }
}
