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

import com.google.common.annotations.VisibleForTesting;
import org.apache.spark.unsafe.memory.MemoryBlock;
import org.apache.spark.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;

/**
 * Manages the memory allocated by an individual task.
 * <p>
 * Most of the complexity in this class deals with encoding of off-heap addresses into 64-bit longs.
 * In off-heap mode, memory can be directly addressed with 64-bit longs. In on-heap mode, memory is
 * addressed by the combination of a base Object reference and a 64-bit offset within that object.
 * This is a problem when we want to store pointers to data structures inside of other structures,
 * such as record pointers inside hashmaps or sorting buffers. Even if we decided to use 128 bits
 * to address memory, we can't just store the address of the base object since it's not guaranteed
 * to remain stable as the heap gets reorganized due to GC.
 * <p>
 * Instead, we use the following approach to encode record pointers in 64-bit longs: for off-heap
 * mode, just store the raw address, and for on-heap mode use the upper 13 bits of the address to
 * store a "page number" and the lower 51 bits to store an offset within this page. These page
 * numbers are used to index into a "page table" array inside of the MemoryManager in order to
 * retrieve the base object.
 * <p>
 * This allows us to address 8192 pages. In on-heap mode, the maximum page size is limited by the
 * maximum size of a long[] array, allowing us to address 8192 * 2^32 * 8 bytes, which is
 * approximately 35 terabytes of memory.
 */
// TaskMemoryManager用于管理单个任务尝试的内存分配与释放。TaskMemoryManager实际上依赖于MemoryManager提供的内存管理能力，
// 多个TaskMemoryManager将共享MemoryManager所管理的内存。一次任务尝试有很多组件需要使用内存，这些组件都借助于TaskMemoryManager
// 提供的服务对实际的物理内存进行消费，他们统称为内存消费者（MemoryConsumer）
public class TaskMemoryManager {

  private static final Logger logger = LoggerFactory.getLogger(TaskMemoryManager.class);

  /** The number of bits used to address the page table. */
  // 用于寻址Page表的位数。13表示在64的长整形中将使用高位的13位存储页号
  private static final int PAGE_NUMBER_BITS = 13;

  /** The number of bits used to encode offsets in data pages. */
  // 用于保存编码后的偏移量的位数。51表示在64位的长整型中将使用低位的51位存储偏移量
  @VisibleForTesting
  static final int OFFSET_BITS = 64 - PAGE_NUMBER_BITS;  // 51

  /** The number of entries in the page table. */
  // Page表中的Page数量。
  private static final int PAGE_TABLE_SIZE = 1 << PAGE_NUMBER_BITS;

  /**
   * Maximum supported data page size (in bytes). In principle, the maximum addressable page size is
   * (1L &lt;&lt; OFFSET_BITS) bytes, which is 2+ petabytes. However, the on-heap allocator's
   * maximum page size is limited by the maximum amount of data that can be stored in a long[]
   * array, which is (2^32 - 1) * 8 bytes (or 16 gigabytes). Therefore, we cap this at 16 gigabytes.
   */
  // 最大的Page大小
  public static final long MAXIMUM_PAGE_SIZE_BYTES = ((1L << 31) - 1) * 8L;

  /** Bit mask for the lower 51 bits of a long. */
  // 长整型的低51位的为掩码
  private static final long MASK_LONG_LOWER_51_BITS = 0x7FFFFFFFFFFFFL;

  /**
   * Similar to an operating system's page table, this array maps page numbers into base object
   * pointers, allowing us to translate between the hashtable's internal 64-bit address
   * representation and the baseObject+offset representation which we use to support both in- and
   * off-heap addresses. When using an off-heap allocator, every entry in this map will be `null`.
   * When using an in-heap allocator, the entries in this map will point to pages' base objects.
   * Entries are added to this map as new data pages are allocated.
   */
  // Page表。pageTable实际为Page（MemoryBlock）的数组，数组长度为PAGE_TABLE_SIZE
  private final MemoryBlock[] pageTable = new MemoryBlock[PAGE_TABLE_SIZE];

  /**
   * Bitmap for tracking free pages.
   */
  // 用于跟踪空闲Page的BitSet
  private final BitSet allocatedPages = new BitSet(PAGE_TABLE_SIZE);

  private final MemoryManager memoryManager;

  // TaskMemoryManager所管理任务尝试的身份标识
  private final long taskAttemptId;

