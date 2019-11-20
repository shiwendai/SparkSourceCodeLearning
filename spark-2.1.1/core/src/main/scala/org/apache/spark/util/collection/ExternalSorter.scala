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

package org.apache.spark.util.collection

import java.io._
import java.util.Comparator

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.google.common.io.ByteStreams

import org.apache.spark._
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.serializer._
import org.apache.spark.storage.{BlockId, DiskBlockObjectWriter}

/**
 * Sorts and potentially merges a number of key-value pairs of type (K, V) to produce key-combiner
 * pairs of type (K, C). Uses a Partitioner to first group the keys into partitions, and then
 * optionally sorts keys within each partition using a custom Comparator. Can output a single
 * partitioned file with a different byte range for each partition, suitable for shuffle fetches.
 *
 * If combining is disabled, the type C must equal V -- we'll cast the objects at the end.
 *
 * Note: Although ExternalSorter is a fairly generic sorter, some of its configuration is tied
 * to its use in sort-based shuffle (for example, its block compression is controlled by
 * `spark.shuffle.compress`).  We may need to revisit this if ExternalSorter is used in other
 * non-shuffle contexts where we might want to use different configuration settings.
 *
 * @param aggregator optional Aggregator with combine functions to use for merging data
 * @param partitioner optional Partitioner; if given, sort by partition ID and then key
 * @param ordering optional Ordering to sort keys within each partition; should be a total ordering
 * @param serializer serializer to use when spilling to disk
 *
 * Note that if an Ordering is given, we'll always sort using it, so only provide it if you really
 * want the output keys to be sorted. In a map task without map-side combine for example, you
 * probably want to pass None as the ordering to avoid extra sorting. On the other hand, if you do
 * want to do combining, having an Ordering is more efficient than not having it.
 *
 * Users interact with this class in the following way:
 *
 * 1. Instantiate an ExternalSorter.
 *
 * 2. Call insertAll() with a set of records.
 *
 * 3. Request an iterator() back to traverse sorted/aggregated records.
 *     - or -
 *    Invoke writePartitionedFile() to create a file containing sorted/aggregated outputs
 *    that can be used in Spark's sort shuffle.
 *
 * At a high level, this class works internally as follows:
 *
 *  - We repeatedly fill up buffers of in-memory data, using either a PartitionedAppendOnlyMap if
 *    we want to combine by key, or a PartitionedPairBuffer if we don't.
 *    Inside these buffers, we sort elements by partition ID and then possibly also by key.
 *    To avoid calling the partitioner multiple times with each key, we store the partition ID
 *    alongside each record.
 *
 *  - When each buffer reaches our memory limit, we spill it to a file. This file is sorted first
 *    by partition ID and possibly second by key or by hash code of the key, if we want to do
 *    aggregation. For each file, we track how many objects were in each partition in memory, so we
 *    don't have to write out the partition ID for every element.
 *
 *  - When the user requests an iterator or file output, the spilled files are merged, along with
 *    any remaining in-memory data, using the same sort order defined above (unless both sorting
 *    and aggregation are disabled). If we need to aggregate by key, we either use a total ordering
 *    from the ordering parameter, or read the keys with the same hash code and compare them with
 *    each other for equality to merge values.
 *
 *  - Users are expected to call stop() at the end to delete all the intermediate files.
 */
// Spark中的外部排序器用于对map任务的输出数据在map端或reduce端进行排序,Spark中有两个外部排序器分别是ExternalSorter和ShuffleExeternalSorter

