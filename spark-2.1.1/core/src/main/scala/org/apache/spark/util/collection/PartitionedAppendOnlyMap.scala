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

/**
 * Implementation of WritablePartitionedPairCollection that wraps a map in which the keys are tuples
 * of (partition ID, K)
 */
// PartitionedAppendOnlyMap实现了特质WritablePartitionedPairCollection定义的
// partitionedDestructiveSortedIterator 接口 和 insert 接口
private[spark] class PartitionedAppendOnlyMap[K, V]
  extends SizeTrackingAppendOnlyMap[(Int, K), V] with WritablePartitionedPairCollection[K, V] {

  def partitionedDestructiveSortedIterator(keyComparator: Option[Comparator[K]])
    : Iterator[((Int, K), V)] = {
    // 调用WritablePartitionedPairCollection的伴生对象的partitionKeyComParator方法生成比较器。
    // 如果没有指定key比较器，那么调用WritablePartitionedPairCollection的伴生对象的partitionComparator方法生成比较器。
    val comparator = keyComparator.map(partitionKeyComparator).getOrElse(partitionComparator)
    // 调用AppendOnlyMap的destructiveSortedIterator方法对底层的data数组进行整理和排序后获得迭代器
    destructiveSortedIterator(comparator)
  }

  // 此方法进key的分区ID和key作为父类SizeTrackingAppendOnlyMap的update方法的第一个参数，而将value作为第二个参数
  //
  def insert(partition: Int, key: K, value: V): Unit = {
    update((partition, key), value)
  }
}
