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

package org.apache.spark.memory;

import org.apache.spark.unsafe.array.LongArray;
import org.apache.spark.unsafe.memory.MemoryBlock;

import java.io.IOException;

/**
 * A memory consumer of {@link TaskMemoryManager} that supports spilling.
 *
 * Note: this only supports allocation / spilling of Tungsten memory.
 */
// 抽象类MemoryConsumer定义了内存消费这的规范，它通过TaskMemoryManager在执行内存（堆内存或堆外内存）上申请或释放内存。
public abstract class MemoryConsumer {

  protected final TaskMemoryManager taskMemoryManager;
  // MemoryConsumer要消费的Page的大小
  private final long pageSize;
  private final MemoryMode mode;
  // 当前消费者已经使用的执行内存的大小，MemoryConsumer中提供了getUsed方法获取Used
  protected long used;

  protected MemoryConsumer(TaskMemoryManager taskMemoryManager, long pageSize, MemoryMode mode) {
    this.taskMemoryManager = taskMemoryManager;
    this.pageSize = pageSize;
    this.mode = mode;
  }

  protected MemoryConsumer(TaskMemoryManager taskMemoryManager) {
    this(taskMemoryManager, taskMemoryManager.pageSizeBytes(), MemoryMode.ON_HEAP);
  }

  /**
   * Returns the memory mode, {@link MemoryMode#ON_HEAP} or {@link MemoryMode#OFF_HEAP}.
   */
  public MemoryMode getMode() {
    return mode;
  }

  /**
   * Returns the size of used memory in bytes.
   */
  protected long getUsed() {
    return used;
  }

  /**
   * Force spill during building.
   *
   * For testing.
   */
  // 此方法用于调用子类的spill方法将数据溢出到磁盘
  public void spill() throws IOException {
    spill(Long.MAX_VALUE, this);
  }

  /**
   * Spill some data to disk to release memory, which will be called by TaskMemoryManager
   * when there is not enough memory for the task.
   *
   * This should be implemented by subclass.
   *
   * Note: In order to avoid possible deadlock, should not call acquireMemory() from spill().
   *
   * Note: today, this only frees Tungsten-managed pages.
   *
   * @param size the amount of memory should be released
   * @param trigger the MemoryConsumer that trigger this spilling
   * @return the amount of released memory in bytes
   * @throws IOException
   */
  // 当任务尝试没有足够的内存可用时，TaskMemoryManager将调用此方法把一些数据溢出到磁盘，以释放内存。
  public abstract long spill(long size, MemoryConsumer trigger) throws IOException;

  /**
   * Allocates a LongArray of `size`.
   */
  // 此方法用于分配指定大小的长整型数组
  public LongArray allocateArray(long size) {
  	// 计算所需的Page大小（即required）。由于长整型占用8个字节，所以需要乘以8
    long required = size * 8L;
    // 调用TaskMemoryManager.allocatePage方法，给当前MemoryConsumer分配指定大小的Page(即MemoryBlock)
    MemoryBlock page = taskMemoryManager.allocatePage(required, this);

    if (page == null || page.size() < required) {
    	// 如果分配得到的MemoryBlock的大小小于所需要的大小required
      long got = 0;
      if (page != null) {
        got = page.size();
        // 则调用TaskMemoryManager的freePage方法释放MemoryBlock
        taskMemoryManager.freePage(page, this);
      }
      // 然后调用TaskMemoryManager的showMemoryUsage打印内存使用信息并抛出OutOfMemoryError。
      taskMemoryManager.showMemoryUsage();
      throw new OutOfMemoryError("Unable to acquire " + required + " bytes of memory, got " + got);
    }
    // 如果分配到的MemoryBlock的大小大于等于所需的required，则将required累加到used，
    used += required;
    // 创建并返回LongArray并返回
	  // LongArray并非使用了Java的new Long[size]语法来创建长整型数组，而是调用sun.misc.Unsafe的putLong(object, offset, value)
	  // 和getLong(object, offset)两个方法来模拟实现长整型数组
    return new LongArray(page);
  }

  /**
   * Frees a LongArray.
   */
  // 用于释放长整型数组
  public void freeArray(LongArray array) {
    freePage(array.memoryBlock());
  }

  /**
   * Allocate a memory block with at least `required` bytes.
   *
   * Throws IOException if there is not enough memory.
   *
   * @throws OutOfMemoryError
   */
  protected MemoryBlock allocatePage(long required) {
    MemoryBlock page = taskMemoryManager.allocatePage(Math.max(pageSize, required), this);
    if (page == null || page.size() < required) {
      long got = 0;
      if (page != null) {
        got = page.size();
        taskMemoryManager.freePage(page, this);
      }
      taskMemoryManager.showMemoryUsage();
      throw new OutOfMemoryError("Unable to acquire " + required + " bytes of memory, got " + got);
    }
    used += page.size();
    return page;
  }

  /**
   * Free a memory block.
   */
  protected void freePage(MemoryBlock page) {
    used -= page.size();
    taskMemoryManager.freePage(page, this);
  }

  /**
   * Allocates memory of `size`.
   */
  // 用于获得指定大小的内存
  public long acquireMemory(long size) {
  	// 调用TaskMemoryManager.acquireExecutionMemory方法获取指定大小的内存，然后更新used，最后返回实际获得的内存大小
    long granted = taskMemoryManager.acquireExecutionMemory(size, this);
    used += granted;
    return granted;
  }

  /**
   * Release N bytes of memory.
   */
  // 此方法用于释放指定大小的内存
  public void freeMemory(long size) {
    taskMemoryManager.releaseExecutionMemory(size, this);
    used -= size;
  }
}
