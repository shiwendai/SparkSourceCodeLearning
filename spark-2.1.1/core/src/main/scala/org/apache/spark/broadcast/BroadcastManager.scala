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

import java.util.concurrent.atomic.AtomicLong

import scala.reflect.ClassTag

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.internal.Logging

// BroadcastManager用于将配置信息和序列化后的RDD、Job及ShuffleDependency等信息在本地存储
private[spark] class BroadcastManager(
    val isDriver: Boolean,
    conf: SparkConf,
    securityManager: SecurityManager)
  extends Logging {

  // 表示BroadcastManager是否初始化完成的状态
  private var initialized = false
  // 广播工厂实例
  private var broadcastFactory: BroadcastFactory = null

  // BroadcastManager在其初始化的过程中就会调用自身的initialize方法，当initialize执行完毕，BroadcastManager就正式生效
  initialize()

  // Called by SparkContext or Executor before using Broadcast
  private def initialize() {
    synchronized {
      // 首先判断BroadcastManager是否已经初始化，以保证BroadcastManager只被初始化一次
      if (!initialized) {
        // 新建TorrentBroadcastFactory作为BroadcastManager的广播工厂实例
        broadcastFactory = new TorrentBroadcastFactory
        // 调用TorrentBroadcastFactory的initialize方法对TorrentBroadcastFactory进行初始化
        broadcastFactory.initialize(isDriver, conf, securityManager)
        // 最后将BroadcastManager自身标记为初始化完成状态
        initialized = true
      }
    }
  }

  def stop() {
    broadcastFactory.stop()
  }

  // 下一个广播对象的广播ID
  private val nextBroadcastId = new AtomicLong(0)

  def newBroadcast[T: ClassTag](value_ : T, isLocal: Boolean): Broadcast[T] = {
    broadcastFactory.newBroadcast[T](value_, isLocal, nextBroadcastId.getAndIncrement())
  }

  def unbroadcast(id: Long, removeFromDriver: Boolean, blocking: Boolean) {
    broadcastFactory.unbroadcast(id, removeFromDriver, blocking)
  }
}
