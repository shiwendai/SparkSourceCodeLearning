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

import java.nio.ByteBuffer
import java.util.{Timer, TimerTask}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.collection.Set
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.Random

import org.apache.spark._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.scheduler.TaskLocality.TaskLocality
import org.apache.spark.scheduler.local.LocalSchedulerBackend
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.util.{AccumulatorV2, ThreadUtils, Utils}

/**
 * Schedules tasks for multiple types of clusters by acting through a SchedulerBackend.
 * It can also work with a local setup by using a [[LocalSchedulerBackend]] and setting
 * isLocal to true. It handles common logic, like determining a scheduling order across jobs, waking
 * up to launch speculative tasks, etc.
 *
 * Clients should first call initialize() and start(), then submit task sets through the
 * runTasks method.
 *
 * THREADING: [[SchedulerBackend]]s and task-submitting clients can call this class from multiple
 * threads, so it needs locks in public API methods to maintain its state. In addition, some
 * [[SchedulerBackend]]s synchronize on themselves when they want to send events here, and then
 * acquire a lock on us, so we need to make sure that we don't try to lock the backend while
 * we are holding a lock on ourselves.
 */
// TaskSchedulerImpl对Task的调度依赖于调度池Pool，所有需要被调度的TaskSet都被置于调度池中。调度池Pool通过调度算法
// 对每个TaskSet进行调度，并将被调度的TaskSet交给TaskSchedulerImpl进行资源调度。

// 任务调度器TaskScheduler定义了对任务进行调度的接口规范，允许向Spark调度系统插入不同的TaskScheduler实现，但目前只有TaskSchedulerImpl这一个具体实现。

// TaskScheduler只为单个Driver调度任务。TaskSchedulerImpl的功能包括接收DAGScheduler给每个Stage创建的Task集合，
// 按照调度算法将资源分配给Task，将Task交给Spark集群不同节点上的Executor运行，在这些Task执行失败时进行重试，通过
// 推断执行减轻落后的Task对整体作业进度的影响。

