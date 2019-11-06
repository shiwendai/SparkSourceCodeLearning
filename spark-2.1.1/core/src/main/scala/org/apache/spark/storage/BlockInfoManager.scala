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

package org.apache.spark.storage

import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag

import com.google.common.collect.ConcurrentHashMultiset

import org.apache.spark.{SparkException, TaskContext}
import org.apache.spark.internal.Logging


/**
 * Tracks metadata for an individual block.
 *
 * Instances of this class are _not_ thread-safe and are protected by locks in the
 * [[BlockInfoManager]].
 *
 * @param level the block's storage level. This is the requested persistence level, not the
 *              effective storage level of the block (i.e. if this is MEMORY_AND_DISK, then this
 *              does not imply that the block is actually resident in memory).
 * @param classTag the block's [[ClassTag]], used to select the serializer
 * @param tellMaster whether state changes for this block should be reported to the master. This
 *                   is true for most blocks, but is false for broadcast blocks.
 */

// BlockInfo用于描述块的元数据信息，包括存储级别、Block类型、大小、锁信息等
private[storage] class BlockInfo(
    val level: StorageLevel,   // BlockInfo所描述的Block的存储级别
    val classTag: ClassTag[_], // BlockInfo所描述的Block的类型
    val tellMaster: Boolean) { // BlockInfo所描述的Block是否需要告知Master

  /**
   * The size of the block (in bytes)
   */
  // BlockInfo所描述的Block的大小
  def size: Long = _size
  def size_=(s: Long): Unit = {
    _size = s
    checkInvariants()
  }
  private[this] var _size: Long = 0

  /**
   * The number of times that this block has been locked for reading.
   */
  // BlockInfo所描述的Block的被锁定读取的次数
  def readerCount: Int = _readerCount
  def readerCount_=(c: Int): Unit = {
    _readerCount = c
    checkInvariants()
  }
  private[this] var _readerCount: Int = 0

  /**
   * The task attempt id of the task which currently holds the write lock for this block, or
   * [[BlockInfo.NON_TASK_WRITER]] if the write lock is held by non-task code, or
   * [[BlockInfo.NO_WRITER]] if this block is not locked for writing.
   */
  // 任务尝试在对Block进行写操作前，首先必须获得对应BlockInfo的写锁，_writeTask用于保存任务尝试的ID
  // (每个任务在实际执行时，会多次尝试，每次尝试都会分配一个ID)
  def writerTask: Long = _writerTask
  def writerTask_=(t: Long): Unit = {
    _writerTask = t
    checkInvariants()
  }
  private[this] var _writerTask: Long = BlockInfo.NO_WRITER

  private def checkInvariants(): Unit = {
    // A block's reader count must be non-negative:
    assert(_readerCount >= 0)
    // A block is either locked for reading or for writing, but not for both at the same time:
    assert(_readerCount == 0 || _writerTask == BlockInfo.NO_WRITER)
  }

  checkInvariants()
}

private[storage] object BlockInfo {

  /**
   * Special task attempt id constant used to mark a block's write lock as being unlocked.
   */
  val NO_WRITER: Long = -1

  /**
   * Special task attempt id constant used to mark a block's write lock as being held by
   * a non-task thread (e.g. by a driver thread or by unit test code).
   */
  // 特殊任务尝试id常量，用于将块的写锁定标记为由非任务线程持有
  val NON_TASK_WRITER: Long = -1024
}

/**
 * Component of the [[BlockManager]] which tracks metadata for blocks and manages block locking.
 *
 * The locking interface exposed by this class is readers-writer lock. Every lock acquisition is
 * automatically associated with a running task and locks are automatically released upon task
 * completion or failure.
 *
 * This class is thread-safe.
 */
