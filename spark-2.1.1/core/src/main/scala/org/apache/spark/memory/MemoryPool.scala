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

package org.apache.spark.memory

import javax.annotation.concurrent.GuardedBy


/**
  * 内存池好比游泳馆的游泳池，只不过游泳池装的是水，内存池装的是内存。游泳馆往往不止一个游泳池，Spark的存储体系的
  * 每个存储节点上也不止一个内存池。内存池实质上是对物理内存的逻辑规划，协助Spark任务在运行时合理地使用内存资源。
  * Spark将内存从逻辑上区分为堆内存和堆外内存。这里的堆内存并不能与JVM中的Java堆直接画等号，它只是JVM对内存的一
  * 部分。堆外内存则是Spark使用sum.misc.Unsafe的API直接在工作节点的系统内存中开辟的空间。无论是那种内存，都需要
  * 一个内存池对内存进行资源管理，抽象类 MemoryPool定义了内存池的规范
  */
/**
 * Manages bookkeeping for an adjustable-sized region of memory. This class is internal to
 * the [[MemoryManager]]. See subclasses for more details.
 *
 * @param lock a [[MemoryManager]] instance, used for synchronization. We purposely erase the type
 *             to `Object` to avoid programming errors, since this object should only be used for
 *             synchronization purposes.
 */
private[memory] abstract class MemoryPool(lock: Object) {

  // 内存池的大小
  @GuardedBy("lock")
  private[this] var _poolSize: Long = 0

  /**
   * Returns the current size of the pool, in bytes.
   */
  // 返回内存池大小的方法
  final def poolSize: Long = lock.synchronized {
    _poolSize
  }

  /**
   * Returns the amount of free memory in the pool, in bytes.
   */
  // 获取内存池的空闲空间
  final def memoryFree: Long = lock.synchronized {
    _poolSize - memoryUsed
  }

  /**
   * Expands the pool by `delta` bytes.
   */
  // 给内存池扩展delta的大小
  final def incrementPoolSize(delta: Long): Unit = lock.synchronized {
    require(delta >= 0)
    _poolSize += delta
  }

  /**
   * Shrinks the pool by `delta` bytes.
   */
  // 给内存池减少delta的大小
  final def decrementPoolSize(delta: Long): Unit = lock.synchronized {
    require(delta >= 0)
    require(delta <= _poolSize)
    require(_poolSize - delta >= memoryUsed)
    _poolSize -= delta
  }

  /**
   * Returns the amount of used memory in this pool (in bytes).
   */
  // 获取已使用的内存大小，此方法需要MemoryPool的子类实现
  def memoryUsed: Long
}