  /**
   * Tracks whether we're in-heap or off-heap. For off-heap, we short-circuit most of these methods
   * without doing any masking or lookups. Since this branching should be well-predicted by the JIT,
   * this extra layer of indirection / abstraction hopefully shouldn't be too expensive.
   */
  final MemoryMode tungstenMemoryMode;

  /**
   * Tracks spillable memory consumers.
   */
  // 用于跟踪可溢出的内存消费者
  @GuardedBy("this")
  private final HashSet<MemoryConsumer> consumers;

  /**
   * The amount of memory that is acquired but not used.
   */
  // 任务尝试已经获得但是并未使用的内存大小
  private volatile long acquiredButNotUsed = 0L;

  /**
   * Construct a new TaskMemoryManager.
   */
  public TaskMemoryManager(MemoryManager memoryManager, long taskAttemptId) {
    this.tungstenMemoryMode = memoryManager.tungstenMemoryMode();
    this.memoryManager = memoryManager;
    this.taskAttemptId = taskAttemptId;
    this.consumers = new HashSet<>();
  }

  /**
   * Acquire N bytes of memory for a consumer. If there is no enough memory, it will call
   * spill() of consumers to release more memory.
   *
   * @return number of bytes successfully granted (<= N).
   */
  // 用于为内存消费者获取指定大小的内存。当Task没有足够的内存时，将调用MemoryConsumer的spill方法释放内存。
  public long acquireExecutionMemory(long required, MemoryConsumer consumer) {
    assert(required >= 0);
    assert(consumer != null);
    MemoryMode mode = consumer.getMode();
    // If we are allocating Tungsten pages off-heap and receive a request to allocate on-heap
    // memory here, then it may not make sense to spill since that would only end up freeing
    // off-heap memory. This is subject to change, though, so it may be risky to make this
    // optimization now in case we forget to undo it late when making changes.
    synchronized (this) {
    	// 调用MemoryManager的acquireExecutionMemory方法尝试为当前任务按指定的存储模式获取指定大小的内存
      long got = memoryManager.acquireExecutionMemory(required, taskAttemptId, mode);

      // Try to release memory from other consumers first, then we can reduce the frequency of
      // spilling, avoid to have too many spilled files.
      if (got < required) {
        // Call spill() on other consumers to release memory
	      // 如果逻辑上已经获得的内存未达到期望的内存大小，那么遍历consumers中与指定内存模式相同且已经使用了内存的
	      // MemoryConsumer(不包括当前申请内存的MemoryConsumer),对每个MemoryConsumer执行如下操作。
        for (MemoryConsumer c: consumers) {
          if (c != consumer && c.getUsed() > 0 && c.getMode() == mode) {
            try {
            	// 调用MemoryConsumer的spill方法,溢出数据到磁盘，以释放内存
              long released = c.spill(required - got, consumer);
              if (released > 0) {
                logger.debug("Task {} released {} from {} for {}", taskAttemptId,
                  Utils.bytesToString(released), c, consumer);
                // 如果MemoryConsumer释放了内存空间，那么调用MemoryManager的acquireExecutionMemory方法尝试为当前
	              // 任务按指定的存储模式继续获取期望的内存与已经获得的内存之间差值大小的内存。
                got += memoryManager.acquireExecutionMemory(required - got, taskAttemptId, mode);
                // 如果已经获得的内存达到了期望，则进入下一步，否则继续获取
                if (got >= required) {
                  break;
                }
              }
            } catch (IOException e) {
              logger.error("error while calling spill() on " + c, e);
              throw new OutOfMemoryError("error while calling spill() on " + c + " : "
                + e.getMessage());
            }
          }
        }
      }

      // call spill() on itself
      if (got < required) {
      	// 如果已经获得的内存未达到期望的内存大小，则调用当前MemoryConsumer的pill方法溢出数据到磁盘以释放内存，
	      // 并调用MemoryManager.acquireExecutionMemory方法做最后尝试。
        try {
          long released = consumer.spill(required - got, consumer);
          if (released > 0) {
            logger.debug("Task {} released {} from itself ({})", taskAttemptId,
              Utils.bytesToString(released), consumer);
            got += memoryManager.acquireExecutionMemory(required - got, taskAttemptId, mode);
          }
        } catch (IOException e) {
          logger.error("error while calling spill() on " + consumer, e);
          throw new OutOfMemoryError("error while calling spill() on " + consumer + " : "
            + e.getMessage());
        }
      }

      // 将当前申请获得内存的MemoryConsumer添加到consumers中，并返回最终获的的内存大小
      consumers.add(consumer);
      logger.debug("Task {} acquired {} for {}", taskAttemptId, Utils.bytesToString(got), consumer);
      return got;
    }
  }

