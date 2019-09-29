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

package org.apache.spark.unsafe.memory;

import org.apache.spark.unsafe.Platform;

import javax.annotation.concurrent.GuardedBy;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * A simple {@link MemoryAllocator} that can allocate up to 16GB using a JVM long primitive array.
 */
// HeapMemoryAllocator是Tungsten在堆外内存模式下使用的内存分配器，与onHeapExecutionMemoryPool配合使用。
public class HeapMemoryAllocator implements MemoryAllocator {

	// bufferPoolsBySize 是关于MemoryBlock的弱引用的缓冲池，用于Page页(即MemoryBlock)的分配。
  @GuardedBy("this")
  private final Map<Long, LinkedList<WeakReference<MemoryBlock>>> bufferPoolsBySize =
    new HashMap<>();

  private static final int POOLING_THRESHOLD_BYTES = 1024 * 1024;

  /**
   * Returns true if allocations of the given size should go through the pooling mechanism and
   * false otherwise.
   */
  // 此方法用于判断对于指定大小的MemoryBlock，是否需要采用池化机制（即从缓冲池bufferPoolsBySize中获取MemoryBlock
  // 或将MemoryBlock放入bufferPoolsBySize）.根据shouldPool方法的实现，当要分配的内存大小大于等于1MB（常量POOLING_THRESHOLD_BYTES）时，
  // 需要从bufferPoolsBySize中获取MemoryBlock.
  private boolean shouldPool(long size) {
    // Very small allocations are less likely to benefit from pooling.
    return size >= POOLING_THRESHOLD_BYTES;
  }


  // 用于分配指定大小(size)的MemoryBlock.
  @Override
  public MemoryBlock allocate(long size) throws OutOfMemoryError {
  	// 如果指定大小的MemoryBlock需要采用池化机制，则从bufferPoolsBySize的弱引用中获取指定大小的MemoryBlock.
	// 如果bufferPoolsBySize中没有指定大小的MemoryBlock,则将指定大小的弱引用池从bufferPoolsBySize中移除
    if (shouldPool(size)) {
      synchronized (this) {
        final LinkedList<WeakReference<MemoryBlock>> pool = bufferPoolsBySize.get(size);
        if (pool != null) {
          while (!pool.isEmpty()) {
            final WeakReference<MemoryBlock> blockReference = pool.pop();
            final MemoryBlock memory = blockReference.get();
            if (memory != null) {
              assert (memory.size() == size);
              return memory;
            }
          }
          bufferPoolsBySize.remove(size);
        }
      }
    }

    // 如果指定大小（size）的MemoryBlock不需要采用池化机制或者bufferPoolsBySize中没有指定大小的MemoryBlock,则创建MemoryBlock并返回
    long[] array = new long[(int) ((size + 7) / 8)];
    MemoryBlock memory = new MemoryBlock(array, Platform.LONG_ARRAY_OFFSET, size);
    if (MemoryAllocator.MEMORY_DEBUG_FILL_ENABLED) {
      memory.fill(MemoryAllocator.MEMORY_DEBUG_FILL_CLEAN_VALUE);
    }
    return memory;
  }

  // 用于释放MemoryBlock的大小
  @Override
  public void free(MemoryBlock memory) {
  	// 获取待释放MemoryBlock的大小
    final long size = memory.size();
    if (MemoryAllocator.MEMORY_DEBUG_FILL_ENABLED) {
      memory.fill(MemoryAllocator.MEMORY_DEBUG_FILL_FREED_VALUE);
    }
    // 如果MemoryBlock的大小需要采用池化机制，那么将MemoryBlock的弱引用放入BufferPoolsSize中
    if (shouldPool(size)) {
      synchronized (this) {
        LinkedList<WeakReference<MemoryBlock>> pool = bufferPoolsBySize.get(size);
        if (pool == null) {
          pool = new LinkedList<>();
          bufferPoolsBySize.put(size, pool);
        }
        pool.add(new WeakReference<>(memory));
      }
    } else {
      // Do nothing
    }
  }
}
