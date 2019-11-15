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

import java.io.NotSerializableException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.math.{max, min}
import scala.util.control.NonFatal

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.SchedulingMode._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.util.{AccumulatorV2, Clock, SystemClock, Utils}

/**
 * Schedules the tasks within a single TaskSet in the TaskSchedulerImpl. This class keeps track of
 * each task, retries tasks if they fail (up to a limited number of times), and
 * handles locality-aware scheduling for this TaskSet via delay scheduling. The main interfaces
 * to it are resourceOffer, which asks the TaskSet whether it wants to run a task on one node,
 * and statusUpdate, which tells it that one of its tasks changed state (e.g. finished).
 *
 * THREADING: This class is designed to only be called from code with a lock on the
 * TaskScheduler (e.g. its event handlers). It should not be called from other threads.
 *
 * @param sched           the TaskSchedulerImpl associated with the TaskSetManager
 * @param taskSet         the TaskSet to manage scheduling for
 * @param maxTaskFailures if any particular task fails this number of times, the entire
 *                        task set will be aborted
 */

// Pool和TaskSetManager中对推断执行的操作分为两类：一类是可推断任务的检查和缓存；另一类是从缓存中找到可推断任务进行推断执行。
// Pool和TaskSetManager的checkSpeculatableTasks方法实现了按照深度遍历算法对可推断任务的检测与缓存。
// TaskSetManager的depueueSpeculative方法则实现了从缓存中找到可推断任务进行推断执行。
// TaskSetManager除提供了Task推断和本地性的方法外，还有很多方法
private[spark] class TaskSetManager(
    sched: TaskSchedulerImpl,
    val taskSet: TaskSet,
    val maxTaskFailures: Int, // 最大任务失败次数
    clock: Clock = new SystemClock()) extends Schedulable with Logging {

  // sparkConf
  private val conf = sched.sc.conf

  // Quantile of tasks at which to start speculation
  // 开始推断执行的任务分数
  val SPECULATION_QUANTILE = conf.getDouble("spark.speculation.quantile", 0.75)
  // 推断的乘数。SPECULATION_MULTIPLIER将用于可推断执行Task的检查
  val SPECULATION_MULTIPLIER = conf.getDouble("spark.speculation.multiplier", 1.5)

  // 结果总大小的直接限制。可通过spark.driver.maxResultSize属性进行配置，默认为1GB
  // Limit of bytes for total size of results (default is 1GB)
  val maxResultSize = Utils.getMaxResultSize(conf)

  // Serializer for closures and tasks.
  // 即SparkEnv
  val env = SparkEnv.get
  // 即SparkEnv的closureSerializer的实例
  val ser = env.closureSerializer.newInstance()

  // TaskSet包含的Task数组
  val tasks = taskSet.tasks
  // TaskSet包含的Task的数量
  val numTasks = tasks.length
  // 对每个Task的复制运行数进行记录的数组。copiesRunning按照索引与tasks数组的同一索引位置的Task相对应，记录对应Task的复制运行数量
  val copiesRunning = new Array[Int](numTasks)
  // 对每个Task是否执行成功进行记录的数组。successful按照索引与tasks数组的同一索引位置的Task相对应，记录对应Task是否执行成功
  val successful = new Array[Boolean](numTasks)
  // 对每个Task的执行失败次数进行记录的数组。numFailures按照索引与tasks数组的同一索引位置的Task相对应，记录对应Task的执行失败次数
  private val numFailures = new Array[Int](numTasks)

  // 对每个Task的所有执行尝试信息进行记录的数组。taskAttempts按照索引与tasks数组的同一索引位置的Task相对应，记录对应Task的所有Task
  // 尝试信息（即TaskInfo）
  val taskAttempts = Array.fill[List[TaskInfo]](numTasks)(Nil)
  // 执行成功的Task数量
  var tasksSuccessful = 0

  // 用于公平调度算法的权重
  var weight = 1
  // 用于公平调度算法的参考值
  var minShare = 0
  // 进行调度的优先级
  var priority = taskSet.priority
  // 调度池所属的Stage的身份标识
  var stageId = taskSet.stageId
  // TaskSetManager的名称，可能会参与到公平调度算法中
  val name = "TaskSet_" + taskSet.id
  // TaskSetManager的父Pool
  var parent: Pool = null
  // 所有Task执行结果的总大小
  var totalResultSize = 0L
  // 计算过的Task数量
  var calculatedTasks = 0

  // TaskSetManager所管理的TaskSet的Executor或节点的黑名单
  private val taskSetBlacklistHelperOpt: Option[TaskSetBlacklist] = {
    if (BlacklistTracker.isBlacklistEnabled(conf)) {
      Some(new TaskSetBlacklist(conf, stageId, clock))
    } else {
      None
    }
  }

  // 正在运行的Task的数量。
  val runningTasksSet = new HashSet[Long]

  // 正在运行的Task的数量
  override def runningTasks: Int = runningTasksSet.size

  // True once no more tasks should be launched for this task set manager. TaskSetManagers enter
  // the zombie state once at least one attempt of each task has completed successfully, or if the
  // task set is aborted (for example, because it was killed).  TaskSetManagers remain in the zombie
  // state until all tasks have finished running; we keep TaskSetManagers that are in the zombie
  // state in order to continue to track and account for the running tasks.
  // TODO: We should kill any running task attempts when the task set manager becomes a zombie.
  // 当TaskSetManager所管理的TaskSet中的所有Task都执行成功了，不再有更多的Task尝试被启动时，就处于“僵尸”状态。
  // 例如，每个Task至少有一次尝试成功，或者TaskSet被舍弃了，TaskSetManager将会进入“僵尸”状态。直到所有的Task
  // 都运行成功为止，TaskSetManager将一直保持在“僵尸”状态。TaskSetManager的“僵尸”状态并不是无用的，在这种
  // 状态下TaskSetManager将继续跟踪、记录正在运行的Task。
  var isZombie = false

  // Set of pending tasks for each executor. These collections are actually
  // treated as stacks, in which new tasks are added to the end of the
  // ArrayBuffer and removed from the end. This makes it faster to detect
  // tasks that repeatedly fail because whenever a task failed, it is put
  // back at the head of the stack. These collections may contain duplicates
  // for two reasons:
  // (1): Tasks are only removed lazily; when a task is launched, it remains
  // in all the pending lists except the one that it was launched from.
  // (2): Tasks may be re-added to these lists multiple times as a result
  // of failures.
  // Duplicates are handled in dequeueTaskFromList, which ensures that a
  // task hasn't already started running before launching it.
  // 每个Executor上待处理的Task的集合，即Executor的身份标识与待处理Task的身份标识的集合之间的映射关系
  private val pendingTasksForExecutor = new HashMap[String, ArrayBuffer[Int]]

  // Set of pending tasks for each host. Similar to pendingTasksForExecutor,
  // but at host level.
  // 每个Host上待处理的Task的集合，即Host的身份标识与待处理Task的身份标识的集合之间的映射关系
  private val pendingTasksForHost = new HashMap[String, ArrayBuffer[Int]]

  // Set of pending tasks for each rack -- similar to the above.
  // 每个机架上待处理的Task的集合，即机架的身份标识与待处理Task的身份标识的集合之间的映射关系
  private val pendingTasksForRack = new HashMap[String, ArrayBuffer[Int]]

  // Set containing pending tasks with no locality preferences.
  // 没有任何本地性偏好的待处理Task的身份标识的集合
  var pendingTasksWithNoPrefs = new ArrayBuffer[Int]

  // Set containing all pending tasks (also used as a stack, as above).
  // 所有待处理的Task的身份标识的集合
  val allPendingTasks = new ArrayBuffer[Int]

  // Tasks that can be speculated. Since these will be a small fraction of total
  // tasks, we'll just hold them in a HashSet.
  // 能够进行推断执行的Task的身份标识的集合。这类Task占所有Task的比例非常小。所谓推断执行，是指当Task的一次尝试非常缓慢，
  // 根据推断，如果此时可以另起一次尝试运行，后来的尝试运行也比原先的尝试运行要快。
  val speculatableTasks = new HashSet[Int]

  // Task的身份标识与Task尝试的信息之间的映射关系。
  // Task index, start and finish time for each task attempt (indexed by task ID)
  val taskInfos = new HashMap[Long, TaskInfo]

  // How frequently to reprint duplicate exceptions in full, in milliseconds
  val EXCEPTION_PRINT_INTERVAL =
    conf.getLong("spark.logging.exceptionPrintInterval", 10000)

  // Map of recent exceptions (identified by string representation and top stack frame) to
  // duplicate count (how many times the same exception has appeared) and time the full exception
  // was printed. This should ideally be an LRU map that can drop old exceptions automatically.
  val recentExceptions = HashMap[String, (Int, Long)]()

  // Figure out the current map output tracker epoch and set it on all tasks
  // 即MapOutputTracker的epoch，用于故障转移
  val epoch = sched.mapOutputTracker.getEpoch
  logDebug("Epoch for " + taskSet + ": " + epoch)
  for (t <- tasks) {
    t.epoch = epoch
  }

  // Add all our tasks to the pending lists. We do this in reverse order
  // of task index so that tasks with low indices get launched first.
  for (i <- (0 until numTasks).reverse) {
    addPendingTask(i)
  }

  // Figure out which locality levels we have in our TaskSet, so we can do delay scheduling
  // Task的本地性级别的数组。
  var myLocalityLevels = computeValidLocalityLevels()
  // 与myLcalityLevels中的每个本地性级别相对应，表示对应本地性级别的等待时间。localityWaits实际是对
  // myLoclityLevels中的每个本地性级别应用getLocalityWait方法获取的
  var localityWaits = myLocalityLevels.map(getLocalityWait) // Time to wait at each level

  // Delay scheduling variables: we keep track of our current locality level and the time we
  // last launched a task at that level, and move up a level when localityWaits[curLevel] expires.
  // We then move down if we manage to launch a "more local" task.
  // 当前的本地性级别在myLocalityLevels中的索引
  var currentLocalityIndex = 0    // Index of our current locality level in validLocalityLevels
  // 在当前的本地性级别上运行Task的时间
  var lastLaunchTime = clock.getTimeMillis()  // Time we last launched a task at this level

  override def schedulableQueue: ConcurrentLinkedQueue[Schedulable] = null

  override def schedulingMode: SchedulingMode = SchedulingMode.NONE

  // 当发现序列化后的Task的大小超过100KB,此属性将被设置为true，并且打印警告级别的日志
  var emittedTaskSizeWarning = false

  /** Add a task to all the pending-task lists that it should be on. */
  // 用于将待处理Task的索引按照Task的偏好位置，添加到pendingTasksForExecutor、pendingTasksForHost、
  // pendingTasksForRack、pendingTasksWithNoPrefs、allPendingTasks等缓存中。
  private def addPendingTask(index: Int) {
    for (loc <- tasks(index).preferredLocations) {
      loc match {
        case e: ExecutorCacheTaskLocation =>
          pendingTasksForExecutor.getOrElseUpdate(e.executorId, new ArrayBuffer) += index
        case e: HDFSCacheTaskLocation =>
          val exe = sched.getExecutorsAliveOnHost(loc.host)
          exe match {
            case Some(set) =>
              for (e <- set) {
                pendingTasksForExecutor.getOrElseUpdate(e, new ArrayBuffer) += index
              }
              logInfo(s"Pending task $index has a cached location at ${e.host} " +
                ", where there are executors " + set.mkString(","))
            case None => logDebug(s"Pending task $index has a cached location at ${e.host} " +
                ", but there are no executors alive there.")
          }
        case _ =>
      }
      pendingTasksForHost.getOrElseUpdate(loc.host, new ArrayBuffer) += index
      for (rack <- sched.getRackForHost(loc.host)) {
        pendingTasksForRack.getOrElseUpdate(rack, new ArrayBuffer) += index
      }
    }

    if (tasks(index).preferredLocations == Nil) {
      pendingTasksWithNoPrefs += index
    }

    allPendingTasks += index  // No point scanning this whole list to find the old task there
  }

  /**
   * Return the pending tasks list for a given executor ID, or an empty list if
   * there is no map entry for that host
   */
  private def getPendingTasksForExecutor(executorId: String): ArrayBuffer[Int] = {
    pendingTasksForExecutor.getOrElse(executorId, ArrayBuffer())
  }

  /**
   * Return the pending tasks list for a given host, or an empty list if
   * there is no map entry for that host
   */
  private def getPendingTasksForHost(host: String): ArrayBuffer[Int] = {
    pendingTasksForHost.getOrElse(host, ArrayBuffer())
  }

  /**
   * Return the pending rack-local task list for a given rack, or an empty list if
   * there is no map entry for that rack
   */
  private def getPendingTasksForRack(rack: String): ArrayBuffer[Int] = {
    pendingTasksForRack.getOrElse(rack, ArrayBuffer())
  }

  /**
   * Dequeue a pending task from the given list and return its index.
   * Return None if the list is empty.
   * This method also cleans up any tasks in the list that have already
   * been launched, since we want that to happen lazily.
   */
  // 此方法用于从给定的Task列表中按照索引，从高到低找到满足条件（不在黑名单、Task的复制运行数等于0、Task没有成功）的Task的索引
  private def dequeueTaskFromList(
      execId: String,
      host: String,
      list: ArrayBuffer[Int]): Option[Int] = {
    var indexOffset = list.size
    while (indexOffset > 0) {
      indexOffset -= 1
      val index = list(indexOffset)
      if (!isTaskBlacklistedOnExecOrNode(index, execId, host)) {
        // This should almost always be list.trimEnd(1) to remove tail
        list.remove(indexOffset)
        if (copiesRunning(index) == 0 && !successful(index)) {
          return Some(index)
        }
      }
    }
    None
  }

  /** Check whether a task is currently running an attempt on a given host */
  private def hasAttemptOnHost(taskIndex: Int, host: String): Boolean = {
    taskAttempts(taskIndex).exists(_.host == host)
  }

  private def isTaskBlacklistedOnExecOrNode(index: Int, execId: String, host: String): Boolean = {
    taskSetBlacklistHelperOpt.exists { blacklist =>
      blacklist.isNodeBlacklistedForTask(host, index) ||
        blacklist.isExecutorBlacklistedForTask(execId, index)
    }
  }

  /**
   * Return a speculative task for a given executor if any are available. The task should not have
   * an attempt running on this host, in case the host is slow. In addition, the task should meet
   * the given locality constraint.
   */
  // Labeled as protected to allow tests to override providing speculative tasks if necessary
  // 用于根据指定的Host、Executor 和 本地性级别，从可推断的Task中找出可推断的Task在TaskSet中的索引和相应的本地性级别
  protected def dequeueSpeculativeTask(execId: String, host: String, locality: TaskLocality.Value)
    : Option[(Int, TaskLocality.Value)] =
  {
    // 从speculatableTasks中移除已经完成的Task,保留还未完成的Task
    speculatableTasks.retain(index => !successful(index)) // Remove finished tasks from set

    // 此方法表示指定Task未在指定的Host上尝试运行，且指定的Host和Executor不在黑名单
    def canRunOnHost(index: Int): Boolean = {
      !hasAttemptOnHost(index, host) &&
        !isTaskBlacklistedOnExecOrNode(index, execId, host)
    }

    if (!speculatableTasks.isEmpty) {
      // Check for process-local tasks; note that tasks can be process-local
      // on multiple nodes when we replicate cached blocks, as in Spark Streaming
      // 对于speculatableTasks中所有未在指定的Host上尝试运行，且指定的Host和Executor不在黑名单的所有Task进行一下处理
      for (index <- speculatableTasks if canRunOnHost(index)) {
        // 获取Task的偏好位置
        val prefs = tasks(index).preferredLocations
        // 获取Task偏好的Executor；
        val executors = prefs.flatMap(_ match {
          case e: ExecutorCacheTaskLocation => Some(e.executorId)
          case _ => None
        });
        // 如果Task偏好的Executor中包含指定的Executor,那么将此Task的索引从speculatableTasks中移除，
        // 并返回此Task的索引与PROCESS_LOCAL的对偶
        if (executors.contains(execId)) { // 找到了在指定的Executor上推断执行的Task
          speculatableTasks -= index
          return Some((index, TaskLocality.PROCESS_LOCAL))
        }
      }

      // Check for node-local tasks
      // 如果指定的本地性级别小于等于NODE_LOCAL，对于speculatableTasks中所有未在指定的Host上尝试运行
      if (TaskLocality.isAllowed(locality, TaskLocality.NODE_LOCAL)) {
        // 且指定的Host和Executor不在黑名单的所有Task进行以下处理
        for (index <- speculatableTasks if canRunOnHost(index)) {
          // 获取Task偏好的Host
          val locations = tasks(index).preferredLocations.map(_.host)
          // 如果Task偏好的Host中包含指定Host，那么将此Task的索引从speculatableTasks中移除,
          // 并返回此Task的索引与NODE_LOCAL的对偶
          if (locations.contains(host)) { // 找到了在本地节点上推断执行的Task
            speculatableTasks -= index
            return Some((index, TaskLocality.NODE_LOCAL))
          }
        }
      }

      // Check for no-preference tasks
      // 如果指定的本地性级别小于等于NO_Pref,对于speculatableTasks中所有未在指定的Host上尝试运行
      if (TaskLocality.isAllowed(locality, TaskLocality.NO_PREF)) {
        // 且指定的Host和Executor不在黑名单的所有Task进行以下处理
        for (index <- speculatableTasks if canRunOnHost(index)) {
          // 获取Task偏好位置
          val locations = tasks(index).preferredLocations
          // 如果Task没有偏好位置信息，那么将此Task的索引从speculatableTasks中移除，
          // 并返回此Task的索引与PROCESS_LOCAL的对偶
          if (locations.size == 0) {
            speculatableTasks -= index
            return Some((index, TaskLocality.PROCESS_LOCAL))
          }
        }
      }

      // Check for rack-local tasks
      // 如果指定的本地性级别小于等于RACK_LOCAL
      if (TaskLocality.isAllowed(locality, TaskLocality.RACK_LOCAL)) {
        // 对于speculatableTasks中所有未在指定的Host上尝试运行
        for (rack <- sched.getRackForHost(host)) {
          // 且指定的Host和Executor不在黑名单的所有Task进行以下处理
          for (index <- speculatableTasks if canRunOnHost(index)) {
            // 获取Task偏好的机架
            val racks = tasks(index).preferredLocations.map(_.host).flatMap(sched.getRackForHost)
            // 如果Task偏好的机架中包含指定的Host所在的机架，那么将此Task的索引从speculatableTasks中移除，
            // 并返回此Task的索引与RACK_LOCAL的对偶
            if (racks.contains(rack)) { // 找到了本地机架上推断执行的Task
              speculatableTasks -= index
              return Some((index, TaskLocality.RACK_LOCAL))
            }
          }
        }
      }

      // Check for non-local tasks
      // 如果指定的本地性级别小于等于ANY,对于speculatableTasks中所有未在指定的Host上尝试运行
      if (TaskLocality.isAllowed(locality, TaskLocality.ANY)) {
        // 且指定的Host和Executor不在黑名单的所有Task进行以下处理
        for (index <- speculatableTasks if canRunOnHost(index)) {
          // 那么将此Task的索引从speculatableTasks中移除，
          // 并返回此Task的索引与ANY的对偶
          speculatableTasks -= index // 找到可以在任何节点、机架上推断执行的Task
          return Some((index, TaskLocality.ANY))
        }
      }
    }

    None
  }

  /**
   * Dequeue a pending task for a given node and return its index and locality level.
   * Only search for tasks matching the given locality constraint.
   *
   * @return An option containing (task index within the task set, locality, is speculative?)
   */
  // 此方法用于根据指定的Host、Executor和本地性级别，找出要执行的(Task的索引、相应的本地性级别 、 是否进行推断执行)
  private def dequeueTask(execId: String, host: String, maxLocality: TaskLocality.Value)
    : Option[(Int, TaskLocality.Value, Boolean)] =
  {
    // 从pendingTasksForExecutor中找出指定Executor的待处理Task中满足条件（不在黑名单、Task的复制运行数等于0、Task没有成功）的Task索引，
    // 并返回此索引、PROCESS_LOCAL、非推断执行标记（false）的三元组。
    for (index <- dequeueTaskFromList(execId, host, getPendingTasksForExecutor(execId))) {
      return Some((index, TaskLocality.PROCESS_LOCAL, false))
    }

    // 如果本地性级别小于等于NODE_LOCAL
    if (TaskLocality.isAllowed(maxLocality, TaskLocality.NODE_LOCAL)) {
      for (index <- dequeueTaskFromList(execId, host, getPendingTasksForHost(host))) {
        return Some((index, TaskLocality.NODE_LOCAL, false))
      }
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.NO_PREF)) {
      // Look for noPref tasks after NODE_LOCAL for minimize cross-rack traffic
      for (index <- dequeueTaskFromList(execId, host, pendingTasksWithNoPrefs)) {
        return Some((index, TaskLocality.PROCESS_LOCAL, false))
      }
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.RACK_LOCAL)) {
      for {
        rack <- sched.getRackForHost(host)
        index <- dequeueTaskFromList(execId, host, getPendingTasksForRack(rack))
      } {
        return Some((index, TaskLocality.RACK_LOCAL, false))
      }
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.ANY)) {
      for (index <- dequeueTaskFromList(execId, host, allPendingTasks)) {
        return Some((index, TaskLocality.ANY, false))
      }
    }

    // find a speculative task if all others tasks have been scheduled
    // 调用dequeueSpeculativeTask 方法，从可推断的Task中找出可推断的Task的索引和相应的本地性级别，
    // 并返回可推断的Task的索引、相应的本地性级别、推断执行标记(true)的三元组
    dequeueSpeculativeTask(execId, host, maxLocality).map {
      case (taskIndex, allowedLocality) => (taskIndex, allowedLocality, true)}
  }

  /**
   * Respond to an offer of a single executor from the scheduler by finding a task
   *
   * NOTE: this function is either called with a maxLocality which
   * would be adjusted by delay scheduling algorithm or it will be with a special
   * NO_PREF locality which will be not modified
   *
   * @param execId the executor Id of the offered resource
   * @param host  the host Id of the offered resource
   * @param maxLocality the maximum locality we want to schedule the tasks at
   */
  // 用于给Task按照本地性分配资源
  @throws[TaskNotSerializableException]
  def resourceOffer(
      execId: String,
      host: String,
      maxLocality: TaskLocality.TaskLocality)
    : Option[TaskDescription] =
  {
    val offerBlacklisted = taskSetBlacklistHelperOpt.exists { blacklist =>
      blacklist.isNodeBlacklistedForTaskSet(host) ||
        blacklist.isExecutorBlacklistedForTaskSet(execId)
    }
    // resourceOffer方法在TaskSetManager处于“僵尸”状态并且分配Task的host和Executor在黑名单中时直接返回None，否则执行以下步骤
    if (!isZombie && !offerBlacklisted) {
      val curTime = clock.getTimeMillis()

      var allowedLocality = maxLocality

      // 计算允许的本地性级别。如果最大本地性级别不为NO_PREF，则调用getAllowedLocalityLevel获取允许的本地性级别和maxLocality的最小值
      if (maxLocality != TaskLocality.NO_PREF) {
        allowedLocality = getAllowedLocalityLevel(curTime)
        if (allowedLocality > maxLocality) {
          // We're not allowed to search for farther-away tasks
          allowedLocality = maxLocality
        }
      }

      // 调用dequeueTask方法，根据指定的Host、Executor和本地性级别，找出要执行的（Task的索引、相应的本地性级别、是否进行推断执行）三元组
      dequeueTask(execId, host, allowedLocality).map { case ((index, taskLocality, speculative)) =>
        // Found a task; do some bookkeeping and return a task description
        // 根据要执行的Task的索引找到要执行的Task
        val task = tasks(index)

        val taskId = sched.newTaskId() // 为Task生成新的身份标识
        // Do various bookkeeping
        // 将Task对应的copiesRunning信息加1，即增加复制运行数
        copiesRunning(index) += 1
        // 获取任务尝试号attemptNumber
        val attemptNum = taskAttempts(index).size
        // 创建任务尝试信息（TaskInfo）
        val info = new TaskInfo(taskId, index, attemptNum, curTime,
          execId, host, taskLocality, speculative)
        // 将Task的身份标识与TaskInfo的对应关系放入taskInfos
        taskInfos(taskId) = info
        // 将TaskInfo添加到taskAttempts中Task对应的TaskInfo列表中
        taskAttempts(index) = info :: taskAttempts(index)
        // Update our locality level for delay scheduling
        // NO_PREF will not affect the variables related to delay scheduling
        // 如果maxLocality不是NO_PREF，那么调用getLocalityIndex方法获取任务的本地性偏好级别在myLocalityLevels中的索引，
        // 并将最后一次运行时间设置为系统时间。
        if (maxLocality != TaskLocality.NO_PREF) {
          currentLocalityIndex = getLocalityIndex(taskLocality)
          lastLaunchTime = curTime
        }
        // Serialize and return the task
        val startTime = clock.getTimeMillis()
        // 将Task、用户添加的Jar包及其他文件序列化，得到需要经过网络传输的序列化Task。
        val serializedTask: ByteBuffer = try {
          Task.serializeWithDependencies(task, sched.sc.addedFiles, sched.sc.addedJars, ser)
        } catch {
          // If the task cannot be serialized, then there's no point to re-attempt the task,
          // as it will always fail. So just abort the whole task-set.
          case NonFatal(e) =>
            val msg = s"Failed to serialize task $taskId, not attempting to retry it."
            logError(msg, e)
            abort(s"$msg Exception during serialization: $e")
            throw new TaskNotSerializableException(e)
        }
        // 如果序列化Task的大小超过了100KB，且emittedTaskSizeWarning仍为false，
        // 则将emittedTaskSizeWarning设置为true并打印警告日志
        if (serializedTask.limit > TaskSetManager.TASK_SIZE_TO_WARN_KB * 1024 &&
          !emittedTaskSizeWarning) {
          emittedTaskSizeWarning = true
          logWarning(s"Stage ${task.stageId} contains a task of very large size " +
            s"(${serializedTask.limit / 1024} KB). The maximum recommended task size is " +
            s"${TaskSetManager.TASK_SIZE_TO_WARN_KB} KB.")
        }
        // 然后调用addRunningTask方法，向runningTasksSet中添加Task的身份标识，
        // 并增加父调度池及祖父调度池中记录的当前正在运行的任务数量
        addRunningTask(taskId)

        // We used to log the time it takes to serialize the task, but task size is already
        // a good proxy to task serialization time.
        // val timeTaken = clock.getTime() - startTime
        // 生成Task的名称，格式为："task $index.$attemptNumber in Stage stageId + "." + stageAttemptId"
        val taskName = s"task ${info.id} in stage ${taskSet.id}"
        logInfo(s"Starting $taskName (TID $taskId, $host, executor ${info.executorId}, " +
          s"partition ${task.partitionId}, $taskLocality, ${serializedTask.limit} bytes)")

        // 调用DAGScheduler的taskStarted方法向DAGSchedulerEventProcessLoop投递beginEvent事件
        sched.dagScheduler.taskStarted(task, info)
        // 创建并返回TaskDescription对象
        new TaskDescription(taskId = taskId, attemptNumber = attemptNum, execId,
          taskName, index, serializedTask)
      }
    } else {
      None
    }
  }

  // 用于当TaskSet可能已经完成的时候进行一些清理工作。
  private def maybeFinishTaskSet() {
    if (isZombie && runningTasks == 0) {
      sched.taskSetFinished(this)
    }
  }

  /**
   * Get the level we can launch tasks according to delay scheduling, based on current wait time.
   */
  // 用于获取允许的本地性级别
  private def getAllowedLocalityLevel(curTime: Long): TaskLocality.TaskLocality = {
    // Remove the scheduled or finished tasks lazily
    // 懒惰地删除计划或完成的任务
    // 此方法用于判断给定的待处理Task数组中是否有需要调度的Task
    def tasksNeedToBeScheduledFrom(pendingTaskIds: ArrayBuffer[Int]): Boolean = {
      var indexOffset = pendingTaskIds.size
      while (indexOffset > 0) {
        indexOffset -= 1
        val index = pendingTaskIds(indexOffset)
        // Task没有被复制运行且Task还未执行成功
        if (copiesRunning(index) == 0 && !successful(index)) {
          return true
        } else {
          pendingTaskIds.remove(indexOffset)
        }
      }
      false
    }
    // Walk through the list of tasks that can be scheduled at each location and returns true
    // if there are any tasks that still need to be scheduled. Lazily cleans up tasks that have
    // already been scheduled.
    // 此方法用于判断处理的Task集合中是否有需要调度的 Task。如果有返回true，否则将此key与Task集合的映射关系
    // 从待处理的Task集合中移除并返回false
    def moreTasksToRunIn(pendingTasks: HashMap[String, ArrayBuffer[Int]]): Boolean = {
      val emptyKeys = new ArrayBuffer[String]
      val hasTasks = pendingTasks.exists {
        case (id: String, tasks: ArrayBuffer[Int]) =>
          if (tasksNeedToBeScheduledFrom(tasks)) {
            true
          } else {
            emptyKeys += id
            false
          }
      }
      // The key could be executorId, host or rackId
      emptyKeys.foreach(id => pendingTasks.remove(id))
      hasTasks
    }

    while (currentLocalityIndex < myLocalityLevels.length - 1) {
      // 按照索引由高到低从myLocalityLevels读取本地性级别，
      val moreTasks = myLocalityLevels(currentLocalityIndex) match {  // 查找本地性级别是否有Task需要运行
          // 调用moreTasksRunIn方法判断本地性级别对应的待处理Task的缓存结构中是否有Task需要处理
        case TaskLocality.PROCESS_LOCAL => moreTasksToRunIn(pendingTasksForExecutor)
        case TaskLocality.NODE_LOCAL => moreTasksToRunIn(pendingTasksForHost)
        case TaskLocality.NO_PREF => pendingTasksWithNoPrefs.nonEmpty
        case TaskLocality.RACK_LOCAL => moreTasksToRunIn(pendingTasksForRack)
      }
      if (!moreTasks) {
        // This is a performance optimization: if there are no more tasks that can
        // be scheduled at a particular locality level, there is no point in waiting
        // for the locality wait timeout (SPARK-4939).
        // 如果没有Task需要处理，则将最后的运行时间设置为curTime
        lastLaunchTime = curTime
        logDebug(s"No tasks for locality level ${myLocalityLevels(currentLocalityIndex)}, " +
          s"so moving to locality level ${myLocalityLevels(currentLocalityIndex + 1)}")
        currentLocalityIndex += 1
      } else if (curTime - lastLaunchTime >= localityWaits(currentLocalityIndex)) {
        // Jump to the next locality level, and reset lastLaunchTime so that the next locality
        // wait timer doesn't immediately expire
        // 如果有Task需要处理且curTime与最后运行时间的差值大于当前本地性级别的等待时间，则将最后的运行时间
        // 增加当前本地性级别的等待时间（这样实际将直接跳入更低的本地性级别）
        lastLaunchTime += localityWaits(currentLocalityIndex) // 跳入更低的本地性级别
        logDebug(s"Moving to ${myLocalityLevels(currentLocalityIndex + 1)} after waiting for " +
          s"${localityWaits(currentLocalityIndex)}ms")
        currentLocalityIndex += 1
      } else {
        // 如果有Task需要处理且curTime与最后运行时间的差值小于当前本地性级别的等待时间，则返回当前本地性级别
        return myLocalityLevels(currentLocalityIndex) // 返回当前本地性级别
      }
    }
    // 为能找到允许的本地性级别，那么返回最低的本地性级别
    myLocalityLevels(currentLocalityIndex)
  }

  /**
   * Find the index in myLocalityLevels for a given locality. This is also designed to work with
   * localities that are not in myLocalityLevels (in case we somehow get those) by returning the
   * next-biggest level we have. Uses the fact that the last value in myLocalityLevels is ANY.
   */
  // 用于从myLocalityLevels中找到指定的本地性级别所对应的索引
  def getLocalityIndex(locality: TaskLocality.TaskLocality): Int = {
    var index = 0
    while (locality > myLocalityLevels(index)) {
      index += 1
    }
    index
  }

  /**
   * Check whether the given task set has been blacklisted to the point that it can't run anywhere.
   *
   * It is possible that this taskset has become impossible to schedule *anywhere* due to the
   * blacklist.  The most common scenario would be if there are fewer executors than
   * spark.task.maxFailures. We need to detect this so we can fail the task set, otherwise the job
   * will hang.
   *
   * There's a tradeoff here: we could make sure all tasks in the task set are schedulable, but that
   * would add extra time to each iteration of the scheduling loop. Here, we take the approach of
   * making sure at least one of the unscheduled tasks is schedulable. This means we may not detect
   * the hang as quickly as we could have, but we'll always detect the hang eventually, and the
   * method is faster in the typical case. In the worst case, this method can take
   * O(maxTaskFailures + numTasks) time, but it will be faster when there haven't been any task
   * failures (this is because the method picks one unscheduled task, and then iterates through each
   * executor until it finds one that the task isn't blacklisted on).
   */
  private[scheduler] def abortIfCompletelyBlacklisted(
      hostToExecutors: HashMap[String, HashSet[String]]): Unit = {
    taskSetBlacklistHelperOpt.foreach { taskSetBlacklist =>
      // Only look for unschedulable tasks when at least one executor has registered. Otherwise,
      // task sets will be (unnecessarily) aborted in cases when no executors have registered yet.
      if (hostToExecutors.nonEmpty) {
        // find any task that needs to be scheduled
        val pendingTask: Option[Int] = {
          // usually this will just take the last pending task, but because of the lazy removal
          // from each list, we may need to go deeper in the list.  We poll from the end because
          // failed tasks are put back at the end of allPendingTasks, so we're more likely to find
          // an unschedulable task this way.
          val indexOffset = allPendingTasks.lastIndexWhere { indexInTaskSet =>
            copiesRunning(indexInTaskSet) == 0 && !successful(indexInTaskSet)
          }
          if (indexOffset == -1) {
            None
          } else {
            Some(allPendingTasks(indexOffset))
          }
        }

        pendingTask.foreach { indexInTaskSet =>
          // try to find some executor this task can run on.  Its possible that some *other*
          // task isn't schedulable anywhere, but we will discover that in some later call,
          // when that unschedulable task is the last task remaining.
          val blacklistedEverywhere = hostToExecutors.forall { case (host, execsOnHost) =>
            // Check if the task can run on the node
            val nodeBlacklisted =
              taskSetBlacklist.isNodeBlacklistedForTaskSet(host) ||
              taskSetBlacklist.isNodeBlacklistedForTask(host, indexInTaskSet)
            if (nodeBlacklisted) {
              true
            } else {
              // Check if the task can run on any of the executors
              execsOnHost.forall { exec =>
                  taskSetBlacklist.isExecutorBlacklistedForTaskSet(exec) ||
                  taskSetBlacklist.isExecutorBlacklistedForTask(exec, indexInTaskSet)
              }
            }
          }
          if (blacklistedEverywhere) {
            val partition = tasks(indexInTaskSet).partitionId
            abort(s"Aborting $taskSet because task $indexInTaskSet (partition $partition) " +
              s"cannot run anywhere due to node and executor blacklist.  Blacklisting behavior " +
              s"can be configured via spark.blacklist.*.")
          }
        }
      }
    }
  }

  /**
   * Marks the task as getting result and notifies the DAG Scheduler
   */
  def handleTaskGettingResult(tid: Long): Unit = {
    val info = taskInfos(tid)
    info.markGettingResult()
    sched.dagScheduler.taskGettingResult(info)
  }

  /**
   * Check whether has enough quota to fetch the result with `size` bytes
   */
  def canFetchMoreResults(size: Long): Boolean = sched.synchronized {
    totalResultSize += size
    calculatedTasks += 1
    if (maxResultSize > 0 && totalResultSize > maxResultSize) {
      val msg = s"Total size of serialized results of ${calculatedTasks} tasks " +
        s"(${Utils.bytesToString(totalResultSize)}) is bigger than spark.driver.maxResultSize " +
        s"(${Utils.bytesToString(maxResultSize)})"
      logError(msg)
      abort(msg)
      false
    } else {
      true
    }
  }

  /**
   * Marks a task as successful and notifies the DAGScheduler that the task has ended.
   */
  // 此方法用于对Task的执行结果(对于map任务而言，实际是任务状态)进行处理
  def handleSuccessfulTask(tid: Long, result: DirectTaskResult[_]): Unit = {
    // 将taskInfos中缓存的TaskInfo标记为已经完成
    val info = taskInfos(tid)
    val index = info.index
    info.markFinished(TaskState.FINISHED)

    // 将Task的ID从正在运行的Task集合中移除，并减少正在运行的Task的数量
    removeRunningTask(tid)
    // This method is called by "TaskSchedulerImpl.handleSuccessfulTask" which holds the
    // "TaskSchedulerImpl" lock until exiting. To avoid the SPARK-7655 issue, we should not
    // "deserialize" the value when holding a lock to avoid blocking other threads. So we call
    // "result.value()" in "TaskResultGetter.enqueueSuccessfulTask" before reaching here.
    // Note: "result.value()" only deserializes the value when it's called at the first time, so
    // here "result.value()" just returns the value and won't block other threads.

    // 调用DAGScheduler的taskEnded方法，将Task的执行结果交给DAGScheduler处理
    sched.dagScheduler.taskEnded(tasks(index), Success, result.value(), result.accumUpdates, info)

    // Kill any other attempts for the same task (since those are unnecessary now that one
    // attempt completed successfully).
    // 杀死此Task正在运行的Task尝试
    for (attemptInfo <- taskAttempts(index) if attemptInfo.running) {
      logInfo(s"Killing attempt ${attemptInfo.attemptNumber} for task ${attemptInfo.id} " +
        s"in stage ${taskSet.id} (TID ${attemptInfo.taskId}) on ${attemptInfo.host} " +
        s"as the attempt ${info.attemptNumber} succeeded on ${info.host}")
      sched.backend.killTask(attemptInfo.taskId, attemptInfo.executorId, true)
    }


    if (!successful(index)) {
      // 增加运行成功的Task数量，并且将successful中代表此任务的状态设置为true
      tasksSuccessful += 1
      logInfo(s"Finished task ${info.id} in stage ${taskSet.id} (TID ${info.taskId}) in" +
        s" ${info.duration} ms on ${info.host} (executor ${info.executorId})" +
        s" ($tasksSuccessful/$numTasks)")
      // Mark successful and stop if all the tasks have succeeded.
      successful(index) = true

      // 如果成功运行的Task数量与TaskSet中的Task数量相同，说明此TaskSet中的所有Task都调度运行成功，于是将isZombie设置为true
      if (tasksSuccessful == numTasks) {
        isZombie = true
      }
    } else {
      logInfo("Ignoring task-finished event for " + info.id + " in stage " + taskSet.id +
        " because task " + index + " has already completed successfully")
    }
    // 调用maybeFinishTaskSet方法，在TaskSet可能已经完成的时候进行一些清理工作
    maybeFinishTaskSet()
  }

  /**
   * Marks the task as failed, re-adds it to the list of pending tasks, and notifies the
   * DAG Scheduler.
   */
  def handleFailedTask(tid: Long, state: TaskState, reason: TaskFailedReason) {
    val info = taskInfos(tid)
    if (info.failed || info.killed) {
      return
    }
    removeRunningTask(tid)
    info.markFinished(state)
    val index = info.index
    copiesRunning(index) -= 1
    var accumUpdates: Seq[AccumulatorV2[_, _]] = Seq.empty
    val failureReason = s"Lost task ${info.id} in stage ${taskSet.id} (TID $tid, ${info.host}," +
      s" executor ${info.executorId}): ${reason.toErrorString}"
    val failureException: Option[Throwable] = reason match {
      case fetchFailed: FetchFailed =>
        logWarning(failureReason)
        if (!successful(index)) {
          successful(index) = true
          tasksSuccessful += 1
        }
        isZombie = true
        None

      case ef: ExceptionFailure =>
        // ExceptionFailure's might have accumulator updates
        accumUpdates = ef.accums
        if (ef.className == classOf[NotSerializableException].getName) {
          // If the task result wasn't serializable, there's no point in trying to re-execute it.
          logError("Task %s in stage %s (TID %d) had a not serializable result: %s; not retrying"
            .format(info.id, taskSet.id, tid, ef.description))
          abort("Task %s in stage %s (TID %d) had a not serializable result: %s".format(
            info.id, taskSet.id, tid, ef.description))
          return
        }
        val key = ef.description
        val now = clock.getTimeMillis()
        val (printFull, dupCount) = {
          if (recentExceptions.contains(key)) {
            val (dupCount, printTime) = recentExceptions(key)
            if (now - printTime > EXCEPTION_PRINT_INTERVAL) {
              recentExceptions(key) = (0, now)
              (true, 0)
            } else {
              recentExceptions(key) = (dupCount + 1, printTime)
              (false, dupCount + 1)
            }
          } else {
            recentExceptions(key) = (0, now)
            (true, 0)
          }
        }
        if (printFull) {
          logWarning(failureReason)
        } else {
          logInfo(
            s"Lost task ${info.id} in stage ${taskSet.id} (TID $tid) on ${info.host}, executor" +
              s" ${info.executorId}: ${ef.className} (${ef.description}) [duplicate $dupCount]")
        }
        ef.exception

      case e: ExecutorLostFailure if !e.exitCausedByApp =>
        logInfo(s"Task $tid failed because while it was being computed, its executor " +
          "exited for a reason unrelated to the task. Not counting this failure towards the " +
          "maximum number of failures for the task.")
        None

      case e: TaskFailedReason =>  // TaskResultLost, TaskKilled, and others
        logWarning(failureReason)
        None
    }

    sched.dagScheduler.taskEnded(tasks(index), reason, null, accumUpdates, info)

    if (successful(index)) {
      logInfo(
        s"Task ${info.id} in stage ${taskSet.id} (TID $tid) failed, " +
        "but another instance of the task has already succeeded, " +
        "so not re-queuing the task to be re-executed.")
    } else {
      addPendingTask(index)
    }

    if (!isZombie && reason.countTowardsTaskFailures) {
      taskSetBlacklistHelperOpt.foreach(_.updateBlacklistForFailedTask(
        info.host, info.executorId, index))
      assert (null != failureReason)
      numFailures(index) += 1
      if (numFailures(index) >= maxTaskFailures) {
        logError("Task %d in stage %s failed %d times; aborting job".format(
          index, taskSet.id, maxTaskFailures))
        abort("Task %d in stage %s failed %d times, most recent failure: %s\nDriver stacktrace:"
          .format(index, taskSet.id, maxTaskFailures, failureReason), failureException)
        return
      }
    }
    maybeFinishTaskSet()
  }

  def abort(message: String, exception: Option[Throwable] = None): Unit = sched.synchronized {
    // TODO: Kill running tasks if we were not terminated due to a Mesos error
    sched.dagScheduler.taskSetFailed(taskSet, message, exception)
    isZombie = true
    maybeFinishTaskSet()
  }

  /** If the given task ID is not in the set of running tasks, adds it.
   *
   * Used to keep track of the number of running tasks, for enforcing scheduling policies.
   */
  // 用于向runningTasksSet中添加Task的身份标识，并调用TaskSetManager的父调度池的increaseRunningTasks，
  // 用于增加父Pool及其祖父Pool中记录的当前正在运行的任务数量
  def addRunningTask(tid: Long) {
    if (runningTasksSet.add(tid) && parent != null) {
      parent.increaseRunningTasks(1)
    }
  }

  /** If the given task ID is in the set of running tasks, removes it. */
  // removeRunningTask方法与addRunningTask方法相反
  def removeRunningTask(tid: Long) {
    if (runningTasksSet.remove(tid) && parent != null) {
      parent.decreaseRunningTasks(1)
    }
  }

  override def getSchedulableByName(name: String): Schedulable = {
    null
  }

  override def addSchedulable(schedulable: Schedulable) {}

  override def removeSchedulable(schedulable: Schedulable) {}

  override def getSortedTaskSetQueue(): ArrayBuffer[TaskSetManager] = {
    var sortedTaskSetQueue = new ArrayBuffer[TaskSetManager]()
    sortedTaskSetQueue += this
    sortedTaskSetQueue
  }

  /** Called by TaskScheduler when an executor is lost so we can re-enqueue our tasks */
  // 此方法在发生Executor丢失的情况下别调用
  override def executorLost(execId: String, host: String, reason: ExecutorLossReason) {
    // Re-enqueue any tasks that ran on the failed executor if this is a shuffle map stage,
    // and we are not using an external shuffle server which could serve the shuffle outputs.
    // The reason is the next stage wouldn't be able to fetch the data from this dead executor
    // so we would need to rerun these tasks on other executors.

    // 如果TaskSetManager管理的TaskSet中的Task为ShuffleMapTask,并且应用没有提供外部的Shuffle服务，
    if (tasks(0).isInstanceOf[ShuffleMapTask] && !env.blockManager.externalShuffleServiceEnabled) {

      // 那么对taskInfos中的所有在指定Executor上执行成功的Task执行一下操作
      for ((tid, info) <- taskInfos if info.executorId == execId) {
        val index = taskInfos(tid).index
        if (successful(index)) {

          // 将此Task标记为未成功
          successful(index) = false
          // 将此Task的复制运行数量减一
          copiesRunning(index) -= 1
          // 将当前TaskSetManager中成功执行的Task数量减一
          tasksSuccessful -= 1
          // 调动addPendingTask方法,用于将待处理Task的索引按照Task的偏好位置，添加到pendingTasksForExecutor、
          // pendingTasksForHost、pendingTasksForRack、pendingTasksWithNoPrefs、allPendingTasks等缓存中。
          addPendingTask(index)
          // Tell the DAGScheduler that this task was resubmitted so that it doesn't think our
          // stage finishes when a total of tasks.size tasks finish.
          // 调用DAGScheduler的taskEnded方法向DAGSchedulerEventProcessLoop发送CompletionEvent。
          // 此时的CompletionEvent将携带Resubmitted，避免CompletionEvent的关心者在Stage的所有Task都完成后
          // 认为当前Stage也执行完成，而是让它们知道此Task将被重新提交。
          sched.dagScheduler.taskEnded(
            tasks(index), Resubmitted, null, Seq.empty, info)
        }
      }
    }
    // 对taskInfos中的所有在身份标识为execId和Executor上正在运行的Task执行以下操作
    for ((tid, info) <- taskInfos if info.running && info.executorId == execId) {
      // 从ExecutorLossReason中获取Executor丢失的具体原因是否是由应用程序引起的。
      val exitCausedByApp: Boolean = reason match {
        case exited: ExecutorExited => exited.exitCausedByApp
        case ExecutorKilled => false
        case _ => true
      }
      // 调用handleFailedTask方法对失败的Task进行处理
      // 将任务标记为失败，将其重新添加到待处理任务列表中，并通知DAG调度程序。
      handleFailedTask(tid, TaskState.FAILED, ExecutorLostFailure(info.executorId, exitCausedByApp,
        Some(reason.toString)))
    }
    // recalculate valid locality levels and waits when executor is lost
    // 重新计算本地性级别
    recomputeLocality()
  }

  /**
   * Check for tasks to be speculated and return true if there are any. This is called periodically
   * by the TaskScheduler.
   *
   * TODO: To make this scale to large jobs, we need to maintain a list of running tasks, so that
   * we don't scan the whole task set. It might also help to make this sorted by launch time.
   */
  // 用于检查当前TaskSetManager中是否有需要推断的任务
  override def checkSpeculatableTasks(minTimeToSpeculation: Int): Boolean = {
    // Can't speculate if we only have one task, and no need to speculate if the task set is a
    // zombie.
    // 如果TaskSetManager处于“僵尸”状态且TaskSet只包含一个Task，那么返回false(即没有可以推断的Task)
    if (isZombie || numTasks == 1) {
      return false
    }
    var foundTasks = false
    // 计算进行推断的最小完成任务数量
    val minFinishedForSpeculation = (SPECULATION_QUANTILE * numTasks).floor.toInt
    logDebug("Checking for speculative tasks: minFinished = " + minFinishedForSpeculation)
    // 如果执行成功的Task数量（tasksSuccessful）大于等于minFinishedForSpeculation,并且tasksSuccessful大于0
    if (tasksSuccessful >= minFinishedForSpeculation && tasksSuccessful > 0) {
      val time = clock.getTimeMillis()
      // 获取taskInfos中执行成功的任务尝试信息(TaskInfo)中的执行时间,并排序
      val durations = taskInfos.values.filter(_.successful).map(_.duration).toArray
      Arrays.sort(durations)
      // 找出taskInfos中执行成功的任务尝试信息（TaskInfo）中执行时间处于中间的时间medianDuration
      val medianDuration = durations(min((0.5 * tasksSuccessful).round.toInt, durations.length - 1))
      // 计算进行推断的最小时间（threshold）
      val threshold = max(SPECULATION_MULTIPLIER * medianDuration, minTimeToSpeculation)
      // TODO: Threshold should also look at standard deviation of task durations and have a lower
      // bound based on that.
      logDebug("Task length threshold for speculation: " + threshold)
      // 遍历taskInfos,将符合推断条件的Task在TaskSet 中的索引放入speculatableTasks中。
      // 推断条件包括还未执行成功、复制运行数为一、运行时间大于threshold。
      // 如果有Task的索引被放入了speculatableTasks,那么返回true。
      for ((tid, info) <- taskInfos) {
        val index = info.index
        if (!successful(index) && copiesRunning(index) == 1 && info.timeRunning(time) > threshold &&
          !speculatableTasks.contains(index)) {
          logInfo(
            "Marking task %d in stage %s (on %s) as speculatable because it ran more than %.0f ms"
              .format(index, taskSet.id, info.host, threshold))
          speculatableTasks += index
          foundTasks = true
        }
      }
    }
    foundTasks
  }

  // 此方法用于获取某个本地性级别的等待时间
  private def getLocalityWait(level: TaskLocality.TaskLocality): Long = {
    // 获取默认的等待时间。默认为3s
    val defaultWait = conf.get("spark.locality.wait", "3s")
    // 根据本地性级别匹配到对应的配置属性。
    val localityWaitKey = level match {
      case TaskLocality.PROCESS_LOCAL => "spark.locality.wait.process"
      case TaskLocality.NODE_LOCAL => "spark.locality.wait.node"
      case TaskLocality.RACK_LOCAL => "spark.locality.wait.rack"
      case _ => null
    }

    // 获取本地性级别对应的等待时间，如果上一步没有获取属性名称，那么将返回0
    if (localityWaitKey != null) {
      conf.getTimeAsMs(localityWaitKey, defaultWait)
    } else {
      0L
    }
  }

  /**
   * Compute the locality levels used in this TaskSet. Assumes that all tasks have already been
   * added to queues using addPendingTask.
   *
   */
  // 用于计算有效的本地性级别，这样就可以将Task按照本地性级别，由高到低地分配给允许的Executor
  private def computeValidLocalityLevels(): Array[TaskLocality.TaskLocality] = {

    import TaskLocality.{PROCESS_LOCAL, NODE_LOCAL, NO_PREF, RACK_LOCAL, ANY}

    val levels = new ArrayBuffer[TaskLocality.TaskLocality]

    // 如果存在Executor上待处理的Task的集合，且PROCESS_LOCAL级别的等待时间不为0，还存在已被激活的Executor
    // （即pendingTasksForExecutor中的ExecutorId有存在于TaskScheludlerImp的executorIdToRunningTaskIds中的），
    // 那么允许本地性级别里包括PROCESS_LOCAL
    if (!pendingTasksForExecutor.isEmpty && getLocalityWait(PROCESS_LOCAL) != 0 &&
        pendingTasksForExecutor.keySet.exists(sched.isExecutorAlive(_))) {
      levels += PROCESS_LOCAL  // 允许的本地性级别里包括PROCESS_LOCAL
    }
    if (!pendingTasksForHost.isEmpty && getLocalityWait(NODE_LOCAL) != 0 &&
        pendingTasksForHost.keySet.exists(sched.hasExecutorsAliveOnHost(_))) {
      levels += NODE_LOCAL  // 允许的本地性级别里包括NODE_LOCAL
    }
    if (!pendingTasksWithNoPrefs.isEmpty) {
      levels += NO_PREF   // 允许的本地性级别里包括NO_PREF
    }
    if (!pendingTasksForRack.isEmpty && getLocalityWait(RACK_LOCAL) != 0 &&
        pendingTasksForRack.keySet.exists(sched.hasHostAliveOnRack(_))) {
      levels += RACK_LOCAL  // 允许的本地性级别里包括RACK_LOCAL
    }
    levels += ANY  // 允许的本地性级别里包括ANY
    logDebug("Valid locality levels for " + taskSet + ": " + levels.mkString(", "))
    levels.toArray  // 返回所有允许的本地性级别
  }

  def recomputeLocality() {
    // 当前的本地性级别
    val previousLocalityLevel = myLocalityLevels(currentLocalityIndex)
    // 用于计算有效的本地性级别，这样就可以将Task按照本地性级别，由高到低地分配给允许的Executor
    myLocalityLevels = computeValidLocalityLevels()
    localityWaits = myLocalityLevels.map(getLocalityWait)
    currentLocalityIndex = getLocalityIndex(previousLocalityLevel)
  }

  def executorAdded() {
    recomputeLocality()
  }
}

private[spark] object TaskSetManager {
  // The user will be warned if any stages contain a task that has a serialized size greater than
  // this.
  val TASK_SIZE_TO_WARN_KB = 100
}