// BlockInfoManager的确对BlockInfo进行了一些简单的管理，但是BlockInfoManager将主要对Block的锁资源进行管理。
// BlockInfoManager对Block的锁管理采用了共享锁与排它锁，其中读锁是共享锁，写锁是排他锁
private[storage] class BlockInfoManager extends Logging {

  private type TaskAttemptId = Long

  /**
   * Used to look up metadata for individual blocks. Entries are added to this map via an atomic
   * set-if-not-exists operation ([[lockNewBlockForWriting()]]) and are removed
   * by [[removeBlock()]].
   */
    // BlockId与BlockInfo之间映射关系的缓存
  @GuardedBy("this")
  private[this] val infos = new mutable.HashMap[BlockId, BlockInfo]

  /**
   * Tracks the set of blocks that each task has locked for writing.
   */
    // 每次任务尝试的标识TaskAttemptId与执行获取的Block的写锁之间的映射关系。TaskAttemptId与写锁之间是一对多的关系，
    // 即一次任务尝试执行会获取零到多个Block的写锁。类型TaskAttemptId的本质是Long类型
  @GuardedBy("this")
  private[this] val writeLocksByTask =
    new mutable.HashMap[TaskAttemptId, mutable.Set[BlockId]]
      with mutable.MultiMap[TaskAttemptId, BlockId]

  /**
   * Tracks the set of blocks that each task has locked for reading, along with the number of times
   * that a block has been locked (since our read locks are re-entrant).
   */
    // 每次任务尝试执行的标识TaskAttemptId与执行获取的Block的读锁之间的映射关系。TaskAttemptId与读锁之间是一对多的关系，
    // 即一次任务尝试执行会获取零到多个Block的读锁，并且会记录对于同一个Block的读锁的占用次数
  @GuardedBy("this")
  private[this] val readLocksByTask =
    new mutable.HashMap[TaskAttemptId, ConcurrentHashMultiset[BlockId]]

  // ----------------------------------------------------------------------------------------------

  // Initialization for special task attempt ids:
  registerTask(BlockInfo.NON_TASK_WRITER)

  // ----------------------------------------------------------------------------------------------

  /**
   * Called at the start of a task in order to register that task with this [[BlockInfoManager]].
   * This must be called prior to calling any other BlockInfoManager methods from that task.
   */
  // 注册TaskAttemptId
  def registerTask(taskAttemptId: TaskAttemptId): Unit = synchronized {
    require(!readLocksByTask.contains(taskAttemptId),
      s"Task attempt $taskAttemptId is already registered")
    readLocksByTask(taskAttemptId) = ConcurrentHashMultiset.create()
  }

  /**
   * Returns the current task's task attempt id (which uniquely identifies the task), or
   * [[BlockInfo.NON_TASK_WRITER]] if called by a non-task thread.
   */
  // 获取任务上下文TaskContext中当前正在执行的任务尝试的任务尝试的TaskAttemptId.如果任务上下文TaskContext中
  // 没有任务尝试的TaskAttemptId，那么返回BlockInfo.NON_TASK_WRITER.
  private def currentTaskAttemptId: TaskAttemptId = {
    Option(TaskContext.get()).map(_.taskAttemptId()).getOrElse(BlockInfo.NON_TASK_WRITER)
  }

  /**
   * Lock a block for reading and return its metadata.
   *
   * If another task has already locked this block for reading, then the read lock will be
   * immediately granted to the calling task and its lock count will be incremented.
   *
   * If another task has locked this block for writing, then this call will block until the write
   * lock is released or will return immediately if `blocking = false`.
   *
   * A single task can lock a block multiple times for reading, in which case each lock will need
   * to be released separately.
   *
   * @param blockId the block to lock.
   * @param blocking if true (default), this call will block until the lock is acquired. If false,
   *                 this call will return immediately if the lock acquisition fails.
   * @return None if the block did not exist or was removed (in which case no lock is held), or
   *         Some(BlockInfo) (in which case the block is locked for reading).
   */
  // 锁定读
  def lockForReading(
      blockId: BlockId,
      blocking: Boolean = true): Option[BlockInfo] = synchronized {
    logTrace(s"Task $currentTaskAttemptId trying to acquire read lock for $blockId")
    do {
      // 从infos中获取BlockId对应的BlockInfo.如果缓存infos中没有对应的BlockInfo,则返回None
      infos.get(blockId) match {
        case None => return None
        case Some(info) =>
          // 如果Block的写锁没有被其他任务尝试线程占用，则由当前任务尝试线程持有锁，并返回BlockInfo
          if (info.writerTask == BlockInfo.NO_WRITER) {
            info.readerCount += 1
            readLocksByTask(currentTaskAttemptId).add(blockId)
            logTrace(s"Task $currentTaskAttemptId acquired read lock for $blockId")
            return Some(info)
          }
      }
      // 如果允许阻塞，那么当前线程将等待，直到占用写锁的线程释放Block的写锁后唤醒当前线程。如果占有写锁的线程一直不释放写锁，
      // 那么当前线程将出现"饥饿"状况，即可能无限期等待下去。
      if (blocking) {
        wait()
      }
    } while (blocking)
    None
  }

  /**
   * Lock a block for writing and return its metadata.
   *
   * If another task has already locked this block for either reading or writing, then this call
   * will block until the other locks are released or will return immediately if `blocking = false`.
   *
   * @param blockId the block to lock.
   * @param blocking if true (default), this call will block until the lock is acquired. If false,
   *                 this call will return immediately if the lock acquisition fails.
   * @return None if the block did not exist or was removed (in which case no lock is held), or
   *         Some(BlockInfo) (in which case the block is locked for writing).
   */
  def lockForWriting(
      blockId: BlockId,
      blocking: Boolean = true): Option[BlockInfo] = synchronized {
    logTrace(s"Task $currentTaskAttemptId trying to acquire write lock for $blockId")
    do {
      // 从infos中获取BlockId对应的BlockInfo
      infos.get(blockId) match {
        case None => return None
        case Some(info) =>
          // 如果Block的写锁没有被其他任务尝试线程暂用，且没有线程正在读取此Block，则由当前任务尝试线程持有写锁并返回BlockInfo。
          // 写锁没有被占用并且没有线程正在读取此Block的条件也说明了任务尝试执行线程不能同时获得同一个Block的读锁与写锁。
          if (info.writerTask == BlockInfo.NO_WRITER && info.readerCount == 0) {
            info.writerTask = currentTaskAttemptId
            writeLocksByTask.addBinding(currentTaskAttemptId, blockId)
            logTrace(s"Task $currentTaskAttemptId acquired write lock for $blockId")
            return Some(info)
          }
      }
      // 如果允许阻塞，那么当前线程将等待，知道占用写锁的线程释放Block的写锁后唤醒当前线程。
      if (blocking) {
        wait()
      }
    } while (blocking)
    None
  }

  /**
   * Throws an exception if the current task does not hold a write lock on the given block.
   * Otherwise, returns the block's BlockInfo.
   */
  def assertBlockIsLockedForWriting(blockId: BlockId): BlockInfo = synchronized {
    infos.get(blockId) match {
      case Some(info) =>
        if (info.writerTask != currentTaskAttemptId) {
          throw new SparkException(
            s"Task $currentTaskAttemptId has not locked block $blockId for writing")
        } else {
          info
        }
      case None =>
        throw new SparkException(s"Block $blockId does not exist")
    }
  }

  /**
   * Get a block's metadata without acquiring any locks. This method is only exposed for use by
   * [[BlockManager.getStatus()]] and should not be called by other code outside of this class.
   */
  private[storage] def get(blockId: BlockId): Option[BlockInfo] = synchronized {
    infos.get(blockId)
  }

  /**
   * Downgrades an exclusive write lock to a shared read lock.
   */
  // 锁降级
  def downgradeLock(blockId: BlockId): Unit = synchronized {
    logTrace(s"Task $currentTaskAttemptId downgrading write lock for $blockId")
    // 获取BlockId对应的BlockInfo
    val info = get(blockId).get
    require(info.writerTask == currentTaskAttemptId,
      s"Task $currentTaskAttemptId tried to downgrade a write lock that it does not hold on" +
        s" block $blockId")
    // 调用unlock 方法释放当前任务尝试线程从BlockId对应Block获取的写锁
    unlock(blockId)
    // 由于已经释放了BlockId对应Block的写锁，所以用非阻塞方式获取BlockId对应Block的读锁
    val lockOutcome = lockForReading(blockId, blocking = false)
    assert(lockOutcome.isDefined)
  }

  /**
   * Release a lock on the given block.
   */
  // 释放BlockId对应的Block上的锁
  def unlock(blockId: BlockId): Unit = synchronized {
    logTrace(s"Task $currentTaskAttemptId releasing lock for $blockId")
    // 获取BlockId对应的BlockInfo
    val info = get(blockId).getOrElse {
      throw new IllegalStateException(s"Block $blockId not found")
    }

    if (info.writerTask != BlockInfo.NO_WRITER) {
      // 如果当前任务尝试线程已经获得了Block的写锁，释放当前Block的写锁
      info.writerTask = BlockInfo.NO_WRITER
      writeLocksByTask.removeBinding(currentTaskAttemptId, blockId)
    } else {
      // 如果当前任务尝试线程没有获得Block的写锁，则释放当前Block的读锁。释放读锁实际是减少当前任务尝试线程已经获取的Block的读锁次数
      assert(info.readerCount > 0, s"Block $blockId is not locked for reading")
      info.readerCount -= 1
      val countsForTask = readLocksByTask(currentTaskAttemptId)
      val newPinCountForTask: Int = countsForTask.remove(blockId, 1) - 1
      assert(newPinCountForTask >= 0,
        s"Task $currentTaskAttemptId release lock on block $blockId more times than it acquired it")
    }
    notifyAll()
  }

  /**
   * Attempt to acquire the appropriate lock for writing a new block.
   * 尝试获取用于写入新块的适当锁定。
   * This enforces the first-writer-wins semantics. If we are the first to write the block,
   * then just go ahead and acquire the write lock. Otherwise, if another thread is already
   * writing the block, then we wait for the write to finish before acquiring the read lock.
   *
   * @return true if the block did not already exist, false otherwise. If this returns false, then
   *         a read lock on the existing block will be held. If this returns true, a write lock on
   *         the new block will be held.
   */
  def lockNewBlockForWriting(
      blockId: BlockId,
      newBlockInfo: BlockInfo): Boolean = synchronized {
    logTrace(s"Task $currentTaskAttemptId trying to put $blockId")
    // 获取BlockId对应的Block的读锁
    lockForReading(blockId) match {
      case Some(info) =>
        // Block already exists. This could happen if another thread races with us to compute
        // the same block. In this case, just keep the read lock and return.
        // 如果上一步能够获取到Block的读锁，则说明BlockId对应的Block已经存在。这种情况发生在多线程在写
        // 同一个Block时产生竞争，已经有线程率先一步，当前线程将没有必要再获得写锁，只需要返回false
        false
      case None =>
        // Block does not yet exist or is removed, so we are free to acquire the write lock
        // 如果没有获取到Block的读锁，则说明BlockId对应的Block还不存在。这种情况下，当前线程首先将BlockId
        // 与新的BlockInfo的映射关系放入infos,然后获取BlockId对应的Block的写锁，最后返回true
        infos(blockId) = newBlockInfo
        lockForWriting(blockId)
        true
    }
  }

  /**
   * Release all lock held by the given task, clearing that task's pin bookkeeping
   * structures and updating the global pin counts. This method should be called at the
   * end of a task (either by a task completion handler or in `TaskRunner.run()`).
   *
   * @return the ids of blocks whose pins were released
   */
  // 释放给定任务尝试线程所占用的所有Block的锁，并通知所有等待获取锁的线程
  def releaseAllLocksForTask(taskAttemptId: TaskAttemptId): Seq[BlockId] = {
    val blocksWithReleasedLocks = mutable.ArrayBuffer[BlockId]()

    val readLocks = synchronized {
      readLocksByTask.remove(taskAttemptId).get
    }
    val writeLocks = synchronized {
      writeLocksByTask.remove(taskAttemptId).getOrElse(Seq.empty)
    }

    for (blockId <- writeLocks) {
      infos.get(blockId).foreach { info =>
        assert(info.writerTask == taskAttemptId)
        info.writerTask = BlockInfo.NO_WRITER
      }
      blocksWithReleasedLocks += blockId
    }
    readLocks.entrySet().iterator().asScala.foreach { entry =>
      val blockId = entry.getElement
      val lockCount = entry.getCount
      blocksWithReleasedLocks += blockId
      synchronized {
        get(blockId).foreach { info =>
          info.readerCount -= lockCount
          assert(info.readerCount >= 0)
        }
      }
    }

    synchronized {
      notifyAll()
    }
    blocksWithReleasedLocks
  }

  /**
   * Returns the number of blocks tracked.
   */
  // 返回infos的大小,即所有Block的数量
  def size: Int = synchronized {
    infos.size
  }

  /**
   * Return the number of map entries in this pin counter's internal data structures.
   * This is used in unit tests in order to detect memory leaks.
   */
  private[storage] def getNumberOfMapEntries: Long = synchronized {
    size +
      readLocksByTask.size +
      readLocksByTask.map(_._2.size()).sum +
      writeLocksByTask.size +
      writeLocksByTask.map(_._2.size).sum
  }

  /**
   * Returns an iterator over a snapshot of all blocks' metadata. Note that the individual entries
   * in this iterator are mutable and thus may reflect blocks that are deleted while the iterator
   * is being traversed.
   */
  // 以迭代器形式返回infos
  def entries: Iterator[(BlockId, BlockInfo)] = synchronized {
    infos.toArray.toIterator
  }

  /**
   * Removes the given block and releases the write lock on it.
   *
   * This can only be called while holding a write lock on the given block.
   */
  // 移除BlockId对应的BlockInfo
  def removeBlock(blockId: BlockId): Unit = synchronized {
    logTrace(s"Task $currentTaskAttemptId trying to remove block $blockId")
    // 获取BlockId对应的BlockInfo
    infos.get(blockId) match {
      // 如果对BlockInfo正在写入的任务尝试线程是当前线程的话，当前线程才有权利去移除BlockInfo
      case Some(blockInfo) =>
        if (blockInfo.writerTask != currentTaskAttemptId) {
          throw new IllegalStateException(
            s"Task $currentTaskAttemptId called remove() on block $blockId without a write lock")
        } else {
          // 将BlockInfo从infos中移除
          infos.remove(blockId)
          // 将BlockInfo的读线程数清零
          blockInfo.readerCount = 0
          // 将BlockInfo的writerTask置为BlockInfo.NO_WRITER
          blockInfo.writerTask = BlockInfo.NO_WRITER
          writeLocksByTask.removeBinding(currentTaskAttemptId, blockId)
        }
      case None =>
        throw new IllegalArgumentException(
          s"Task $currentTaskAttemptId called remove() on non-existent block $blockId")
    }
    // 通知所有在BlockId对应的Block的锁上等待的线程
    notifyAll()
  }

  /**
   * Delete all state. Called during shutdown.
   */
  // 清除BlockInfoManager中的所有信息，并通知所有在BlockInfoManager管理的Block的锁上等待的线程
  def clear(): Unit = synchronized {
    infos.valuesIterator.foreach { blockInfo =>
      blockInfo.readerCount = 0
      blockInfo.writerTask = BlockInfo.NO_WRITER
    }
    infos.clear()
    readLocksByTask.clear()
    writeLocksByTask.clear()
    notifyAll()
  }

}
