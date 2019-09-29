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

package org.apache.spark.scheduler

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode

/**
 * A Schedulable entity that represents collection of Pools or TaskSetManagers
 */
private[spark] class Pool(
    val poolName: String,
    val schedulingMode: SchedulingMode, // Pool的构造器属性之一，表示调度模式(SchedulingMode).
    initMinShare: Int, // minShare 的初始值
    initWeight: Int) // weight的初始值
  extends Schedulable with Logging {

  // 用于存储Schedulable，由于Schedulable只有Pool和TaskSetManager两个实现类，所以我们知道SchedulableQueue是一个可以嵌套的层次结构
  val schedulableQueue = new ConcurrentLinkedQueue[Schedulable]

  // 调度名称与Schedulable的映射关系
  val schedulableNameToSchedulable = new ConcurrentHashMap[String, Schedulable]
  // 用于公平调度算法的权重
  var weight = initWeight
  // 用于公平调度算法的参考值
  var minShare = initMinShare
  // 当前正在运行的任务数量
  var runningTasks = 0
  // 进行调度的优先级
  var priority = 0

  // 调度池或TaskSetManager所属Stage的身份标识
  // A pool's stage id is used to break the tie in scheduling.
  var stageId = -1
  // Pool的构造器属性之一，表示Pool的名称
  var name = poolName
  // 当前Pool的父Pool
  var parent: Pool = null

  // 任务集合的调度算法，默认为FIFOSchedulingAlgorithm
  var taskSetSchedulingAlgorithm: SchedulingAlgorithm = {
    schedulingMode match {
      case SchedulingMode.FAIR =>
        new FairSchedulingAlgorithm()
      case SchedulingMode.FIFO =>
        new FIFOSchedulingAlgorithm()
      case _ =>
        val msg = "Unsupported scheduling mode: $schedulingMode. Use FAIR or FIFO instead."
        throw new IllegalArgumentException(msg)
    }
  }

  // 用于将Schedulable添加到schedulableQueue和schedulableNameToSchedulable中，并将Schedulable的父亲设置为当前Pool
  override def addSchedulable(schedulable: Schedulable) {
    require(schedulable != null)
    schedulableQueue.add(schedulable)
    schedulableNameToSchedulable.put(schedulable.name, schedulable)
    schedulable.parent = this
  }

  // 用于将指定的Schedulable从schedulableQueue和schedulableNameToSchedulable中移除
  override def removeSchedulable(schedulable: Schedulable) {
    schedulableQueue.remove(schedulable)
    schedulableNameToSchedulable.remove(schedulable.name)
  }

  // 用于根据指定名称查找Schedualble
  override def getSchedulableByName(schedulableName: String): Schedulable = {
    // 从当前Pool找到指定名称的Schedulable
    if (schedulableNameToSchedulable.containsKey(schedulableName)) {
      return schedulableNameToSchedulable.get(schedulableName)
    }
    // 从子Schedulable中查找
    for (schedulable <- schedulableQueue.asScala) {
      val sched = schedulable.getSchedulableByName(schedulableName)
      if (sched != null) {
        return sched
      }
    }
    null
  }

  // 用于当某个Executor丢失后，调用当前Pool的schedulableQueue中的各个Schedulable（可以为子调度池，也可能是TaskSetManager）
  // 的executorLost方法。TaskSetManager的executorLost方法进而将再此Executor上运行的Task作为任务失败处理，并重新提交这些任务。
  override def executorLost(executorId: String, host: String, reason: ExecutorLossReason) {
    schedulableQueue.asScala.foreach(_.executorLost(executorId, host, reason))
  }

  // 用于检查当前Pool中是否需要推断执行的任务。checkSpeculatableTasks实际通过迭代调用shedulableQueue中的各个值Schedulable的
  // checkSpeculatableTasks方法来实现。Pool和TaskSetManager的checkSpeculatableTasks方法，一起实现了按照深度遍历算法从调度池
  // 中查找可推断执行的任务。
  override def checkSpeculatableTasks(minTimeToSpeculation: Int): Boolean = {
    var shouldRevive = false
    for (schedulable <- schedulableQueue.asScala) {
      shouldRevive |= schedulable.checkSpeculatableTasks(minTimeToSpeculation)
    }
    shouldRevive
  }

  // 用于对当前Pool中的所有TaskSetManager按照调度算法进行排序，并返回排序后的TaskSetManager.
  // getSortedTaskSetQueue实际是通过迭代调用schedulableQueue中的各个子Schedulable的getSortedTaskSetQueue方法实现的
  override def getSortedTaskSetQueue: ArrayBuffer[TaskSetManager] = {
    var sortedTaskSetQueue = new ArrayBuffer[TaskSetManager]
    val sortedSchedulableQueue =
      schedulableQueue.asScala.toSeq.sortWith(taskSetSchedulingAlgorithm.comparator)
    for (schedulable <- sortedSchedulableQueue) {
      sortedTaskSetQueue ++= schedulable.getSortedTaskSetQueue
    }
    sortedTaskSetQueue
  }

  // 用于增加当前Pool及其父Pool中记录的当前正在运行的任务数量
  def increaseRunningTasks(taskNum: Int) {
    runningTasks += taskNum
    if (parent != null) {
      parent.increaseRunningTasks(taskNum)
    }
  }

  // 用于减少当前Pool及其父Pool中记录的当前正在运行的任务数量
  def decreaseRunningTasks(taskNum: Int) {
    runningTasks -= taskNum
    if (parent != null) {
      parent.decreaseRunningTasks(taskNum)
    }
  }
}