  /**
   * Release N bytes of execution memory for a MemoryConsumer.
   */
  // 此方法用于为内存消费者释放指定大小的内存
  public void releaseExecutionMemory(long size, MemoryConsumer consumer) {
    logger.debug("Task {} release {} from {}", taskAttemptId, Utils.bytesToString(size), consumer);
    // 实际是调用MemoryManager.releaseExecutionMemory方法
    memoryManager.releaseExecutionMemory(size, taskAttemptId, consumer.getMode());
  }

  /**
   * Dump the memory usage of all consumers.
   */
  // 此方法用于将任务尝试、各个MemoryConsumer及MemoryManager管理的执行内存和存储内存的使用情况打印到日志
  public void showMemoryUsage() {
    logger.info("Memory used in task " + taskAttemptId);
    synchronized (this) {
      long memoryAccountedForByConsumers = 0;
      for (MemoryConsumer c: consumers) {
        long totalMemUsage = c.getUsed();
        memoryAccountedForByConsumers += totalMemUsage;
        if (totalMemUsage > 0) {
          logger.info("Acquired by " + c + ": " + Utils.bytesToString(totalMemUsage));
        }
      }
      long memoryNotAccountedFor =
        memoryManager.getExecutionMemoryUsageForTask(taskAttemptId) - memoryAccountedForByConsumers;
      logger.info(
        "{} bytes of memory were used by task {} but are not associated with specific consumers",
        memoryNotAccountedFor, taskAttemptId);
      logger.info(
        "{} bytes of memory are used for execution and {} bytes of memory are used for storage",
        memoryManager.executionMemoryUsed(), memoryManager.storageMemoryUsed());
    }
  }

  /**
   * Return the page size in bytes.
   */
  // 此方法用于获得Page的大小。其实际为MemoryManager的pageSizeBytes
  public long pageSizeBytes() {
    return memoryManager.pageSizeBytes();
  }

  /**
   * Allocate a block of memory that will be tracked in the MemoryManager's page table; this is
   * intended for allocating large blocks of Tungsten memory that will be shared between operators.
   *
   * Returns `null` if there was not enough memory to allocate the page. May return a page that
   * contains fewer bytes than requested, so callers should verify the size of returned pages.
   */
  // 根据allocatePage方法的实现，我们看到TaskMemoryManager在分配Page时，首先从指定内存模式对应的ExecutionMemoryPool中
  // 申请获得逻辑内存，然后会选择内存模式对应的MemoryAllocator申请获得物理内存。

  // 此方法用于给MemoryConsumer分配指定大小的MemoryBlock
  public MemoryBlock allocatePage(long size, MemoryConsumer consumer) {
  	// 对参数进行校验
    assert(consumer != null);
    assert(consumer.getMode() == tungstenMemoryMode);
    if (size > MAXIMUM_PAGE_SIZE_BYTES) {
      throw new IllegalArgumentException(
        "Cannot allocate a page with more than " + MAXIMUM_PAGE_SIZE_BYTES + " bytes");
    }

    // 调用acquireExecutionMemory方法获取逻辑内存。如果获取到的内存大小小于等于零，那么返回null
    long acquired = acquireExecutionMemory(size, consumer);
    if (acquired <= 0) {
      return null;
    }

	  // 从allocatedPages获取还未分配的页号，将此页号记为已分配
    final int pageNumber;
    synchronized (this) {
      pageNumber = allocatedPages.nextClearBit(0);

      if (pageNumber >= PAGE_TABLE_SIZE) {
        releaseExecutionMemory(acquired, consumer);
        throw new IllegalStateException(
          "Have already allocated a maximum of " + PAGE_TABLE_SIZE + " pages");
      }
      allocatedPages.set(pageNumber);
    }

    MemoryBlock page = null;
    try {
    	// 获取Tungsten采用的内存分配器(MemoryAllocator)，并调用MemoryAllocator的allocate方法分配指定大小的MemoryBlock(即获取物理内存)。
      page = memoryManager.tungstenMemoryAllocator().allocate(acquired);
    } catch (OutOfMemoryError e) {
      logger.warn("Failed to allocate a page ({} bytes), try again.", acquired);
      // there is no enough memory actually, it means the actual free memory is smaller than
      // MemoryManager thought, we should keep the acquired memory.
	    // 如果allocate方法抛出了OutOfMemoryError，那么说明物理内存大小小于MemoryManager认为自己管理的逻辑内存大小，
      synchronized (this) {
	      // 此时需要更新acquiredButNotUsed，
        acquiredButNotUsed += acquired;
	      // 从allocatePages中清除此页号
        allocatedPages.clear(pageNumber);
      }
	    // 从allocatePages中清除此页号并再次调用allocatePage方法
      // this could trigger spilling to free some pages.
      return allocatePage(size, consumer);
    }
    // 给MemoryBlock指定页号
    page.pageNumber = pageNumber;
    // 并将页号(pageNumber)与MemoryBlock之间的对应关系放入pageTable中
    pageTable[pageNumber] = page;
    if (logger.isTraceEnabled()) {
      logger.trace("Allocate page number {} ({} bytes)", pageNumber, acquired);
    }
    // 返回MemoryBlock
    return page;
  }

