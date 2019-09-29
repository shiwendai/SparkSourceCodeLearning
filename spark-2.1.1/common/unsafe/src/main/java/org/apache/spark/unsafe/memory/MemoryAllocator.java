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

// MemoryAllocator是Tungsten的内存分配器的接口规范
public interface MemoryAllocator {

  /**
   * Whether to fill newly allocated and deallocated memory with 0xa5 and 0x5a bytes respectively.
   * This helps catch misuse of uninitialized or freed memory, but imposes some overhead.
   */
  boolean MEMORY_DEBUG_FILL_ENABLED = Boolean.parseBoolean(
    System.getProperty("spark.memory.debugFill", "false"));

  // Same as jemalloc's debug fill values.
  byte MEMORY_DEBUG_FILL_CLEAN_VALUE = (byte)0xa5;
  byte MEMORY_DEBUG_FILL_FREED_VALUE = (byte)0x5a;

  /**
   * Allocates a contiguous block of memory. Note that the allocated memory is not guaranteed
   * to be zeroed out (call `fill(0)` on the result if this is necessary).
   */
  // 分配指定大小的连续内存块。按照这种方式分配的内存不能保证被清零，如果需要，可以在MemoryBlock上调用fill(0)
  MemoryBlock allocate(long size) throws OutOfMemoryError;

  // 释放连续的内存
  void free(MemoryBlock memory);

  //
  MemoryAllocator UNSAFE = new UnsafeMemoryAllocator();

  MemoryAllocator HEAP = new HeapMemoryAllocator();
}
