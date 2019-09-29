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

import java.io.IOException

import org.apache.spark.scheduler.MapStatus

/**
 * Obtained inside a map task to write out records to the shuffle system.
 */
// SortShuffleManager依赖于ShuffleWriter提供的服务，抽象类ShuffleWriter定义了将map任务的中间结果输出到磁盘上的功能规范，
// 包括将数据写入磁盘和关闭ShuffleWriter
private[spark] abstract class ShuffleWriter[K, V] {
  /** Write a sequence of records to this task's output */
  // 此方法用于将map任务的结果写到磁盘
  @throws[IOException]
  def write(records: Iterator[Product2[K, V]]): Unit

  // 此方法可以关闭ShuffleWriter
  /** Close this writer, passing along whether the map completed */
  def stop(success: Boolean): Option[MapStatus]
}