  /**
   * Free a block of memory allocated via {@link TaskMemoryManager#allocatePage}.
   */
  // 根据freePage方法的实现，我们看到TaskMemoryManager在释放Page时，首先使用内存模式对应的MemoryAllocator释放物理内存，
  // 然后从指定内存模式对应的ExecutionMemoryPool中释放逻辑内存。freePage与allocatePage操作的顺序正好相反。

  // 此方法用于释放给MemoryConsumer分配的MemoryBlock
  public void freePage(MemoryBlock page, MemoryConsumer consumer) {
    assert (page.pageNumber != -1) :
      "Called freePage() on memory that wasn't allocated with allocatePage()";
    assert(allocatedPages.get(page.pageNumber));

    // 清空pageTable中保存的MemoryBlock
    pageTable[page.pageNumber] = null;

    // 清空allocatedPages对MemoryBlock的页号的跟踪
    synchronized (this) {
      allocatedPages.clear(page.pageNumber);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("Freed page number {} ({} bytes)", page.pageNumber, page.size());
    }
    // 获取MemoryBlock的页大小
    long pageSize = page.size();
    // 获取Tungsten采用的内存分配器(MemoryAllocator)，并调用MemoryAllocator的free方法释放MemoryBock(即释放物理内存)
    memoryManager.tungstenMemoryAllocator().free(page);
    // 调用releaseExecutionMemory方法释放MemoryManager管理的逻辑内存
    releaseExecutionMemory(pageSize, consumer);
  }

  /**
   * Given a memory page and offset within that page, encode this address into a 64-bit long.
   * This address will remain valid as long as the corresponding page has not been freed.
   *
   * @param page a data page allocated by {@link TaskMemoryManager#allocatePage}/
   * @param offsetInPage an offset in this page which incorporates the base offset. In other words,
   *                     this should be the value that you would pass as the base offset into an
   *                     UNSAFE call (e.g. page.baseOffset() + something).
   * @return an encoded page address.
   */
  // 此方法用于根据给定的page(MemoryBlock)和Page中偏移量的地址，返回页号和相对于内存块起始地址的偏移量(64位长整形)
  public long encodePageNumberAndOffset(MemoryBlock page, long offsetInPage) {
    if (tungstenMemoryMode == MemoryMode.OFF_HEAP) {
      // In off-heap mode, an offset is an absolute address that may require a full 64 bits to
      // encode. Due to our page size limitation, though, we can convert this into an offset that's
      // relative to the page's base offset; this relative offset will fit in 51 bits.
	    // 如果Tungsten的内存模式是堆外内存，此时的参数offsetInPage是操作系统的内存的绝对地址，offsetInpage与MemoryBlock的起始地址之差
	    // 就是相对于起始地址的偏移量（64位地址的低51位）
      offsetInPage -= page.getBaseOffset();
    }
    return encodePageNumberAndOffset(page.pageNumber, offsetInPage);
  }

  // 此方法通过位运算将页号存储到64位长整型的高13位，并将偏移量存储到64位长整型的低51位中，返回生成的64位的长整型
  @VisibleForTesting
  public static long encodePageNumberAndOffset(int pageNumber, long offsetInPage) {
    assert (pageNumber != -1) : "encodePageNumberAndOffset called with invalid page";
    return (((long) pageNumber) << OFFSET_BITS) | (offsetInPage & MASK_LONG_LOWER_51_BITS);
  }

