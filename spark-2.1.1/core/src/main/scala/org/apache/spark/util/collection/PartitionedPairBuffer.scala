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

import java.util.Comparator

import org.apache.spark.util.collection.WritablePartitionedPairCollection._


// 在将map任务的输出数据写入磁盘前，将数据临时存放的在内存中的两种数据结构AppendOnlyMay和PartitionedPairBuffer。
// AppendOnlyMap和PartitionedPairBuffer底层都使用数组存放元素，两者都有相似的容量增长实现，都有生成访问底层data
// 数组的迭代器方法，那么两者间有什么区别呢？
// 1.AppendOnlyMap会对元素在内存中进行更新或聚合，而PartitionedPairBuffer只起到数据缓冲的作用
// 2.AppendOnlyMap的行为更像map，元素以散列的方式放入到data数组，而PartitionedPairBuffer的行为更像collection，
//   元素都是从data数组的起始所以0和1开始连续放入的
// 3.AppendOnlyMap没有继承SizeTracker，因而不支持采样和大小估算，而PartitionedPairBuffer天生就继承自SizeTracker，
//   所以支持采样和大小估算。好在AppendOnlyMap继承了SizeTracker的子类SizeTrackingAppendOnlyMap
// 4.AppendOnlyMap没有继承WritablePartitionedPairCollection,因而不支持基于内存进行有效排序的迭代器，也不可以创建
//   将集合内容按照字节写入磁盘的WritablePartitionedIterator。而PartitionedPairBuffer天生就继承自
//   WritablePartitionedPairCollection。好在AppendOnlyMap继承了WritablePartitionedPairCollection的子类
//   PartitionedAppendOnlyMap.


/**
 * Append-only buffer of key-value pairs, each with a corresponding partition ID, that keeps track
 * of its estimated size in bytes.
 *
 * The buffer can support up to `1073741823 (2 ^ 30 - 1)` elements.
 */
// map任务 除了采用AppendOnlyMap对键值对在内存中进行更新或聚合，Spark还提供了一种将将键值对缓存在内存中，并支持对元素进行排序的的数据结构。
// AppendOnlyMap的表现行为类似于Map，而这种数据结构类似于Collection,它就是PartitionedPairBuffer。
// PartitionedPairBuffer最大支持2^30-1个元素
private[spark] class PartitionedPairBuffer[K, V](initialCapacity: Int = 64)
  extends WritablePartitionedPairCollection[K, V] with SizeTracker
{
  import PartitionedPairBuffer._

  require(initialCapacity <= MAXIMUM_CAPACITY,
    s"Can't make capacity bigger than ${MAXIMUM_CAPACITY} elements")
  require(initialCapacity >= 1, "Invalid initial capacity")

  // Basic growable array data structure. We use a single array of AnyRef to hold both the keys
  // and the values, so that we can sort them efficiently with KVArraySortDataFormat.
  // data数组的当前容量。
  private var capacity = initialCapacity
  // 记录当前已经放入data的key与value的数量
  private var curSize = 0
  // 用于保存key和value的数组。
  private var data = new Array[AnyRef](2 * initialCapacity)

  /** Add an element into the buffer */
  // 此方法用于将key的分区ID、key及value添加到PartitionedPartitionedPairBuffer底层的data数组中
  def insert(partition: Int, key: K, value: V): Unit = {
    // 如果底层data数组已经满了，即curSize与capacity相等时，调用growArray方法对PartitionedPairBuffer的容量进行扩充
    if (curSize == capacity) {
      growArray()
    }
    // 将key的分区ID和key作为对偶放入data数组的索引为 2*curSize的位置
    data(2 * curSize) = (partition, key.asInstanceOf[AnyRef])
    // 将value放入data数组的索引为2 * curSize + 1的位置
    data(2 * curSize + 1) = value.asInstanceOf[AnyRef]
    // 增加已经放入data数组的key与value的数量，即将curSize加一
    curSize += 1
    // 调用父类的SizeTracker的afterUpdate对集合大小进行采样
    afterUpdate()
  }

  /** Double the size of the array because we've reached capacity */
  // 用于扩充PartitionedPairBuffer的容量
  private def growArray(): Unit = {
    // 对PartitionedPairBuffer的当前容量进行校验，以防止超过常量MAXIMUM_CAPACITY的限制
    if (capacity >= MAXIMUM_CAPACITY) {
      throw new IllegalStateException(s"Can't insert more than ${MAXIMUM_CAPACITY} elements")
    }
    // 计算对PartitionedPairBuffer进行扩充后的容量newCapacity,即capacity*2,
    // 如果newCapacity超过MAXIMUM_CAPACITY的限制，那么将newCapacity重新设置为MAXIMUM_CAPACITY
    val newCapacity =
      if (capacity * 2 < 0 || capacity * 2 > MAXIMUM_CAPACITY) { // Overflow
        MAXIMUM_CAPACITY
      } else {
        capacity * 2
      }
    // 创建一个两倍于newCapacity的大小的新数组
    val newArray = new Array[AnyRef](2 * newCapacity)
    // 将底层data数组的每个元素都拷贝到新数组中
    System.arraycopy(data, 0, newArray, 0, 2 * capacity)
    // 将新数组设置为底层的data数组
    data = newArray
    // 将当前capacity设置为newCapacity
    capacity = newCapacity
    // 调用父类SizeTracker的resetSamples对样本进行重置，以便估算准确
    resetSamples()
  }

  /** Iterate through the data in a given order. For this class this is not really destructive. */
  // 根据给定的对key进行比较的比较器，返回对集合中的数据按照分区ID的顺序进行迭代的迭代器
  override def partitionedDestructiveSortedIterator(keyComparator: Option[Comparator[K]])
    : Iterator[((Int, K), V)] = {
    // 调用WritablePartitionedPairCollection的伴生对象的partitionKeyComparator方法生成比较器。
    // 如果没有指定key比较器，那么调用WritablePartitionedPairCollection的伴生对象的partitionComparator方法生成比较器
    val comparator = keyComparator.map(partitionKeyComparator).getOrElse(partitionComparator)
    // 利用Sorter、KVArraySortDataFormat及上一步生成的比较器执行内置排序。这其中用到了TimSort，也就是优化版的归并排序
    new Sorter(new KVArraySortDataFormat[(Int, K), AnyRef]).sort(data, 0, curSize, comparator)
    // 调用PartitionedPairBuffer的iterator方法获得对data中的数据进行迭代的迭代器
    iterator
  }

  private def iterator(): Iterator[((Int, K), V)] = new Iterator[((Int, K), V)] {
    var pos = 0

    override def hasNext: Boolean = pos < curSize

    override def next(): ((Int, K), V) = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val pair = (data(2 * pos).asInstanceOf[(Int, K)], data(2 * pos + 1).asInstanceOf[V])
      pos += 1
      pair
    }
  }
}

private object PartitionedPairBuffer {
  // data数组的容量不能超过MAXIMUM_CAPACITY,以防止data数组溢出
  val MAXIMUM_CAPACITY = Int.MaxValue / 2 // 2 ^ 30 - 1
}