// ExternalSorter是SortShuffleManager的底层组件，它提供了很多功能，包括将map任务的输出存储到JVM的堆中，如果指定了聚合函数，
// 则还会对数据进行聚合；使用分区计算器首先将Key分组到各个分区中，然后使用自定义比较器对每个分区中的键进行可选的排序；
// 可以将每个分区输出到单个文件的不同字节范围中，便于reduce端的Shuffle获取
private[spark] class ExternalSorter[K, V, C](
    context: TaskContext,
    aggregator: Option[Aggregator[K, V, C]] = None,// 对map任务的输出数据进行聚合的聚合函数
    partitioner: Option[Partitioner] = None,// 对map任务的输出数据按照key计算分区的分区计算器Partitioner
    ordering: Option[Ordering[K]] = None,// 对map任务的输出数据按照key进行排序的scala.math.Ordering的实现类
    serializer: Serializer = SparkEnv.get.serializer)
  extends Spillable[WritablePartitionedPairCollection[K, C]](context.taskMemoryManager())
  with Logging {

  // SparkConf
  private val conf = SparkEnv.get.conf

  // 分区数量。默认为1
  private val numPartitions = partitioner.map(_.numPartitions).getOrElse(1)
  // 是否有分区
  private val shouldPartition = numPartitions > 1
  // 计算key的分区号
  private def getPartition(key: K): Int = {
    if (shouldPartition) partitioner.get.getPartition(key) else 0
  }

  // 即SparkEnv的子组件BlockManager
  private val blockManager = SparkEnv.get.blockManager
  // 即BlockManager的子组件DiskBlockManager
  private val diskBlockManager = blockManager.diskBlockManager
  // 即SparkEnv的子组件SerializerManager
  private val serializerManager = SparkEnv.get.serializerManager
  private val serInstance = serializer.newInstance()

  // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
  // 用于设置DiskBlockObjectWriter内部的文件缓存大小
  private val fileBufferSize = conf.getSizeAsKb("spark.shuffle.file.buffer", "32k").toInt * 1024

  // Size of object batches when reading/writing from serializers.
  //
  // Objects are written in batches, with each batch using its own serialization stream. This
  // cuts down on the size of reference-tracking maps constructed when deserializing a stream.
  //
  // NOTE: Setting this too low can cause excessive copying when serializing, since some serializers
  // grow internal data structures by growing + copying every time the number of objects doubles.
  // 用于将DiskBlockObjectWriter内部的文件缓存写到磁盘的大小
  private val serializerBatchSize = conf.getLong("spark.shuffle.spill.batchSize", 10000)

  // Data structures to store in-memory objects before we spill. Depending on whether we have an
  // Aggregator set, we either put objects into an AppendOnlyMap where we combine them, or we
  // store them in an array buffer.
  // 当设置了聚合器(Aggregator)时，map端将中间结果溢出到磁盘前，先利用此数据结构将中间结果存储在内存中。
  @volatile private var map = new PartitionedAppendOnlyMap[K, C]
  // 当没有设置聚合器(Aggregator)时，map端将中间结果溢出到磁盘前，先利用此数据结构将此中间结果存储在内存中
  @volatile private var buffer = new PartitionedPairBuffer[K, C]

  // Total spilling statistics
  // 对于溢出到磁盘的字节数进行统计(单位字节)。
  private var _diskBytesSpilled = 0L
  // diskBytesSpilled方法专门用于返回_diskBytesSpilled的值
  def diskBytesSpilled: Long = _diskBytesSpilled

  // Peak size of the in-memory data structure observed so far, in bytes
  // 内存中数据结构大小的峰值
  private var _peakMemoryUsedBytes: Long = 0L
  // peakMemoryUsedBytes方法专门用于返回_peakMemoryUsedBytes的值
  def peakMemoryUsedBytes: Long = _peakMemoryUsedBytes

  // 是否对Shuffle数据进行排序
  @volatile private var isShuffleSort: Boolean = true
  // 缓存强制溢出的文件数组。SpillFile保存了溢出文件的信息，
  // 包括file(文件)、blockId(BlockId)、serializerBatchSize、elementsPerPartition(每个分区的元素数量)
  private val forceSpillFiles = new ArrayBuffer[SpilledFile]
  // 用于包装内存中数据的迭代器和溢出文件，并表现为一个新的迭代器
  @volatile private var readingIterator: SpillableIterator = null

  // A comparator for keys K that orders them within a partition to allow aggregation or sorting.
  // Can be a partial ordering by hash code if a total ordering is not provided through by the
  // user. (A partial ordering means that equal keys have comparator.compare(k, k) = 0, but some
  // non-equal keys also have this, so we need to do a later pass to find truly equal keys).
  // Note that we ignore this if no aggregator and no ordering are given.
  // 中间输出的key的比较器。keyComparator的类型为Comparator[K],用于在分区内对中间结果按照key进行排序，以便于聚合。
  // 根据keyComparator的定义，当用户没有指定ordering时，将会创建一个按照Key的哈希值进行比较的默认比较器。以为不同的
  // key值也可能有相同的哈希值，因此默认的比较器会工作不正常。此比较器经常作为partitionComparator和partitionKeyComparator
  // 所需要的key值比较器。
  private val keyComparator: Comparator[K] = ordering.getOrElse(new Comparator[K] {
    override def compare(a: K, b: K): Int = {
      val h1 = if (a == null) 0 else a.hashCode()
      val h2 = if (b == null) 0 else b.hashCode()
      if (h1 < h2) -1 else if (h1 == h2) 0 else 1
    }
  })

  private def comparator: Option[Comparator[K]] = {
    if (ordering.isDefined || aggregator.isDefined) {
      Some(keyComparator)
    } else {
      None
    }
  }

  // Information about a spilled file. Includes sizes in bytes of "batches" written by the
  // serializer as we periodically reset its stream, as well as number of elements in each
  // partition, used to efficiently keep track of partitions when merging.
  private[this] case class SpilledFile(
    file: File,
    blockId: BlockId,
    serializerBatchSizes: Array[Long],
    elementsPerPartition: Array[Long])

  // 缓存溢出的文件数组
  private val spills = new ArrayBuffer[SpilledFile]

  /**
   * Number of files this sorter has spilled so far.
   * Exposed for testing.
   */
  // 此方法用于返回spills的大小，即溢出的文件数量
  private[spark] def numSpills: Int = spills.size

  // map任务在执行结束后会将数据写入磁盘，等待reduce任务获取。但在写入磁盘之前，Spark可能会对map任务的输出在内存中进行一些排序和聚合。
  // ExternalSorter的insertAll方法是这一过程的入口
  def insertAll(records: Iterator[Product2[K, V]]): Unit = {
    // TODO: stop combining if we find that the reduction factor isn't high
    val shouldCombine = aggregator.isDefined

    if (shouldCombine) {
      // 如果用户指定了聚合函数，则执行如下操作
      // Combine values in-memory first using our AppendOnlyMap
      // 获取聚合器的mergeValue函数(此函数用于将新的Value合并到聚合的结果中)
      val mergeValue = aggregator.get.mergeValue
      // 获取聚合器的createCombiner函数（此函数用于创建聚合的初始值）
      val createCombiner = aggregator.get.createCombiner
      var kv: Product2[K, V] = null

      // 定义偏函数update.此函数的作用是当有新的Value时，调用mergeValue函数将新的Value合并到之前聚合的结果中，
      // 否则说明刚刚开始聚合，此时调用createCombiner函数以Value作为聚合的初始值
      val update = (hadValue: Boolean, oldValue: C) => {
        if (hadValue) mergeValue(oldValue, kv._2) else createCombiner(kv._2)
      }
      // 迭代输入的记录
      while (records.hasNext) {
        // 首先调用父类Spillable的addElementsRead方法增加已经读取的元素数
        addElementsRead()
        // 获取数据,类型是Product2的KV键值对
        kv = records.next()
        // 对每个scala.Product2[K,V]的key通过调用getPartition方法计算分区索引，并将分区索引与key作为调用AppendOnlyMap的
        // changeValue方法的参数key，以偏函数update作为changeValue方法的参数updateFunc，对由分区索引与key组成的对偶进行聚合。
        map.changeValue((getPartition(kv._1), kv._1), update)
        // 调用maybeSpillCollection方法进行可能的磁盘溢出
        maybeSpillCollection(usingMap = true)
      }
    } else {
      // 如果用户没有指定聚合器
      // Stick values into our buffer
      // 对迭代器中的记录进行迭代，并在每次迭代过程中执行如下操作。
      while (records.hasNext) {
        // 首先调用父类Spillable的addElementsRead方法增加已经读取的元素数
        addElementsRead()
        val kv = records.next()
        // 对每个scala.Product2[K,V]的key通过调用getPartition方法计算分区索引，并将索引、key 及 value作为调用
        // PartitionedPairBuffer的insert方法的参数
        buffer.insert(getPartition(kv._1), kv._1, kv._2.asInstanceOf[C])
        // 调用maybeSpillCollection方法进行可能的磁盘溢出
        maybeSpillCollection(usingMap = false)
      }
    }
  }

  /**
   * Spill the current in-memory collection to disk if needed.
   *
   * @param usingMap whether we're using a map or buffer as our current in-memory collection
   */
  // External的map属性的类型为PartitionedAppendOnlyMap[K,C],buffer属性的类型是PartitionedPairBuffer[K,C].
  // 既然ExternalSorter使用了AppendOnlyMap和PartitionedPairBuffer，且这两种数据结构的容量都可以增长，那么数据量
  // 不大的时候不会有问题。由于大数据处理的数据量往往都很大，全部都放入内存将很容易引起系统的OOM问题。另一方面，map任务
  // 的输出需要写入磁盘，磁盘写入频率过高会因为大量的此案I/O降低效率，那么何时才应该将内存中的数据写入到磁盘呢？Spark
  // 为了解决这两个问题，提供了maybeSpillCollection方法。以判断何时将内存中的数据写入磁盘
  private def maybeSpillCollection(usingMap: Boolean): Unit = {
    var estimatedSize = 0L
    if (usingMap) { // 如果使用了PartitionedAppendOnlyMap
      // 对PartitionedAppendOnlyMap的大小进行估算
      estimatedSize = map.estimateSize()
      // 将PartitionedAppendOnlyMap中的数据溢出到磁盘
      if (maybeSpill(map, estimatedSize)) {
        // 重新创建PartitionedAppendOnlyMap
        map = new PartitionedAppendOnlyMap[K, C]
      }
    } else { // 如果使用了PartitionedPairBuffer
      // 对PartitionedPairBuffer的大小进行估算
      estimatedSize = buffer.estimateSize()
      // 将PartitionedPairBuffer中的数据溢出到磁盘
      if (maybeSpill(buffer, estimatedSize)) {
        // 重新创建PartitionedPairBuffer
        buffer = new PartitionedPairBuffer[K, C]
      }
    }

    // 更新ExternalSorter已经使用内存大小的峰值
    if (estimatedSize > _peakMemoryUsedBytes) {
      _peakMemoryUsedBytes = estimatedSize
    }
  }

  /**
   * Spill our in-memory collection to a sorted file that we can merge later.
   * We add this file into `spilledFiles` to find it later.
   *
   * @param collection whichever collection we're using (map or buffer)
   */
  // ExternalSorter实现了父类Spillable定义的spill接口，spill方法的参数为WritablePartitionedPairCollection.
  // 由于PartitionedAppendOnlyMap和PartitionedPairBuffer都实现了特质WritablePartitionedPairCollection，
  // 因此spill方法对这两种数据结构都适用。
  override protected[this] def spill(collection: WritablePartitionedPairCollection[K, C]): Unit = {
    // 获取WritablePartitionEdIterator，如果定义了ordering或aggregator，那么比较器就是keyComparator，否则没有比较器
    val inMemoryIterator = collection.destructiveSortedWritablePartitionedIterator(comparator)
    // 调用spillMemoryIteratorToDisk方法，通过WritablePartitionEdIterator将集合中的数据溢出到磁盘
    val spillFile = spillMemoryIteratorToDisk(inMemoryIterator)
    // 将溢出生成的文件添加到spills中
    spills += spillFile
  }

  /**
   * Force to spilling the current in-memory collection to disk to release memory,
   * It will be called by TaskMemoryManager when there is not enough memory for the task.
   */
  override protected[this] def forceSpill(): Boolean = {
    if (isShuffleSort) {
      false
    } else {
      assert(readingIterator != null)
      val isSpilled = readingIterator.spill()
      if (isSpilled) {
        map = null
        buffer = null
      }
      isSpilled
    }
  }

  /**
   * Spill contents of in-memory iterator to a temporary file on disk.
   */
  // 将内存中迭代器的数据溢出到磁盘上的临时文件中。
  private[this] def spillMemoryIteratorToDisk(inMemoryIterator: WritablePartitionedIterator)
      : SpilledFile = {
    // Because these files may be read during shuffle, their compression must be controlled by
    // spark.shuffle.compress instead of spark.shuffle.spill.compress, so we need to use
    // createTempShuffleBlock here; see SPARK-3426 for more context.
    // 调用DiskBlockManager的createTempShuffleBlock方法创建唯一的BlockId和文件。
    val (blockId, file) = diskBlockManager.createTempShuffleBlock()

    // These variables are reset after each flush
    // 用于统计已经写入磁盘的键值对数量
    var objectsWritten: Long = 0
    // 用于对Shuffle中间结果写入到磁盘的度量与统计
    val spillMetrics: ShuffleWriteMetrics = new ShuffleWriteMetrics
    // 获取DiskBlockObjectWriter
    val writer: DiskBlockObjectWriter =
      blockManager.getDiskWriter(blockId, file, serInstance, fileBufferSize, spillMetrics)

    // List of batch sizes (bytes) in the order they are written to disk
    // 创建存储批次大小的数组缓冲batchSizes
    val batchSizes = new ArrayBuffer[Long]

    // How many elements we have in each partition
    // 创建用于存储每个分区有多少个元素的数组缓冲elementsPerPartition
    val elementsPerPartition = new Array[Long](numPartitions)

    // Flush the disk writer's contents to disk, and update relevant variables.
    // The writer is committed at the end of this process.
    def flush(): Unit = {
      val segment = writer.commitAndGet()
      batchSizes += segment.length
      _diskBytesSpilled += segment.length
      objectsWritten = 0
    }

    var success = false
    try {
      // 对WritablePartitionedIterator进行迭代
      while (inMemoryIterator.hasNext) {
        // 获取数据的分区ID
        val partitionId = inMemoryIterator.nextPartition()
        require(partitionId >= 0 && partitionId < numPartitions,
          s"partition Id: ${partitionId} should be in the range [0, ${numPartitions})")
        // 以DiskBlockObjectWriter为参数，调用WritablePartitionedIterator的writeNext方法将键值对写入磁盘
        inMemoryIterator.writeNext(writer)
        // 将elementsPerPartition中统计的分区对应的元素数量加1
        elementsPerPartition(partitionId) += 1
        // 将objectsWritten加1
        objectsWritten += 1

        // 如果objectsWritten == serializerBatchSize，则调用flush方法首先将DiskBlockObjectWriter的输出流中的数据
        // 真正写入到磁盘，然后将本批次写入的文件长度添加到batchSizes和_diskBytesSpilled中，最后将ObjectsWritten清零。
        // 以便下一批次的正确执行
        if (objectsWritten == serializerBatchSize) {
          flush()
        }
      }
      if (objectsWritten > 0) {
        flush()
      } else {
        writer.revertPartialWritesAndClose()
      }
      // 最后将succes设置为true
      success = true
    } finally {
      if (success) {
        writer.close()
      } else {
        // This code path only happens if an exception was thrown above before we set success;
        // close our stuff and let the exception be thrown further
        writer.revertPartialWritesAndClose()
        if (file.exists()) {
          if (!file.delete()) {
            logWarning(s"Error deleting ${file}")
          }
        }
      }
    }

    // 最后返回SpilledFile
    SpilledFile(file, blockId, batchSizes.toArray, elementsPerPartition)
  }

  /**
   * Merge a sequence of sorted files, giving an iterator over partitions and then over elements
   * inside each partition. This can be used to either write out a new file or return data to
   * the user.
   *
   * Returns an iterator over all the data written to this object, grouped by partition. For each
   * partition we then have an iterator over its contents, and these are expected to be accessed
   * in order (you can't "skip ahead" to one partition without reading the previous one).
   * Guaranteed to return a key-value pair for each partition, in order of partition ID.
   */
  // 此方法用于将destructiveIterator方法返回的可迭代访问内存中数据的迭代器与已经溢出到磁盘的文件进行合并
  private def merge(spills: Seq[SpilledFile], inMemory: Iterator[((Int, K), C)])
      : Iterator[(Int, Iterator[Product2[K, C]])] = {
    val readers = spills.map(new SpillReader(_))
    val inMemBuffered = inMemory.buffered
    (0 until numPartitions).iterator.map { p =>
      val inMemIterator = new IteratorForPartition(p, inMemBuffered)
      val iterators = readers.map(_.readNextPartition()) ++ Seq(inMemIterator)
      if (aggregator.isDefined) {
        // Perform partial aggregation across partitions
        (p, mergeWithAggregation(
          iterators, aggregator.get.mergeCombiners, keyComparator, ordering.isDefined))
      } else if (ordering.isDefined) {
        // No aggregator given, but we have an ordering (e.g. used by reduce tasks in sortByKey);
        // sort the elements without trying to merge them
        (p, mergeSort(iterators, ordering.get))
      } else {
        (p, iterators.iterator.flatten)
      }
    }
  }

  /**
   * Merge-sort a sequence of (K, C) iterators using a given a comparator for the keys.
   */
  private def mergeSort(iterators: Seq[Iterator[Product2[K, C]]], comparator: Comparator[K])
      : Iterator[Product2[K, C]] =
  {
    val bufferedIters = iterators.filter(_.hasNext).map(_.buffered)
    type Iter = BufferedIterator[Product2[K, C]]
    val heap = new mutable.PriorityQueue[Iter]()(new Ordering[Iter] {
      // Use the reverse of comparator.compare because PriorityQueue dequeues the max
      override def compare(x: Iter, y: Iter): Int = -comparator.compare(x.head._1, y.head._1)
    })
    heap.enqueue(bufferedIters: _*)  // Will contain only the iterators with hasNext = true
    new Iterator[Product2[K, C]] {
      override def hasNext: Boolean = !heap.isEmpty

      override def next(): Product2[K, C] = {
        if (!hasNext) {
          throw new NoSuchElementException
        }
        val firstBuf = heap.dequeue()
        val firstPair = firstBuf.next()
        if (firstBuf.hasNext) {
          heap.enqueue(firstBuf)
        }
        firstPair
      }
    }
  }

  /**
   * Merge a sequence of (K, C) iterators by aggregating values for each key, assuming that each
   * iterator is sorted by key with a given comparator. If the comparator is not a total ordering
   * (e.g. when we sort objects by hash code and different keys may compare as equal although
   * they're not), we still merge them by doing equality tests for all keys that compare as equal.
   */
  private def mergeWithAggregation(
      iterators: Seq[Iterator[Product2[K, C]]],
      mergeCombiners: (C, C) => C,
      comparator: Comparator[K],
      totalOrder: Boolean)
      : Iterator[Product2[K, C]] =
  {
    if (!totalOrder) {
      // We only have a partial ordering, e.g. comparing the keys by hash code, which means that
      // multiple distinct keys might be treated as equal by the ordering. To deal with this, we
      // need to read all keys considered equal by the ordering at once and compare them.
      new Iterator[Iterator[Product2[K, C]]] {
        val sorted = mergeSort(iterators, comparator).buffered

        // Buffers reused across elements to decrease memory allocation
        val keys = new ArrayBuffer[K]
        val combiners = new ArrayBuffer[C]

        override def hasNext: Boolean = sorted.hasNext

        override def next(): Iterator[Product2[K, C]] = {
          if (!hasNext) {
            throw new NoSuchElementException
          }
          keys.clear()
          combiners.clear()
          val firstPair = sorted.next()
          keys += firstPair._1
          combiners += firstPair._2
          val key = firstPair._1
          while (sorted.hasNext && comparator.compare(sorted.head._1, key) == 0) {
            val pair = sorted.next()
            var i = 0
            var foundKey = false
            while (i < keys.size && !foundKey) {
              if (keys(i) == pair._1) {
                combiners(i) = mergeCombiners(combiners(i), pair._2)
                foundKey = true
              }
              i += 1
            }
            if (!foundKey) {
              keys += pair._1
              combiners += pair._2
            }
          }

          // Note that we return an iterator of elements since we could've had many keys marked
          // equal by the partial order; we flatten this below to get a flat iterator of (K, C).
          keys.iterator.zip(combiners.iterator)
        }
      }.flatMap(i => i)
    } else {
      // We have a total ordering, so the objects with the same key are sequential.
      new Iterator[Product2[K, C]] {
        val sorted = mergeSort(iterators, comparator).buffered

        override def hasNext: Boolean = sorted.hasNext

        override def next(): Product2[K, C] = {
          if (!hasNext) {
            throw new NoSuchElementException
          }
          val elem = sorted.next()
          val k = elem._1
          var c = elem._2
          while (sorted.hasNext && sorted.head._1 == k) {
            val pair = sorted.next()
            c = mergeCombiners(c, pair._2)
          }
          (k, c)
        }
      }
    }
  }

  /**
   * An internal class for reading a spilled file partition by partition. Expects all the
   * partitions to be requested in order.
   */
  private[this] class SpillReader(spill: SpilledFile) {
    // Serializer batch offsets; size will be batchSize.length + 1
    val batchOffsets = spill.serializerBatchSizes.scanLeft(0L)(_ + _)

    // Track which partition and which batch stream we're in. These will be the indices of
    // the next element we will read. We'll also store the last partition read so that
    // readNextPartition() can figure out what partition that was from.
    var partitionId = 0
    var indexInPartition = 0L
    var batchId = 0
    var indexInBatch = 0
    var lastPartitionId = 0

    skipToNextPartition()

    // Intermediate file and deserializer streams that read from exactly one batch
    // This guards against pre-fetching and other arbitrary behavior of higher level streams
    var fileStream: FileInputStream = null
    var deserializeStream = nextBatchStream()  // Also sets fileStream

    var nextItem: (K, C) = null
    var finished = false

    /** Construct a stream that only reads from the next batch */
    def nextBatchStream(): DeserializationStream = {
      // Note that batchOffsets.length = numBatches + 1 since we did a scan above; check whether
      // we're still in a valid batch.
      if (batchId < batchOffsets.length - 1) {
        if (deserializeStream != null) {
          deserializeStream.close()
          fileStream.close()
          deserializeStream = null
          fileStream = null
        }

        val start = batchOffsets(batchId)
        fileStream = new FileInputStream(spill.file)
        fileStream.getChannel.position(start)
        batchId += 1

        val end = batchOffsets(batchId)

        assert(end >= start, "start = " + start + ", end = " + end +
          ", batchOffsets = " + batchOffsets.mkString("[", ", ", "]"))

        val bufferedStream = new BufferedInputStream(ByteStreams.limit(fileStream, end - start))

        val wrappedStream = serializerManager.wrapStream(spill.blockId, bufferedStream)
        serInstance.deserializeStream(wrappedStream)
      } else {
        // No more batches left
        cleanup()
        null
      }
    }

    /**
     * Update partitionId if we have reached the end of our current partition, possibly skipping
     * empty partitions on the way.
     */
    private def skipToNextPartition() {
      while (partitionId < numPartitions &&
          indexInPartition == spill.elementsPerPartition(partitionId)) {
        partitionId += 1
        indexInPartition = 0L
      }
    }

    /**
     * Return the next (K, C) pair from the deserialization stream and update partitionId,
     * indexInPartition, indexInBatch and such to match its location.
     *
     * If the current batch is drained, construct a stream for the next batch and read from it.
     * If no more pairs are left, return null.
     */
    private def readNextItem(): (K, C) = {
      if (finished || deserializeStream == null) {
        return null
      }
      val k = deserializeStream.readKey().asInstanceOf[K]
      val c = deserializeStream.readValue().asInstanceOf[C]
      lastPartitionId = partitionId
      // Start reading the next batch if we're done with this one
      indexInBatch += 1
      if (indexInBatch == serializerBatchSize) {
        indexInBatch = 0
        deserializeStream = nextBatchStream()
      }
      // Update the partition location of the element we're reading
      indexInPartition += 1
      skipToNextPartition()
      // If we've finished reading the last partition, remember that we're done
      if (partitionId == numPartitions) {
        finished = true
        if (deserializeStream != null) {
          deserializeStream.close()
        }
      }
      (k, c)
    }

    var nextPartitionToRead = 0

    def readNextPartition(): Iterator[Product2[K, C]] = new Iterator[Product2[K, C]] {
      val myPartition = nextPartitionToRead
      nextPartitionToRead += 1

      override def hasNext: Boolean = {
        if (nextItem == null) {
          nextItem = readNextItem()
          if (nextItem == null) {
            return false
          }
        }
        assert(lastPartitionId >= myPartition)
        // Check that we're still in the right partition; note that readNextItem will have returned
        // null at EOF above so we would've returned false there
        lastPartitionId == myPartition
      }

      override def next(): Product2[K, C] = {
        if (!hasNext) {
          throw new NoSuchElementException
        }
        val item = nextItem
        nextItem = null
        item
      }
    }

    // Clean up our open streams and put us in a state where we can't read any more data
    def cleanup() {
      batchId = batchOffsets.length  // Prevent reading any other batch
      val ds = deserializeStream
      deserializeStream = null
      fileStream = null
      if (ds != null) {
        ds.close()
      }
      // NOTE: We don't do file.delete() here because that is done in ExternalSorter.stop().
      // This should also be fixed in ExternalAppendOnlyMap.
    }
  }

  /**
   * Returns a destructive iterator for iterating over the entries of this map.
   * If this iterator is forced spill to disk to release memory when there is not enough memory,
   * it returns pairs from an on-disk map.
   */
  def destructiveIterator(memoryIterator: Iterator[((Int, K), C)]): Iterator[((Int, K), C)] = {
    if (isShuffleSort) {
      memoryIterator
    } else {
      readingIterator = new SpillableIterator(memoryIterator)
      readingIterator
    }
  }

  /**
   * Return an iterator over all the data written to this object, grouped by partition and
   * aggregated by the requested aggregator. For each partition we then have an iterator over its
   * contents, and these are expected to be accessed in order (you can't "skip ahead" to one
   * partition without reading the previous one). Guaranteed to return a key-value pair for each
   * partition, in order of partition ID.
   *
   * For now, we just merge all the spilled files in once pass, but this can be modified to
   * support hierarchical merging.
   * Exposed for testing.
   */
  // 此方法通过对集合按照指定的比较器进行排序，并且按照分区ID分组，生成迭代器
  def partitionedIterator: Iterator[(Int, Iterator[Product2[K, C]])] = {
    val usingMap = aggregator.isDefined
    val collection: WritablePartitionedPairCollection[K, C] = if (usingMap) map else buffer

    if (spills.isEmpty) { // 如果spills中没有缓存溢出到磁盘的文件，即所有的数据依然都在内存中
      // Special case: if we have only in-memory data, we don't need to merge streams, and perhaps
      // we don't even need to sort by anything other than partition ID
      if (!ordering.isDefined) { // 对底层data数组中的数据只按照分区ID排序
        // The user hasn't requested sorted keys, so only sort by partition ID, not key
        groupByPartition(destructiveIterator(collection.partitionedDestructiveSortedIterator(None)))
      } else { // 对底层data数组中的数据按照分区ID和key排序
        // We do need to sort by both partition ID and key
        groupByPartition(destructiveIterator(
          collection.partitionedDestructiveSortedIterator(Some(keyComparator))))
      }
    } else {
      // 如果spills中缓存了溢出到磁盘的文件，即有些数据在内存中，有些数据已经溢出到了磁盘上
      // 将溢出的磁盘文件和data数组中的数据合并
      // Merge spilled and in-memory data
      merge(spills, destructiveIterator(
        collection.partitionedDestructiveSortedIterator(comparator)))
    }
  }

  /**
   * Return an iterator over all the data written to this object, aggregated by our aggregator.
   */
  def iterator: Iterator[Product2[K, C]] = {
    isShuffleSort = false
    partitionedIterator.flatMap(pair => pair._2)
  }

  /**
   * Write all the data added into this ExternalSorter into a file in the disk store. This is
   * called by the SortShuffleWriter.
   *
   * @param blockId block ID to write to. The index file will be blockId.name + ".index".
   * @return array of lengths, in bytes, of each partition of the file (used by map output tracker)
   */
  // 此方法用于持久化计算结果
  def writePartitionedFile(
      blockId: BlockId,
      outputFile: File): Array[Long] = {

    // Track location of each range in the output file
    // 创建对每个分区的长度进行跟踪的数组lengths
    val lengths = new Array[Long](numPartitions)
    // 调用BlockManager的getDiskWriter方法获取DiskBlockObjectWriter
    val writer = blockManager.getDiskWriter(blockId, outputFile, serInstance, fileBufferSize,
      context.taskMetrics().shuffleWriteMetrics)

    // (注意：可以看到当没有溢出文件时，将data数组中的数据写入磁盘的方式与缓存溢出非常相似)
    if (spills.isEmpty) { // 如果spills中没有缓存溢出到磁盘的文件，即所有的数据依然都在内存中
      // Case where we only have in-memory data
      // 获取当前使用的数据结构。
      val collection = if (aggregator.isDefined) map else buffer
      // 获得对PartitionedAppendOnlyMap或PartitionedPairBuffer底层data数组中数据进行迭代的
      // 迭代器WritablePartitionedIterator
      val it = collection.destructiveSortedWritablePartitionedIterator(comparator)
      // 通过对WritablePartitionedIterator的迭代，将PartitionedAppendOnlyMap或PartitionedPairBuffer
      // 底层data数组中数据按照分区ID分别写入到磁盘中，并将各分区的数据长度更新到lengths数组中。
      while (it.hasNext) {
        val partitionId = it.nextPartition()
        while (it.hasNext && it.nextPartition() == partitionId) {
          it.writeNext(writer)
        }
        val segment = writer.commitAndGet()
        lengths(partitionId) = segment.length
      }
    } else { // 如果spills中缓存了溢出到磁盘的文件，即有些数据在内存中，有些数据已经溢出到磁盘
      // We must perform merge-sort; get an iterator by partition and write everything directly.
      // 调用partitionedIterator方法，返回按照分区ID分组的迭代器
      for ((id, elements) <- this.partitionedIterator) {
        // 对每个分区ID对应的迭代器进行迭代，将各个元素写到磁盘
        if (elements.hasNext) {
          for (elem <- elements) {
            writer.write(elem._1, elem._2)
          }
          val segment = writer.commitAndGet()
          // 将各个分区的数据长度更新到lengths数组中
          lengths(id) = segment.length
        }
      }
    }

    // 关闭DiskBlockObjectWriter，更新任务度量信息
    writer.close()
    context.taskMetrics().incMemoryBytesSpilled(memoryBytesSpilled)
    context.taskMetrics().incDiskBytesSpilled(diskBytesSpilled)
    context.taskMetrics().incPeakExecutionMemory(peakMemoryUsedBytes)

    // 最后返回lengths数组
    lengths
  }

  def stop(): Unit = {
    spills.foreach(s => s.file.delete())
    spills.clear()
    forceSpillFiles.foreach(s => s.file.delete())
    forceSpillFiles.clear()
    if (map != null || buffer != null) {
      map = null // So that the memory can be garbage-collected
      buffer = null // So that the memory can be garbage-collected
      releaseMemory()
    }
  }

  /**
   * Given a stream of ((partition, key), combiner) pairs *assumed to be sorted by partition ID*,
   * group together the pairs for each partition into a sub-iterator.
   *
   * @param data an iterator of elements, assumed to already be sorted by partition ID
   */
  // 此方法用于对destructiveIterator方法返回的迭代器按照分区ID分组
  private def groupByPartition(data: Iterator[((Int, K), C)])
      : Iterator[(Int, Iterator[Product2[K, C]])] =
  {
    val buffered = data.buffered
    // groupByPartition方法实际给每个分区生成了一个IteratorForPartition.
    // 但是每个分区对应的IteratorForPartition实例都使用了相同的数据源，即有destructiveIterator方法返回的迭代器
    (0 until numPartitions).iterator.map(p => (p, new IteratorForPartition(p, buffered)))
  }

  /**
   * An iterator that reads only the elements for a given partition ID from an underlying buffered
   * stream, assuming this partition is the next one to be read. Used to make it easier to return
   * partitioned iterators from our in-memory collection.
   */
  private[this] class IteratorForPartition(partitionId: Int, data: BufferedIterator[((Int, K), C)])
    extends Iterator[Product2[K, C]]
  {
    // 此方法用判断，对于指定的分区ID是否有下一个元素的条件：
    // ①.data本身需要有下一个元素
    // ②.data的下一个元素对应的分区ID要与指定的分区ID一样
    // 假如下一个元素对应的分区ID如果与指定的分区ID不一样，但是data中还有其他此分区的元素，怎么办？
    // 根据之前对WritablePartitionedPairCollection的partitionedDestructiveSortedIterator方法的实现分析，
    // 我们知道此时data中的元素已经按照分区ID排好序了，所以不会出现这种让人担忧的情况。
    override def hasNext: Boolean = data.hasNext && data.head._1._1 == partitionId

    override def next(): Product2[K, C] = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val elem = data.next()
      (elem._1._2, elem._2)
    }
  }

  private[this] class SpillableIterator(var upstream: Iterator[((Int, K), C)])
    extends Iterator[((Int, K), C)] {

    private val SPILL_LOCK = new Object()

    private var nextUpstream: Iterator[((Int, K), C)] = null

    private var cur: ((Int, K), C) = readNext()

    private var hasSpilled: Boolean = false

    def spill(): Boolean = SPILL_LOCK.synchronized {
      if (hasSpilled) {
        false
      } else {
        val inMemoryIterator = new WritablePartitionedIterator {
          private[this] var cur = if (upstream.hasNext) upstream.next() else null

          def writeNext(writer: DiskBlockObjectWriter): Unit = {
            writer.write(cur._1._2, cur._2)
            cur = if (upstream.hasNext) upstream.next() else null
          }

          def hasNext(): Boolean = cur != null

          def nextPartition(): Int = cur._1._1
        }
        logInfo(s"Task ${context.taskAttemptId} force spilling in-memory map to disk and " +
          s" it will release ${org.apache.spark.util.Utils.bytesToString(getUsed())} memory")
        val spillFile = spillMemoryIteratorToDisk(inMemoryIterator)
        forceSpillFiles += spillFile
        val spillReader = new SpillReader(spillFile)
        nextUpstream = (0 until numPartitions).iterator.flatMap { p =>
          val iterator = spillReader.readNextPartition()
          iterator.map(cur => ((p, cur._1), cur._2))
        }
        hasSpilled = true
        true
      }
    }

    def readNext(): ((Int, K), C) = SPILL_LOCK.synchronized {
      if (nextUpstream != null) {
        upstream = nextUpstream
        nextUpstream = null
      }
      if (upstream.hasNext) {
        upstream.next()
      } else {
        null
      }
    }

    override def hasNext(): Boolean = cur != null

    override def next(): ((Int, K), C) = {
      val r = cur
      cur = readNext()
      r
    }
  }
}