  // 用于将64位的长整型右移51位，然后转换为整型以获得Page的页号
  @VisibleForTesting
  public static int decodePageNumber(long pagePlusOffsetAddress) {
    return (int) (pagePlusOffsetAddress >>> OFFSET_BITS);
  }

  // 用于将64位的长整型与51位的掩码按位进行与运算，以获得在Page中的偏移量
  private static long decodeOffset(long pagePlusOffsetAddress) {
    return (pagePlusOffsetAddress & MASK_LONG_LOWER_51_BITS);
  }

  /**
   * Get the page associated with an address encoded by
   * {@link TaskMemoryManager#encodePageNumberAndOffset(MemoryBlock, long)}
   */
  // 用于通过64位的长整型，获取Page在内存中的对象。此方法在Tungsten采用堆内存模式时有效，否则返回null
  public Object getPage(long pagePlusOffsetAddress) {
    if (tungstenMemoryMode == MemoryMode.ON_HEAP) {
	    // 如果Tungsten的内存模式时堆内存，首先调用decodePageNumber获取Page的页号
      final int pageNumber = decodePageNumber(pagePlusOffsetAddress);
      assert (pageNumber >= 0 && pageNumber < PAGE_TABLE_SIZE);
      // 然后从pageTable中取出MemoryBlock
      final MemoryBlock page = pageTable[pageNumber];
      assert (page != null);
      assert (page.getBaseObject() != null);
      // 最后返回MemoryBlock的obj
      return page.getBaseObject();
    } else {
      return null;
    }
  }

  /**
   * Get the offset associated with an address encoded by
   * {@link TaskMemoryManager#encodePageNumberAndOffset(MemoryBlock, long)}
   */
  // 此方法用于通过64位的长整型，获取在Page中的偏移量
  public long getOffsetInPage(long pagePlusOffsetAddress) {
  	// 调用decodeOffset方法从64位长整型中解码获得在Page中的偏移量
    final long offsetInPage = decodeOffset(pagePlusOffsetAddress);
    if (tungstenMemoryMode == MemoryMode.ON_HEAP) {
    	// 如果Tungsten的内存模式是堆内存，则返回offsetInPage偏移量
      return offsetInPage;
    } else {
      // In off-heap mode, an offset is an absolute address. In encodePageNumberAndOffset, we
      // converted the absolute address into a relative address. Here, we invert that operation:
	    // 如果Tungsten的内存模式是堆外内存，首先调动decodePageNumber从64位的长整型中解码获得Page页号，
	    // 然后从pageTable中获得也页号对应的MemoryBlock，最后将Page在操作系统内存中的地址与上面得到的
	    // 偏移量之和作为偏移量
      final int pageNumber = decodePageNumber(pagePlusOffsetAddress);
      assert (pageNumber >= 0 && pageNumber < PAGE_TABLE_SIZE);
      final MemoryBlock page = pageTable[pageNumber];
      assert (page != null);
      return page.getBaseOffset() + offsetInPage;
    }
  }

  /**
   * Clean up all allocated memory and pages. Returns the number of bytes freed. A non-zero return
   * value can be used to detect memory leaks.
   */
  // 用于清空所有Page和内存的方法
  public long cleanUpAllAllocatedMemory() {
    synchronized (this) {
      for (MemoryConsumer c: consumers) {
        if (c != null && c.getUsed() > 0) {
          // In case of failed task, it's normal to see leaked memory
          logger.debug("unreleased " + Utils.bytesToString(c.getUsed()) + " memory from " + c);
        }
      }
      consumers.clear();

      for (MemoryBlock page : pageTable) {
        if (page != null) {
          logger.debug("unreleased page: " + page + " in task " + taskAttemptId);
          memoryManager.tungstenMemoryAllocator().free(page);
        }
      }
      Arrays.fill(pageTable, null);
    }

    // release the memory that is not used by any consumer (acquired for pages in tungsten mode).
    memoryManager.releaseExecutionMemory(acquiredButNotUsed, taskAttemptId, tungstenMemoryMode);

    return memoryManager.releaseAllExecutionMemoryForTask(taskAttemptId);
  }

  /**
   * Returns the memory consumption, in bytes, for the current task.
   */
  // 用于获取任务尝试消费的所有内存的大小
  public long getMemoryConsumptionForThisTask() {
    return memoryManager.getExecutionMemoryUsageForTask(taskAttemptId);
  }

  /**
   * Returns Tungsten memory mode
   */
  public MemoryMode getTungstenMemoryMode() {
    return tungstenMemoryMode;
  }
}