// Spark的资源调度分为两层：
// 第一层是Cluster Manager(在YARN模式下为Resource Manager, 在Mesos模式下为Mesos Master，在Standalone模式下为Master)
//       将资源分配给Application；
// 第二层是Application进一步将资源分配给Application的各个Task。TaskSchedulerImpl中的资源调度就是第二层的资源调度
private[spark] class TaskSchedulerImpl(
    val sc: SparkContext,
    val maxTaskFailures: Int, // 任务失败的最大次数
    isLocal: Boolean = false) // 是否是Local部署模式
  extends TaskScheduler with Logging
{
  def this(sc: SparkContext) = this(sc, sc.conf.get(config.MAX_TASK_FAILURES))

  // 即SparkConf
  val conf = sc.conf

  // 任务推断执行的时间间隔。
  // How often to check for speculative tasks
  val SPECULATION_INTERVAL_MS = conf.getTimeAsMs("spark.speculation.interval", "100ms")

  // Duplicate copies of a task will only be launched if the original copy has been running for
  // at least this amount of time. This is to avoid the overhead of launching speculative copies
  // of tasks that are very short.
  // 用于保证原始任务至少需要运行的时间。原始任务只有超过此时间限制，才允许启动副本任务。
  // 这可以避免原始任务执行太短的时间就被推断执行副本任务
  val MIN_TIME_TO_SPECULATION = 100

  // 对任务调度进行推断执行的ScheduledThreadPoolExecutor.
  private val speculationScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("task-scheduler-speculation")

  // 判断TaskSet饥饿的阈值
  // Threshold above which we warn user initial TaskSet may be starved
  val STARVATION_TIMEOUT_MS = conf.getTimeAsMs("spark.starvation.timeout", "15s")

  // 每个Task需要分配的CPU核数。默认为1
  // CPUs to request per task
  val CPUS_PER_TASK = conf.getInt("spark.task.cpus", 1)

  // TaskSetManagers are not thread safe, so any access to one should be synchronized
  // on this class.
  // 是用于StageId、stageAttemptId、TaskSetManager的二级缓存
  private val taskSetsByStageIdAndAttempt = new HashMap[Int, HashMap[Int, TaskSetManager]]

  // Protected by `this`
  // Task与所属TaskSetManager的映射关系
  private[scheduler] val taskIdToTaskSetManager = new HashMap[Long, TaskSetManager]
  // Task 与执行此 Task的Executor之间的映射关系
  val taskIdToExecutorId = new HashMap[Long, String]

  // 标记TaskSchedulerImpl是否已经接收到Task
  @volatile private var hasReceivedTask = false
  // 标记TaskSchedulerImpl接收的Task是否已经有运行过的
  @volatile private var hasLaunchedTask = false
  // 处理饥饿的定时器
  private val starvationTimer = new Timer(true)

  // 用于生成新提交Task的标识
  // Incrementing task IDs
  val nextTaskId = new AtomicLong(0)

  // 用于缓存Executor与运行在此Executor上的任务之间的映射关系，由此看出一个Executor上可以运行多个Task
  // IDs of the tasks running on each executor
  private val executorIdToRunningTaskIds = new HashMap[String, HashSet[Long]]

  def runningTasksByExecutors: Map[String, Int] = synchronized {
    executorIdToRunningTaskIds.toMap.mapValues(_.size)
  }

  // The set of executors we have on each host; this is used to compute hostsAlive, which
  // in turn is used to decide when we can attain data locality on a given host
  // 用于缓存机器的Host与运行在此机器上的Executor之间的映射关系
  protected val hostToExecutors = new HashMap[String, HashSet[String]]

  // 用于缓存机器所在的机架与机架上机器的Host之间的映射关系
  protected val hostsByRack = new HashMap[String, HashSet[String]]

  // Executor与Executor运行所有在机器的Host之间的映射关系
  protected val executorIdToHost = new HashMap[String, String]

  // Listener object to pass upcalls into
  var dagScheduler: DAGScheduler = null

  var backend: SchedulerBackend = null

  val mapOutputTracker = SparkEnv.get.mapOutputTracker

  // 调度池构建器
  var schedulableBuilder: SchedulableBuilder = null
  // 根调度池
  var rootPool: Pool = null
  // default scheduler is FIFO 调度模式配置
  private val schedulingModeConf = conf.get("spark.scheduler.mode", "FIFO")
  // 调度模式
  val schedulingMode: SchedulingMode = try {
    SchedulingMode.withName(schedulingModeConf.toUpperCase)
  } catch {
    case e: java.util.NoSuchElementException =>
      throw new SparkException(s"Unrecognized spark.scheduler.mode: $schedulingModeConf")
  }

  // 他的作用是通过线程池（此线程池有Executor.newFiexedThreadPool创建，大小默认为4），对Slave发送的Task的执行结果进行处理
  // This is a var so that we can reset it for testing purposes.
  private[spark] var taskResultGetter = new TaskResultGetter(sc.env, this)

  override def setDAGScheduler(dagScheduler: DAGScheduler) {
    this.dagScheduler = dagScheduler
  }

  // TaskSchedulerImpl的初始化
  def initialize(backend: SchedulerBackend) {
    // 使用参数传递的SchedulerBackend设置TaskSchedulerImpl的backend属性
    this.backend = backend
    // 创建根调度池
    // temporarily set rootPool name to empty
    rootPool = new Pool("", schedulingMode, 0, 0)
    schedulableBuilder = {
      // 根据调度模式，创建相应的调度池构造器。
      schedulingMode match {
        case SchedulingMode.FIFO =>
          new FIFOSchedulableBuilder(rootPool)
        case SchedulingMode.FAIR =>
          new FairSchedulableBuilder(rootPool, conf)
        case _ =>
          throw new IllegalArgumentException(s"Unsupported spark.scheduler.mode: $schedulingMode")
      }
    }
    // 调用调度池构造器的buildPools方法构建调度池
    schedulableBuilder.buildPools()
  }

  def newTaskId(): Long = nextTaskId.getAndIncrement()

  // TaskSchedulerImpl的启动
  override def start() {
    // 调用SchedulerBackend的Start方法启动SchedulerBackend
    backend.start()

    // 当应用不在Local模式下，并且设置了推断执行，那么设置一个执行间隔为SPECULATION_INTERVAL_MS(默认100ms)的检查可推断任务的定时器。
    // 此定时器通过调用checkSpeculatableTasks方法来检查可推断任务
    if (!isLocal && conf.getBoolean("spark.speculation", false)) {
      logInfo("Starting speculative execution thread")
      speculationScheduler.scheduleAtFixedRate(new Runnable {
        override def run(): Unit = Utils.tryOrStopSparkContext(sc) {
          checkSpeculatableTasks()
        }
      }, SPECULATION_INTERVAL_MS, SPECULATION_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
  }

  // 由于TaskSchedulerImpl对任务资源的运行依赖于SchedulerBackend，所以为了避免TaskSchedulerImpl在SchedulerBackend准备就绪之前，
  // 就将Task交给SchedulerBackend处理，因此实现了postStartHook方法御用等待SchedulerBackend准备就绪。
  override def postStartHook() {
    waitBackendReady()
  }

  // TaskSchedulerImpl与Task的提交
  // DAGScheduler将Stage中各个分区的Task封装为TaskSet后，会将TaskSet交给TaskSchedulerImpl处理。
  // TaskShcedulerImpl的submitTasks方法是这一过程的入口
  override def submitTasks(taskSet: TaskSet) {
    // 获取TaskSet中的所有Task
    val tasks = taskSet.tasks
    logInfo("Adding task set " + taskSet.id + " with " + tasks.length + " tasks")
    this.synchronized {
      // 创建TaskSetManager
      val manager = createTaskSetManager(taskSet, maxTaskFailures)
      // 在taskSetsByStageIdAndAttempt中设置TaskSet关联的Stage、Stage尝试 及 刚创建的TaskSetMananger之间的三级映射关系
      val stage = taskSet.stageId
      val stageTaskSets =
        taskSetsByStageIdAndAttempt.getOrElseUpdate(stage, new HashMap[Int, TaskSetManager])
      stageTaskSets(taskSet.stageAttemptId) = manager

      // 对当前TaskSet进行冲突检测，即taskSetsByStageIdAndAttempt中不应该存在同属于当前Stage，但是TaskSet却不相同的情况。
      val conflictingTaskSet = stageTaskSets.exists { case (_, ts) =>
        ts.taskSet != taskSet && !ts.isZombie
      }
      if (conflictingTaskSet) {
        throw new IllegalStateException(s"more than one active taskSet for stage $stage:" +
          s" ${stageTaskSets.toSeq.map{_._2.taskSet.id}.mkString(",")}")
      }

      // 调用调度池构造器的addTaskSetManager方法，将刚创建的TaskSetManager添加到调度池构建器的调度池中
      schedulableBuilder.addTaskSetManager(manager, manager.taskSet.properties)

      // 如果当前应用程序不是Local模式并且TaskShcedulerImpl还没接收到Task，那么设置一个定时器按照STARVATION_TIMEOUT_MS指定的时间
      // 间隔检查TaskSchedulerImpl的饥饿状况，当TaskSchedulerImpl已经运行Task后，取消此定时器。
      if (!isLocal && !hasReceivedTask) {
        starvationTimer.scheduleAtFixedRate(new TimerTask() {
          override def run() {
            if (!hasLaunchedTask) {
              logWarning("Initial job has not accepted any resources; " +
                "check your cluster UI to ensure that workers are registered " +
                "and have sufficient resources")
            } else {
              this.cancel()
            }
          }
        }, STARVATION_TIMEOUT_MS, STARVATION_TIMEOUT_MS)
      }
      // 将hasReceivedTask设置为true
      hasReceivedTask = true
    }
    // 调用SchedulerBanckend的reviveOffers方法给Task分配资源并运行Task
    backend.reviveOffers()
  }

  // Label as private[scheduler] to allow tests to swap in different task set managers if necessary
  private[scheduler] def createTaskSetManager(
      taskSet: TaskSet,
      maxTaskFailures: Int): TaskSetManager = {
    new TaskSetManager(this, taskSet, maxTaskFailures)
  }

  override def cancelTasks(stageId: Int, interruptThread: Boolean): Unit = synchronized {
    logInfo("Cancelling stage " + stageId)
    taskSetsByStageIdAndAttempt.get(stageId).foreach { attempts =>
      attempts.foreach { case (_, tsm) =>
        // There are two possible cases here:
        // 1. The task set manager has been created and some tasks have been scheduled.
        //    In this case, send a kill signal to the executors to kill the task and then abort
        //    the stage.
        // 2. The task set manager has been created but no tasks has been scheduled. In this case,
        //    simply abort the stage.
        tsm.runningTasksSet.foreach { tid =>
          val execId = taskIdToExecutorId(tid)
          backend.killTask(tid, execId, interruptThread)
        }
        tsm.abort("Stage %s cancelled".format(stageId))
        logInfo("Stage %d was cancelled".format(stageId))
      }
    }
  }

  /**
   * Called to indicate that all task attempts (including speculated tasks) associated with the
   * given TaskSetManager have completed, so state associated with the TaskSetManager should be
   * cleaned up.
   */
  def taskSetFinished(manager: TaskSetManager): Unit = synchronized {
    taskSetsByStageIdAndAttempt.get(manager.taskSet.stageId).foreach { taskSetsForStage =>
      taskSetsForStage -= manager.taskSet.stageAttemptId
      if (taskSetsForStage.isEmpty) {
        taskSetsByStageIdAndAttempt -= manager.taskSet.stageId
      }
    }
    manager.parent.removeSchedulable(manager)
    logInfo(s"Removed TaskSet ${manager.taskSet.id}, whose tasks have all completed, from pool" +
      s" ${manager.parent.name}")
  }

  // resourceOfferSingleTaskSet方法将遍历WorkerOffer，对每个WorkerOffer执行以下操作
  private def resourceOfferSingleTaskSet(
      taskSet: TaskSetManager,
      maxLocality: TaskLocality,
      shuffledOffers: Seq[WorkerOffer],
      availableCpus: Array[Int],
      tasks: IndexedSeq[ArrayBuffer[TaskDescription]]) : Boolean = {
    var launchedTask = false
    // 遍历WorkerOffer
    for (i <- 0 until shuffledOffers.size) {
      // 获取WorkerOffer的ExecutorId
      val execId = shuffledOffers(i).executorId
      // 获取WorkerOffer的Host
      val host = shuffledOffers(i).host
      // 如果WorkerOffer的可用的CPU核数大于等于CPUS_PER_TASK
      if (availableCpus(i) >= CPUS_PER_TASK) {
        try {
          // 调用TaskSetManager的resourceOffer方法，给符合条件的待处理Task创建TaskDescription
          for (task <- taskSet.resourceOffer(execId, host, maxLocality)) {
            // 将TaskDescription添加到Tasks数组
            tasks(i) += task
            val tid = task.taskId
            // 更新Task的身份标识与TaskSet、Executor的身份标识相关的缓存映射
            taskIdToTaskSetManager(tid) = taskSet
            taskIdToExecutorId(tid) = execId
            executorIdToRunningTaskIds(execId).add(tid)
            // 由于给Task分配了CPUS_PER_TASK指定数量的CPU内核数，因此WorkerOffer的可用的CPU核数减去CPUS_PER_TASK
            availableCpus(i) -= CPUS_PER_TASK
            assert(availableCpus(i) >= 0)
            launchedTask = true
          }
        } catch {
          case e: TaskNotSerializableException =>
            logError(s"Resource offer failed, task set ${taskSet.name} was not serializable")
            // Do not offer resources for this task, but don't throw an error to allow other
            // task sets to be submitted.
            return launchedTask
        }
      }
    }
    // 返回launchedTask，即是否已经给TaskSet中的某个Task分配到了资源
    return launchedTask
  }

  /**
   * Called by cluster manager to offer resources on slaves. We respond by asking our active task
   * sets for tasks in order of priority. We fill each node with tasks in a round-robin manner so
   * that tasks are balanced across the cluster.
   */
  // 以Local模式下SchedulerBackend的实现LocalSchedulerBackend为例，LocalSchedulerBackend的reviveOffers方法实际向
  // LocalEndpoint发送ReviveOffers消息。LocalEndpoint接收到ReviveOffers消息后，将调用LocalEndpoint自己的reviveOffers方法
  // 但reviveOffers方法最终将调用TaskSchedulerImpl的resourceOffers方法给Task分配资源。
  def resourceOffers(offers: IndexedSeq[WorkerOffer]): Seq[Seq[TaskDescription]] = synchronized {
    // Mark each slave as alive and remember its hostname
    // Also track if new executor is added
    var newExecAvail = false
    // 遍历WorkerOffer序列，对每一个WorkerOffer执行以下操作
    for (o <- offers) {
      // 更新Host与Executor的各种映射关系
      if (!hostToExecutors.contains(o.host)) {
        hostToExecutors(o.host) = new HashSet[String]()
      }
      if (!executorIdToRunningTaskIds.contains(o.executorId)) {
        hostToExecutors(o.host) += o.executorId

        // 调用TaskSchedulerImpl的executorAdded方法向DAGScheduler的
        // DAGSchedulerEventProcessLoop投递ExecutorAdded事件
        executorAdded(o.executorId, o.host)

        executorIdToHost(o.executorId) = o.host
        executorIdToRunningTaskIds(o.executorId) = HashSet[Long]()
        // 标记添加了新的Executor
        newExecAvail = true
      }
      // 更新Host与机架之间的关系，这的hostToExecutors及hostsByRack是为了在资源分配时计算Task本地性时使用
      for (rack <- getRackForHost(o.host)) {
        hostsByRack.getOrElseUpdate(rack, new HashSet[String]()) += o.host
      }
    }

    // Randomly shuffle offers to avoid always placing tasks on the same set of workers.
    // 对所有WorkerOffer随机洗牌，避免将任务总是分配给同样一组Worker
    val shuffledOffers = Random.shuffle(offers)
    // Build a list of tasks to assign to each worker.
    // 根据每个WorkerOffer的可用的CPU核数创建同等尺寸的TaskDescription数组
    val tasks = shuffledOffers.map(o => new ArrayBuffer[TaskDescription](o.cores))
    // 将每个WorkerOffer的可用CPU核数统计到可用CPU（availableCpus）数组中
    val availableCpus = shuffledOffers.map(o => o.cores).toArray
    // 调用rootPool的getSortedTaskSetQueue方法，对rootPool中所有TaskSetManager按照调度算法排序
    val sortedTaskSets = rootPool.getSortedTaskSetQueue
    for (taskSet <- sortedTaskSets) {
      logDebug("parentName: %s, name: %s, runningTasks: %s".format(
        taskSet.parent.name, taskSet.name, taskSet.runningTasks))
      // 如果newExecAvail为true，那么调用每个TaskSetManager的exeuctorAdded方法。此executorAdded方法实际调用了
      // computeValidLocalityLevels方法重新计算TaskSet的本地性。
      if (newExecAvail) {
        taskSet.executorAdded()
      }
    }

    // Take each TaskSet in our scheduling order, and then offer it each node in increasing order
    // of locality levels so that it gets a chance to launch local tasks on all of them.
    // NOTE: the preferredLocality order: PROCESS_LOCAL, NODE_LOCAL, NO_PREF, RACK_LOCAL, ANY
    // 遍历TaskSetManager,按照最大本地性原则调用resourceOfferSingleTaskSet方法，给单个TaskSet中的Task提供资源。
    // 如果在任务TaskSet所允许的本地性级别下，TaskSet中没有任何一个任务获得了资源，那么将调用TaskSetManager的
    // abortIfCompletelyBlacklisted方法，放弃在黑名单中的Task
    for (taskSet <- sortedTaskSets) {
      var launchedAnyTask = false
      var launchedTaskAtCurrentMaxLocality = false
      for (currentMaxLocality <- taskSet.myLocalityLevels) {
        do {
          launchedTaskAtCurrentMaxLocality = resourceOfferSingleTaskSet(
            taskSet, currentMaxLocality, shuffledOffers, availableCpus, tasks)
          launchedAnyTask |= launchedTaskAtCurrentMaxLocality
        } while (launchedTaskAtCurrentMaxLocality)
      }
      if (!launchedAnyTask) {
        taskSet.abortIfCompletelyBlacklisted(hostToExecutors)
      }
    }

    if (tasks.size > 0) {
      hasLaunchedTask = true
    }
    // 返回生成的TaskDescription列表，即已经获得了资源的任务列表
    return tasks
  }

  // Task在执行的时候会不断发送StatusUpdate消息，在Local模式下，LocalEndpoint接收到StatusUpdate消息后会先匹配执行
  // TaskSchedulerImpl的statusUpdate方法，然后调用reviveOffers方法给其他Task分配资源。

  // TaskSchedulerImpl的statusUpdate方法用于更新Task的状态。
  // Task的状态包括： RUNNING, FINISHED, FAILED, KILLED, LOST五种
  // 会从taskIdToTaskSetId、taskIdToExecutorId中移除此任务，并调用taskResultGetter的enqueueSuccessfulTask方法。
  def statusUpdate(tid: Long, state: TaskState, serializedData: ByteBuffer) {
    var failedExecutor: Option[String] = None
    var reason: Option[ExecutorLossReason] = None
    synchronized {
      try {
        // 从taskIdToTaskSetManager中获取Task对应的TaskSetManager
        taskIdToTaskSetManager.get(tid) match {
          case Some(taskSet) =>
            // 如果要更新的任务状态是LOST
            if (state == TaskState.LOST) {
              // TaskState.LOST is only used by the deprecated Mesos fine-grained scheduling mode,
              // where each executor corresponds to a single task, so mark the executor as failed.
              // 那么从taskIdToExecutorId中获取Task对应的ExecutorId.
              val execId = taskIdToExecutorId.getOrElse(tid, throw new IllegalStateException(
                "taskIdToTaskSetManager.contains(tid) <=> taskIdToExecutorId.contains(tid)"))
              // 如果此Executor上正在运行Task，那么调用removeExecutor方法移除Executor，移除的原因是SlaveLost
              if (executorIdToRunningTaskIds.contains(execId)) {
                reason = Some(
                  SlaveLost(s"Task $tid was lost, so marking the executor as lost as well."))
                removeExecutor(execId, reason.get)
                failedExecutor = Some(execId)
              }
            }
            // 如果要更新的任务状态时完成状态（包括FINISHED, FAILED, KILLED, LOST）
            if (TaskState.isFinished(state)) {
              // 调用cleanupTaskState方法，清除此Task在taskIdToTaskSetManager、taskIdToExecutorId等缓存中的数据
              cleanupTaskState(tid)
              // 然后调用TaskSetManager的removeRunningTask方法减少正在运行的任务数量。
              taskSet.removeRunningTask(tid)

              if (state == TaskState.FINISHED) {
                // 如果任务状态时FINISHED，则调用taskResultGetter.enqueueSuccessfulTask方法,对执行成功的任务进行处理
                taskResultGetter.enqueueSuccessfulTask(taskSet, tid, serializedData)
              } else if (Set(TaskState.FAILED, TaskState.KILLED, TaskState.LOST).contains(state)) {
                // 如果任务是FAILED KILLED LOST中的一种，那么调用taskResultGetter.enqueueFailedTask方法，对执行失败的Task进行处理。
                taskResultGetter.enqueueFailedTask(taskSet, tid, state, serializedData)
              }
            }
          case None =>
            logError(
              ("Ignoring update with state %s for TID %s because its task set is gone (this is " +
                "likely the result of receiving duplicate task finished status updates) or its " +
                "executor has been marked as failed.")
                .format(state, tid))
        }
      } catch {
        case e: Exception => logError("Exception in statusUpdate", e)
      }
    }
    // Update the DAGScheduler without holding a lock on this, since that can deadlock
    // 如果failedExecutor设置了Executor的身份标识，说明此Executor已经被移除，那么此Executor上正在运行的Task需要得到妥善的处置。
    // 安置的办法是：首先调用DAGScheduler的executorLost方法，然后调用SchedulerBackend的reviveOffers方法给Task分配资源并运行Task。
    if (failedExecutor.isDefined) {
      assert(reason.isDefined)
      dagScheduler.executorLost(failedExecutor.get, reason.get)
      backend.reviveOffers()
    }
  }

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  override def executorHeartbeatReceived(
      execId: String,
      accumUpdates: Array[(Long, Seq[AccumulatorV2[_, _]])],
      blockManagerId: BlockManagerId): Boolean = {
    // (taskId, stageId, stageAttemptId, accumUpdates)
    val accumUpdatesWithTaskIds: Array[(Long, Int, Int, Seq[AccumulableInfo])] = synchronized {
      accumUpdates.flatMap { case (id, updates) =>
        val accInfos = updates.map(acc => acc.toInfo(Some(acc.value), None))
        taskIdToTaskSetManager.get(id).map { taskSetMgr =>
          (id, taskSetMgr.stageId, taskSetMgr.taskSet.stageAttemptId, accInfos)
        }
      }
    }
    dagScheduler.executorHeartbeatReceived(execId, accumUpdatesWithTaskIds, blockManagerId)
  }

  def handleTaskGettingResult(taskSetManager: TaskSetManager, tid: Long): Unit = synchronized {
    taskSetManager.handleTaskGettingResult(tid)
  }

  // 根据对TaskResultGetter的enqueueSuccessfulTask方法的介绍，我们知道在获取到Task的执行结果后，
  // 将调用TaskSchedulerImpl的handleSuccessfulTask方法。handleSuccessfulTask方法实际调用了TaskSetManager的
  // handleSuccessfulTask方法，这样就把Task执行结果的处理串联了起来。
  def handleSuccessfulTask(
      taskSetManager: TaskSetManager,
      tid: Long,
      taskResult: DirectTaskResult[_]): Unit = synchronized {
    taskSetManager.handleSuccessfulTask(tid, taskResult)
  }

  def handleFailedTask(
      taskSetManager: TaskSetManager,
      tid: Long,
      taskState: TaskState,
      reason: TaskFailedReason): Unit = synchronized {
    taskSetManager.handleFailedTask(tid, taskState, reason)
    if (!taskSetManager.isZombie && taskState != TaskState.KILLED) {
      // Need to revive offers again now that the task set manager state has been updated to
      // reflect failed tasks that need to be re-run.
      backend.reviveOffers()
    }
  }

  def error(message: String) {
    synchronized {
      if (taskSetsByStageIdAndAttempt.nonEmpty) {
        // Have each task set throw a SparkException with the error
        for {
          attempts <- taskSetsByStageIdAndAttempt.values
          manager <- attempts.values
        } {
          try {
            manager.abort(message)
          } catch {
            case e: Exception => logError("Exception in error callback", e)
          }
        }
      } else {
        // No task sets are active but we still got an error. Just exit since this
        // must mean the error is during registration.
        // It might be good to do something smarter here in the future.
        throw new SparkException(s"Exiting due to error from cluster scheduler: $message")
      }
    }
  }

  override def stop() {
    speculationScheduler.shutdown()
    if (backend != null) {
      backend.stop()
    }
    if (taskResultGetter != null) {
      taskResultGetter.stop()
    }
    starvationTimer.cancel()
  }

  override def defaultParallelism(): Int = backend.defaultParallelism()

  // Check for speculatable tasks in all our active jobs.
  // checkSpeculatableTasks方法实际依赖于rootPool的checkSpeculatableTasks方法。
  // 如果检测到有可以推断执行的任务，则调用SchedulerBackend的reviveOffers方法将向LocalEndpoint发送ReviveOffers消息，
  // LocalEndpoint接收到ReviveOffers消息后，将调用LocalEndpoint的reviveOffers方法分配资源并运行Task
  def checkSpeculatableTasks() {
    var shouldRevive = false
    synchronized {
      shouldRevive = rootPool.checkSpeculatableTasks(MIN_TIME_TO_SPECULATION)
    }
    if (shouldRevive) {
      backend.reviveOffers()
    }
  }

  // 此方法用于处理Executor丢失
  override def executorLost(executorId: String, reason: ExecutorLossReason): Unit = {
    var failedExecutor: Option[String] = None

    synchronized {
      // 如果executorIdToRunningTaskIds中包含指定的ExecutorId，则说明此时在此Executor上已经有Task正在运行，
      // 那么调用removeExecutor方法移除Executor,并将failedExecutor设置为此Executor.
      if (executorIdToRunningTaskIds.contains(executorId)) {
        val hostPort = executorIdToHost(executorId)
        logExecutorLoss(executorId, hostPort, reason)
        removeExecutor(executorId, reason)
        failedExecutor = Some(executorId)
      } else {
        // 如果executorIdToRunningTaskIds中不包含指定的ExecutorId，则说明此时在此Executor上没有Task正在运行，
        // 那么从executorIdToHost中获取此Exeuctor对应的Host，并调用removeExecutor方法移除Exuecutor
        executorIdToHost.get(executorId) match {
          case Some(hostPort) =>
            // If the host mapping still exists, it means we don't know the loss reason for the
            // executor. So call removeExecutor() to update tasks running on that executor when
            // the real loss reason is finally known.
            logExecutorLoss(executorId, hostPort, reason)
            removeExecutor(executorId, reason)

          case None =>
            // We may get multiple executorLost() calls with different loss reasons. For example,
            // one may be triggered by a dropped connection from the slave while another may be a
            // report of executor termination from Mesos. We produce log messages for both so we
            // eventually report the termination reason.
            logError(s"Lost an executor $executorId (already removed): $reason")
        }
      }
    }

    // Call dagScheduler.executorLost without holding the lock on this to prevent deadlock
    // 如果failedExecutor设置了ExecutorId，这说明此Executor已经移除，那么此Executor上正在运行的Task需要得到妥善安置。
    // 首先调用DAGScheduler的executorLost方法，然后调用SchedulerBackend的reviveOffers方法给Task分配资源并运行Task。
    if (failedExecutor.isDefined) {
      dagScheduler.executorLost(failedExecutor.get, reason)
      backend.reviveOffers()
    }
  }

  private def logExecutorLoss(
      executorId: String,
      hostPort: String,
      reason: ExecutorLossReason): Unit = reason match {
    case LossReasonPending =>
      logDebug(s"Executor $executorId on $hostPort lost, but reason not yet known.")
    case ExecutorKilled =>
      logInfo(s"Executor $executorId on $hostPort killed by driver.")
    case _ =>
      logError(s"Lost executor $executorId on $hostPort: $reason")
  }

  /**
   * Cleans up the TaskScheduler's state for tracking the given task.
   */
  private def cleanupTaskState(tid: Long): Unit = {
    taskIdToTaskSetManager.remove(tid)
    taskIdToExecutorId.remove(tid).foreach { executorId =>
      executorIdToRunningTaskIds.get(executorId).foreach { _.remove(tid) }
    }
  }

  /**
   * Remove an executor from all our data structures and mark it as lost. If the executor's loss
   * reason is not yet known, do not yet remove its association with its host nor update the status
   * of any running tasks, since the loss reason defines whether we'll fail those tasks.
   */
  // 移除Executor及其对应的Host和机架等缓存
  private def removeExecutor(executorId: String, reason: ExecutorLossReason) {
    // The tasks on the lost executor may not send any more status updates (because the executor
    // has been lost), so they should be cleaned up here.
    // 从executorIdToRunningTaskIds中移除Executor的缓存，并调用cleanupTaskState方法，清除在此Executor上正在运行的Task在
    // taskIdToTaskSetManager、taskIdToExecutorId等缓存中的数据
    executorIdToRunningTaskIds.remove(executorId).foreach { taskIds =>
      logDebug("Cleaning up TaskScheduler state for tasks " +
        s"${taskIds.mkString("[", ",", "]")} on failed executor $executorId")
      // We do not notify the TaskSetManager of the task failures because that will
      // happen below in the rootPool.executorLost() call.
      taskIds.foreach(cleanupTaskState)
    }

    // 从executorIdToHost中移除此Executor的信息
    val host = executorIdToHost(executorId)

    // 如果hostToExecutors中此Executor所在的Host主机已经没有任何Executor了，那么从hostsByRack中移除此Host的信息
    // 如果hostsByRack中此Executor所在的机架已经没有任何Host了，那么从hostsByRack中移除此机架的信息。
    val execs = hostToExecutors.getOrElse(host, new HashSet)
    execs -= executorId
    if (execs.isEmpty) {
      hostToExecutors -= host
      for (rack <- getRackForHost(host); hosts <- hostsByRack.get(rack)) {
        hosts -= host
        if (hosts.isEmpty) {
          hostsByRack -= rack
        }
      }
    }

    // 如果移除Executor的原因不是LossReasonPending，那么首先从executorIdToHost中移除此Executor的缓存，
    // 然后调用rootPool.executorLost方法，将在此Executor上正在运行的Task作为失败任务处理，最后重新提交这些任务
    if (reason != LossReasonPending) {
      executorIdToHost -= executorId
      rootPool.executorLost(executorId, host, reason)
    }
  }

  def executorAdded(execId: String, host: String) {
    dagScheduler.executorAdded(execId, host)
  }

  def getExecutorsAliveOnHost(host: String): Option[Set[String]] = synchronized {
    hostToExecutors.get(host).map(_.toSet)
  }

  def hasExecutorsAliveOnHost(host: String): Boolean = synchronized {
    hostToExecutors.contains(host)
  }

  def hasHostAliveOnRack(rack: String): Boolean = synchronized {
    hostsByRack.contains(rack)
  }

  def isExecutorAlive(execId: String): Boolean = synchronized {
    executorIdToRunningTaskIds.contains(execId)
  }

  def isExecutorBusy(execId: String): Boolean = synchronized {
    executorIdToRunningTaskIds.get(execId).exists(_.nonEmpty)
  }

  // By default, rack is unknown
  def getRackForHost(value: String): Option[String] = None

  private def waitBackendReady(): Unit = {
    if (backend.isReady) {
      return
    }
    while (!backend.isReady) {
      // Might take a while for backend to be ready if it is waiting on resources.
      if (sc.stopped.get) {
        // For example: the master removes the application for some reason
        throw new IllegalStateException("Spark context stopped while waiting for backend")
      }
      synchronized {
        this.wait(100)
      }
    }
  }

  override def applicationId(): String = backend.applicationId()

  override def applicationAttemptId(): Option[String] = backend.applicationAttemptId()

  private[scheduler] def taskSetManagerForAttempt(
      stageId: Int,
      stageAttemptId: Int): Option[TaskSetManager] = {
    for {
      attempts <- taskSetsByStageIdAndAttempt.get(stageId)
      manager <- attempts.get(stageAttemptId)
    } yield {
      manager
    }
  }

}


private[spark] object TaskSchedulerImpl {
  /**
   * Used to balance containers across hosts.
   *
   * Accepts a map of hosts to resource offers for that host, and returns a prioritized list of
   * resource offers representing the order in which the offers should be used.  The resource
   * offers are ordered such that we'll allocate one container on each host before allocating a
   * second container on any host, and so on, in order to reduce the damage if a host fails.
   *
   * For example, given <h1, [o1, o2, o3]>, <h2, [o4]>, <h1, [o5, o6]>, returns
   * [o1, o5, o4, 02, o6, o3]
   */
  def prioritizeContainers[K, T] (map: HashMap[K, ArrayBuffer[T]]): List[T] = {
    val _keyList = new ArrayBuffer[K](map.size)
    _keyList ++= map.keys

    // order keyList based on population of value in map
    val keyList = _keyList.sortWith(
      (left, right) => map(left).size > map(right).size
    )

    val retval = new ArrayBuffer[T](keyList.size * 2)
    var index = 0
    var found = true

    while (found) {
      found = false
      for (key <- keyList) {
        val containerList: ArrayBuffer[T] = map.getOrElse(key, null)
        assert(containerList != null)
        // Get the index'th entry for this host - if present
        if (index < containerList.size) {
          retval += containerList.apply(index)
          found = true
        }
      }
      index += 1
    }

    retval.toList
  }
}
