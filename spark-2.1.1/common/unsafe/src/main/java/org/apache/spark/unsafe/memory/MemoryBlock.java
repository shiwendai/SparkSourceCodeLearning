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

import javax.annotation.Nullable;

/**
 * A consecutive block of memory, starting at a {@link MemoryLocation} with a fixed size.
 */
// 在Tungstem中实现了一种与操作系统的内存Page非常相似的数据结构，这个对象就是MemoryBlock。MemoryBlock中的数据可能位于JVM的对上，
	// 也可能位于JVM的堆外内存（操作系统内存）中。
public class MemoryBlock extends MemoryLocation {

	// 当前MemoryBlock的连续内存块的长度。
  private final long length;

  /**
   * Optional page number; used when this MemoryBlock represents a page allocated by a
   * TaskMemoryManager. This field is public so that it can be modified by the TaskMemoryManager,
   * which lives in a different package.
   */
  // 当前MemoryBlock的页号。TaskMemoryManager分配由MemoryBlock表示的Page时，将使用此属性。
  public int pageNumber = -1;

  public MemoryBlock(@Nullable Object obj, long offset, long length) {
    super(obj, offset);
    this.length = length;
  }

  /**
   * Returns the size of the memory block.
   */
  // MemoryBlock的大小
  public long size() {
    return length;
  }

  /**
   * Creates a memory block pointing to the memory used by the long array.
   */
  // 创建一个指向由长整型数据使用的内存的MemoryBlock
  public static MemoryBlock fromLongArray(final long[] array) {
    return new MemoryBlock(array, Platform.LONG_ARRAY_OFFSET, array.length * 8L);
  }

  /**
   * Fills the memory block with the specified byte value.
   */
  // 以指定的字节填充整个MemoryBlock，即将obj对象从offset开始，长度为length的堆内存替换为指定字节的值。
  // Platform中封装了对sun.misc.Unsafe的API调用，Platform的setMemory方法实际调用了sun.misc.Unsage的setMemory方法
  public void fill(byte value) {
    Platform.setMemory(obj, offset, length, value);
  }
}
