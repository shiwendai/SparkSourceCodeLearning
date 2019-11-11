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
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.collection.Map
import scala.collection.mutable.{HashMap, HashSet, Stack}
import scala.concurrent.duration._
import scala.language.existentials
import scala.language.postfixOps
import scala.util.control.NonFatal

import org.apache.commons.lang3.SerializationUtils

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.network.util.JavaUtils
import org.apache.spark.partial.{ApproximateActionListener, ApproximateEvaluator, PartialResult}
import org.apache.spark.rdd.RDD
import org.apache.spark.rpc.RpcTimeout
import org.apache.spark.storage._
import org.apache.spark.storage.BlockManagerMessages.BlockManagerHeartbeat
import org.apache.spark.util._

/**
 * The high-level scheduling layer that implements stage-oriented scheduling. It computes a DAG of
 * stages for each job, keeps track of which RDDs and stage outputs are materialized, and finds a
 * minimal schedule to run the job. It then submits stages as TaskSets to an underlying
 * TaskScheduler implementation that runs them on the cluster. A TaskSet contains fully independent
 * tasks that can run right away based on the data that's already on the cluster (e.g. map output
 * files from previous stages), though it may fail if this data becomes unavailable.
 *
 * Spark stages are created by breaking the RDD graph at shuffle boundaries. RDD operations with
 * "narrow" dependencies, like map() and filter(), are pipelined together into one set of tasks
 * in each stage, but operations with shuffle dependencies require multiple stages (one to write a
 * set of map output files, and another to read those files after a barrier). In the end, every
 * stage will have only shuffle dependencies on other stages, and may compute multiple operations
 * inside it. The actual pipelining of these operations happens in the RDD.compute() functions of
 * various RDDs (MappedRDD, FilteredRDD, etc).
 *
 * In addition to coming up with a DAG of stages, the DAGScheduler also determines the preferred
 * locations to run each task on, based on the current cache status, and passes these to the
 * low-level TaskScheduler. Furthermore, it handles failures due to shuffle output files being
 * lost, in which case old stages may need to be resubmitted. Failures *within* a stage that are
 * not caused by shuffle file loss are handled by the TaskScheduler, which will retry each task
 * a small number of times before cancelling the whole stage.
 *
 * When looking through this code, there are several key concepts:
 *
 *  - Jobs (represented by [[ActiveJob]]) are the top-level work items submitted to the scheduler.
 *    For example, when the user calls an action, like count(), a job will be submitted through
 *    submitJob. Each Job may require the execution of multiple stages to build intermediate data.
 *
 *  - Stages ([[Stage]]) are sets of tasks that compute intermediate results in jobs, where each
 *    task computes the same function on partitions of the same RDD. Stages are separated at shuffle
 *    boundaries, which introduce a barrier (where we must wait for the previous stage to finish to
 *    fetch outputs). There are two types of stages: [[ResultStage]], for the final stage that
 *    executes an action, and [[ShuffleMapStage]], which writes map output files for a shuffle.
 *    Stages are often shared across multiple jobs, if these jobs reuse the same RDDs.
 *
 *  - Tasks are individual units of work, each sent to one machine.
 *
 *  - Cache tracking: the DAGScheduler figures out which RDDs are cached to avoid recomputing them
 *    and likewise remembers which shuffle map stages have already produced output files to avoid
 *    redoing the map side of a shuffle.
 *
 *  - Preferred locations: the DAGScheduler also computes where to run each task in a stage based
 *    on the preferred locations of its underlying RDDs, or the location of cached or shuffle data.
 *
 *  - Cleanup: all data structures are cleared when the running jobs that depend on them finish,
 *    to prevent memory leaks in a long-running application.
 *
 * To recover from failures, the same stage might need to run multiple times, which are called
 * "attempts". If the TaskScheduler reports that a task failed because a map output file from a
 * previous stage was lost, the DAGScheduler resubmits that lost stage. This is detected through a
 * CompletionEvent with FetchFailed, or an ExecutorLost event. The DAGScheduler will wait a small
 * amount of time to see whether other nodes or tasks fail, then resubmit TaskSets for any lost
 * stage(s) that compute the missing tasks. As part of this process, we might also have to create
 * Stage objects for old (finished) stages where we previously cleaned up the Stage object. Since
 * tasks from the old attempt of a stage could still be running, care must be taken to map any
 * events received in the correct Stage object.
 *
 * Here's a checklist to use when making or reviewing changes to this class:
 *
 *  - All data structures should be cleared when the jobs involving them end to avoid indefinite
 *    accumulation of state in long-running programs.
 *
 *  - When adding a new data structure, update `DAGSchedulerSuite.assertDataStructuresEmpty` to
 *    include the new structure. This will help to catch memory leaks.
 */
// DAGScheduler主要用于在任务正式提交给TaskSchedulerImpl提交之前做一些准备工作，包括创建Job、将DAG中的RDD划分到不同的Stage、提交Stage等
// DAGScheduler实现了面向DAG的高层次调度
// DAGScheduler会将Job的RDD划分到不同的Stage，并构建这些Stage的依赖关系。这样可以使得没有依赖关系的Stage并行执行，并保证有依赖关系的Stage顺序执行
// 所有的组件都通过向DAGScheduler投递DAGSchedulerEvent来使用DAGScheduler.
// DAGScheduler内部的DAGSchedulerEventProcessLoop将处理这些DAGSchedulerEvent，并调用DAGScheduler的不同方法。
// JobListener用于对作业中每个Task执行成功或失败进行监听，JobWaiter实现了JobListener并最终确定作业的成功与失败。
private[spark]
class DAGScheduler(
    private[scheduler] val sc: SparkContext,
    private[scheduler] val taskScheduler: TaskScheduler,
    listenerBus: LiveListenerBus,
    mapOutputTracker: MapOutputTrackerMaster,
    blockManagerMaster: BlockManagerMaster,
    env: SparkEnv,
    clock: Clock = new SystemClock())
  extends Logging {

  def this(sc: SparkContext, taskScheduler: TaskScheduler) = {
    this(
      sc,
      taskScheduler,
      sc.listenerBus,
      sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster],
      sc.env.blockManager.master,
      sc.env)
  }

  def this(sc: SparkContext) = this(sc, sc.taskScheduler)

  private[spark] val metricsSource: DAGSchedulerSource = new DAGSchedulerSource(this)

  // 用于生成下一个Job的身份标识
  private[scheduler] val nextJobId = new AtomicInteger(0)
  // 总共提交的作业数量
  private[scheduler] def numTotalJobs: Int = nextJobId.get()
  // 用于生成下一个Stage的身份标识
  private val nextStageId = new AtomicInteger(0)

  // 用于缓存JobId与StageId之间的映射关系
  private[scheduler] val jobIdToStageIds = new HashMap[Int, HashSet[Int]]
  // 用于缓存StageId与Stage之间的映射关系
  private[scheduler] val stageIdToStage = new HashMap[Int, Stage]
  /**
   * Mapping from shuffle dependency ID to the ShuffleMapStage that will generate the data for
   * that dependency. Only includes stages that are part of currently running job (when the job(s)
   * that require the shuffle stage complete, the mapping will be removed, and the only record of
   * the shuffle data will be in the MapOutputTracker).
   */
    // 用于缓存Shuffle的身份标识(即shuffleId)与ShuffleMapStage之间的映射关系
  private[scheduler] val shuffleIdToMapStage = new HashMap[Int, ShuffleMapStage]
  // JobId与ActiveJob之间的映射关系
  private[scheduler] val jobIdToActiveJob = new HashMap[Int, ActiveJob]

  // 处于等待状态的Stage集合
  // Stages we need to run whose parents aren't done
  private[scheduler] val waitingStages = new HashSet[Stage]

  // 处于运行状态的Stage集合
  // Stages we are running right now
  private[scheduler] val runningStages = new HashSet[Stage]

  // 处于失败状态的Stage集合
  // Stages that must be resubmitted due to fetch failures
  private[scheduler] val failedStages = new HashSet[Stage]

  // 所有激活的Job集合
  private[scheduler] val activeJobs = new HashSet[ActiveJob]

  /**
   * Contains the locations that each RDD's partitions are cached on.  This map's keys are RDD ids
   * and its values are arrays indexed by partition numbers. Each array value is the set of
   * locations where that RDD partition is cached.
   *
   * All accesses to this map should be guarded by synchronizing on it (see SPARK-4454).
   */
  // 缓存每个RDD的所有分区的位置信息。cacheLocs的数据类似是HashMap[Int, IndexedSeq[Seq[TaskLocation]]]
  // 所以每个RDD的分区按照 分区号 作为索引存储到IndexedSeq[Seq[TaskLocation]]，由于RDD的每个分区作为一个Block
  // 以及存储体系的复制因素，因此RDD的每个分区的Block可能存在于多个节点的BlockManager上，RDD每个分区的位置信息为
  // TaskLocation的序列。
  // This map's keys are RDD ids and its values are arrays indexed by partition numbers.
  private val cacheLocs = new HashMap[Int, IndexedSeq[Seq[TaskLocation]]]

  // For tracking failed nodes, we use the MapOutputTracker's epoch number, which is sent with
  // every task. When we detect a node failing, we note the current epoch number and failed
  // executor, increment it for new tasks, and use this to ignore stray ShuffleMapTask results.
  //
  // TODO: Garbage collect information about failure epochs when we know there are no more
  //       stray messages to detect.
  // 当检查到一个节点出现故障时，会将执行失败的Executor和MapOutputTracker当前的纪元添加到failedEpoch忽略迷失的ShuffleMapTask的结果
  private val failedEpoch = new HashMap[String, Long]

  // SparkEnv的子组件OutputCommitCoordinator
  private [scheduler] val outputCommitCoordinator = env.outputCommitCoordinator

  // A closure serializer that we reuse.
  // This is only safe because DAGScheduler runs in a single thread.
  private val closureSerializer = SparkEnv.get.closureSerializer.newInstance()

  /** If enabled, FetchFailed will not cause stage retry, in order to surface the problem. */
  private val disallowStageRetryForTest = sc.getConf.getBoolean("spark.test.noStageRetry", false)

  // 只有一个线程的ScheduledThreadPoolExecutor，messageScheduler的职责是对失败的Stage进行重试
  private val messageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("dag-scheduler-message")

  // DAGSchedulerEventProcessLoop是DAGScheduler内部的事件循环处理器，用于处理DAGSchedulerEvent类型的事件。
  private[scheduler] val eventProcessLoop = new DAGSchedulerEventProcessLoop(this)
  taskScheduler.setDAGScheduler(this)

  /**
   * Called by the TaskSetManager to report task's starting.
   */
  def taskStarted(task: Task[_], taskInfo: TaskInfo) {
    eventProcessLoop.post(BeginEvent(task, taskInfo))
  }

  /**
   * Called by the TaskSetManager to report that a task has completed
   * and results are being fetched remotely.
   */
  def taskGettingResult(taskInfo: TaskInfo) {
    eventProcessLoop.post(GettingResultEvent(taskInfo))
  }

  /**
   * Called by the TaskSetManager to report task completions or failures.
   */
  // DAGScheduler的taskEnded方法用于对Task执行的结果进行处理。
  // 1.对于ShufleMapTask而言，需要将它的状态信息MapStatus追加到ShuffleMapStage的outputLocs缓存中；
  // 2.如果ShuffleMapStage的所有分区的ShuffleMapTask都执行成功了，那么将需要把ShuffleMapStage的outputLocs缓存中的所有MapStatus
  //   注册到MapOutputTrackerMaster的mapStatuses中，以便于下游Stage中的Task读取输入数据所在的位置信息；
  // 3.如果某个ShuffleMapTask执行失败了，则需要重新提交ShuffleMapStage；
  // 4.如果ShuffleMapStage的所有ShuffleMapTask都执行成功了，还需要唤醒下游Stage的执行。
  // 5.对于ResultTask而言，如果ResultMapTask都执行成功了，则将ResultStage标记为成功，并通知JobWaiter对各个ResultTask的执行结果进行收集，
  //   然后根据应用程序的需要进行最终的处理(如打印到控制台、输出到HDFS)
  def taskEnded(
      task: Task[_],
      reason: TaskEndReason,
      result: Any,
      accumUpdates: Seq[AccumulatorV2[_, _]],
      taskInfo: TaskInfo): Unit = {
    // 向DAGSchedulerEventProcessLoop投递CompletionEvent事件
    eventProcessLoop.post(
      CompletionEvent(task, reason, result, accumUpdates, taskInfo))
  }

  /**
   * Update metrics for in-progress tasks and let the master know that the BlockManager is still
   * alive. Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  def executorHeartbeatReceived(
      execId: String,
      // (taskId, stageId, stageAttemptId, accumUpdates)
      accumUpdates: Array[(Long, Int, Int, Seq[AccumulableInfo])],
      blockManagerId: BlockManagerId): Boolean = {
    listenerBus.post(SparkListenerExecutorMetricsUpdate(execId, accumUpdates))
    blockManagerMaster.driverEndpoint.askWithRetry[Boolean](
      BlockManagerHeartbeat(blockManagerId), new RpcTimeout(600 seconds, "BlockManagerHeartbeat"))
  }

  /**
   * Called by TaskScheduler implementation when an executor fails.
   */
  def executorLost(execId: String, reason: ExecutorLossReason): Unit = {
    eventProcessLoop.post(ExecutorLost(execId, reason))
  }

  /**
   * Called by TaskScheduler implementation when a host is added.
   */
  // 用于向DAGScheduler的DAGSchedulerEventProcessLoop投递ExecutorAdded事件
  // 我们知道DAGSchedulerEventProcessLoop在接收到ExecutorAdded事件后将调用DAGScheduler的handleExecutorAdded方法
  def executorAdded(execId: String, host: String): Unit = {
    eventProcessLoop.post(ExecutorAdded(execId, host))
  }

  /**
   * Called by the TaskSetManager to cancel an entire TaskSet due to either repeated failures or
   * cancellation of the job itself.
   */
  def taskSetFailed(taskSet: TaskSet, reason: String, exception: Option[Throwable]): Unit = {
    eventProcessLoop.post(TaskSetFailed(taskSet, reason, exception))
  }

  // getCacheLocs方法用于获取RDD各个分区的TaskLocation序列
  private[scheduler]
  def getCacheLocs(rdd: RDD[_]): IndexedSeq[Seq[TaskLocation]] = cacheLocs.synchronized {
    // Note: this doesn't use `getOrElse()` because this method is called O(num tasks) times
    if (!cacheLocs.contains(rdd.id)) {
      // 如果cacheLocs中不包含RDD对应的 IndexedSeq[Seq[TaskLocation]]
      // Note: if the storage level is NONE, we don't need to get locations from block manager.

      val locs: IndexedSeq[Seq[TaskLocation]] = if (rdd.getStorageLevel == StorageLevel.NONE) {
        // 如果RDD的存储级别为NONE,那么构造一个空的IndexedSeq[Seq[TaskLocation]]返回
        IndexedSeq.fill(rdd.partitions.length)(Nil)
      } else {
        // 如果RDD的存储级别不为NONE,则首先构造RDD各个分区的RDDBlockId数组
        val blockIds =
          rdd.partitions.indices.map(index => RDDBlockId(rdd.id, index)).toArray[BlockId]

        // 然后调用BlockManagerMaster的getLocations方法获取数组中每个RDDBlockId所存储的位置信息
        // （即BlockManager的身份标识BlockManagerId）序列，并封装为TaskLocation
        blockManagerMaster.getLocations(blockIds).map { bms =>
          bms.map(bm => TaskLocation(bm.host, bm.executorId))
        }
      }
      // 将RDD的id与上面得到的IndexedSeq[Seq[TaskLocation]]之间的映射关系放入cacheLocs
      cacheLocs(rdd.id) = locs
    }
    // 返回cacheLocs中缓存的RDD对应的IndexedSeq[Seq[TaskLocation]]
    cacheLocs(rdd.id)
  }

  // 用于清空cacheLocs中缓存的各个RDD的所有分区的位置
  private def clearCacheLocs(): Unit = cacheLocs.synchronized {
    cacheLocs.clear()
  }

  /**
   * Gets a shuffle map stage if one exists in shuffleIdToMapStage. Otherwise, if the
   * shuffle map stage doesn't already exist, this method will create the shuffle map stage in
   * addition to any missing ancestor shuffle map stages.
   */
  // 方法为每一个ShuffleDependency获取或者创建对应的ShuffleMapStage
  private def getOrCreateShuffleMapStage(
      shuffleDep: ShuffleDependency[_, _, _],
      firstJobId: Int): ShuffleMapStage = {

    shuffleIdToMapStage.get(shuffleDep.shuffleId) match {
      // 如果已经创建了ShuffleDependency对应的ShuffleMapStage，则直接返回此ShuffleMapStage
      case Some(stage) =>
        stage
      case None =>
        // Create stages for all missing ancestor shuffle dependencies.
        // 否则调用getMissingAncestorShuffleDependencies方法找到所有还未创建过ShuffleMapStage的祖先ShuffleDependency,
        getMissingAncestorShuffleDependencies(shuffleDep.rdd).foreach { dep =>
          // Even though getMissingAncestorShuffleDependencies only returns shuffle dependencies
          // that were not already in shuffleIdToMapStage, it's possible that by the time we
          // get to a particular dependency in the foreach loop, it's been added to
          // shuffleIdToMapStage by the stage creation process for an earlier dependency. See
          // SPARK-13902 for more information.
          // 并调用createShuffleMapStage方法创建ShuffleMapStage并注册
          if (!shuffleIdToMapStage.contains(dep.shuffleId)) {
            createShuffleMapStage(dep, firstJobId)
          }
        }
        // 最后还会为当前ShuffleDependency调用方法createShuffleMapStage创建ShuffleMapStage并注册
        // Finally, create a stage for the given shuffle dependency.
        createShuffleMapStage(shuffleDep, firstJobId)
    }
  }

  /**
   * Creates a ShuffleMapStage that generates the given shuffle dependency's partitions. If a
   * previously run stage generated the same shuffle data, this function will copy the output
   * locations that are still available from the previous shuffle to avoid unnecessarily
   * regenerating data.
   */
  // 小贴士：因为Stage可以重试，所以当前Stage可能在之前已经执行过。在上一次执行过程中，部分map任务可能执行成功，
  // MapOutputTrackerMaster中将缓存这些成功的map任务的MapStatus，因而当前Stage只需要从MapOutputTrackerMaster
  // 中复制这些MapStatus即可，从而避免重新计算生成这些数据。
  // 创建ShuffleMapStage
  def createShuffleMapStage(shuffleDep: ShuffleDependency[_, _, _], jobId: Int): ShuffleMapStage = {

    // 获取ShuffleDependency的rdd属性，作为将要创建的ShuffleMapStage的rdd
    val rdd = shuffleDep.rdd
    // 获取rdd分区数长度，此长度即为要创建的ShuffleMapStage的numTasks(Task数量)。这说明map任务数量与RDD的各个分区一一对应
    val numTasks = rdd.partitions.length
    // 调动getOrCreateParentStages方法获得要创建ShuffleMapStage的所有父Stage（即parents）
    val parents = getOrCreateParentStages(rdd, jobId)
    // 生成将要创建的ShuffleMapStage的身份标识
    val id = nextStageId.getAndIncrement()
    // 创建ShuffleMapStage
    val stage = new ShuffleMapStage(id, rdd, numTasks, parents, jobId, rdd.creationSite, shuffleDep)

    // 更新刚创建的ShuffleMapStage的映射关系
    stageIdToStage(id) = stage
    shuffleIdToMapStage(shuffleDep.shuffleId) = stage
    updateJobIdStageIdMaps(jobId, stage)

    // 调用MapOutputTrackerMaster的containsShuffle方法查看是否已经存在shuffleId对应的MapStatus.
    if (mapOutputTracker.containsShuffle(shuffleDep.shuffleId)) {
      // A previously run stage generated partitions for this shuffle, so for each output
      // that's still available, copy information about that output location to the new stage
      // (so we don't unnecessarily re-compute that data).
      // 如果MapOutputTrackerMaster中缓存了shuffleId对应的MapStatus,将执行以下操作

      // 调用MapOutputTrackerMaster的getSerializedMapOutputStatuses从MapOutputTrackerMaster
      // 获取对MapStatus序列化后的字节数组
      val serLocs = mapOutputTracker.getSerializedMapOutputStatuses(shuffleDep.shuffleId)
      // 调用MapOutputTrackerMaster的deserializeMapStatuses方法对上一步获得的字节数组进行反序列化，得到MapStattus数组
      val locs = MapOutputTracker.deserializeMapStatuses(serLocs)

      // 调用ShuffleMapStage的addOutputLoc方法更新ShuffleMapStage的outputLocs
      (0 until locs.length).foreach { i =>
        if (locs(i) ne null) {
          // locs(i) will be null if missing
          stage.addOutputLoc(i, locs(i))
        }
      }
    } else {
      // Kind of ugly: need to register RDDs with the cache and map output tracker here
      // since we can't do it in the RDD constructor because # of partitions is unknown

      // 如果MapOutputTrackerMaster中未缓存shuffleId对应的MapStatus,那么调用MapOutputTrackerMaster的
      // registerShuffle方法注册shuffleId与对应的MapStatus的映射关系。
      logInfo("Registering RDD " + rdd.id + " (" + rdd.getCreationSite + ")")
      mapOutputTracker.registerShuffle(shuffleDep.shuffleId, rdd.partitions.length)
    }
    stage
  }

  /**
   * Create a ResultStage associated with the provided jobId.
   */
  private def createResultStage(
      rdd: RDD[_],
      func: (TaskContext, Iterator[_]) => _,
      partitions: Array[Int],
      jobId: Int,
      callSite: CallSite): ResultStage = {
    // 调用getOrCreateParentStages获取所有父Stage的列表，父Stage主要以宽依赖(ShuffleDependency)对应的Stage，
    // 此列表内的Stage包含以下几种：
    // ①当前RDD的直接或间接的依赖是ShuffleDependency且已经注册过的Stage。
    // ②当前RDD的直接或间接的依赖是ShuffleDependency且没有注册过的Stage的。对于这种ShuffleDependency，
    // 则根据ShuffleDependency中的RDD，找到它的直接或间接的依赖是ShuffleDependency且没有注册过Stage的所有ShuffleDependency,
    // 为他们创建并注册Stage。
    // ③当前RDD的直接或间接的依赖是ShuffleDependency且没有注册过的Stage的。为此ShuffleDependency创建并注册Stage。
    val parents = getOrCreateParentStages(rdd, jobId)
    // 生成Stage的身份标
    val id = nextStageId.getAndIncrement()
    // 创建ResultStage
    val stage = new ResultStage(id, rdd, func, partitions, parents, jobId, callSite)
    // 将ResultStage注册到stageIdToStage中
    stageIdToStage(id) = stage
    // 调用updateJobIdStageIdMaps方法更新JobId与ResultStage及其所有祖先的映射关系
    updateJobIdStageIdMaps(jobId, stage)
    stage
  }

  /**
   * Get or create the list of parent stages for a given RDD.  The new Stages will be created with
   * the provided firstJobId.
   */
  // 此方法用于获取或者创建给定RDD的所有父Stage，这些Stage将被分配给jobId对应的Job
  private def getOrCreateParentStages(rdd: RDD[_], firstJobId: Int): List[Stage] = {
    // 调用DAGScheduler的getShuffleDependencies方法获取RDD的所有ShuffleDependency的序列，
    // 逐个访问每个RDD及其依赖的非Shuffle的RDD，获取所有非Shuffle的RDD的ShuffleDependency依赖
    getShuffleDependencies(rdd).map { shuffleDep =>
      // 调用DAGScheduler的geOrCreateShuffleMapStage方法为每一个ShuffleDependency获取或者创建对应的ShuffleMapStage,
      // 并返回的到ShuffleMapStage列表
      getOrCreateShuffleMapStage(shuffleDep, firstJobId)
    }.toList
  }

  /** Find ancestor shuffle dependencies that are not registered in shuffleToMapStage yet */
  // 找到所有还未创建过ShuffleMapStage的祖先ShuffleDependency,
  private def getMissingAncestorShuffleDependencies(
      rdd: RDD[_]): Stack[ShuffleDependency[_, _, _]] = {

    val ancestors = new Stack[ShuffleDependency[_, _, _]]
    val visited = new HashSet[RDD[_]]

    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new Stack[RDD[_]]
    waitingForVisit.push(rdd)

    while (waitingForVisit.nonEmpty) {
      val toVisit = waitingForVisit.pop()
      if (!visited(toVisit)) {
        visited += toVisit
        getShuffleDependencies(toVisit).foreach { shuffleDep =>
          if (!shuffleIdToMapStage.contains(shuffleDep.shuffleId)) {
            ancestors.push(shuffleDep)
            waitingForVisit.push(shuffleDep.rdd)
          } // Otherwise, the dependency and its ancestors have already been registered.
        }
      }
    }
    ancestors
  }

  /**
   * Returns shuffle dependencies that are immediate parents of the given RDD.
   * 返回作为给定RDD的直接父项的shuffle依赖项。
   * This function will not return more distant ancestors.  For example, if C has a shuffle
   * dependency on B which has a shuffle dependency on A:
   *此功能不会返回更远的祖先。例如，如果C对B有一个shuffle依赖，它对A有一个shuffle依赖：
   * A <-- B <-- C
   *
   * calling this function with rdd C will only return the B <-- C dependency.
   *
   * This function is scheduler-visible for the purpose of unit testing.
   */
  // 找到RDD的所有直接或间接的ShuffleDependency的序列
  private[scheduler] def getShuffleDependencies(
      rdd: RDD[_]): HashSet[ShuffleDependency[_, _, _]] = {

    val parents = new HashSet[ShuffleDependency[_, _, _]]
    val visited = new HashSet[RDD[_]]
    val waitingForVisit = new Stack[RDD[_]]

    waitingForVisit.push(rdd)
    while (waitingForVisit.nonEmpty) {
      val toVisit = waitingForVisit.pop()
      if (!visited(toVisit)) {
        visited += toVisit
        toVisit.dependencies.foreach {
          case shuffleDep: ShuffleDependency[_, _, _] =>
            parents += shuffleDep
          case dependency =>
            waitingForVisit.push(dependency.rdd)
        }
      }
    }
    parents
  }

  // 判断Stage的未提交父Stage的条件如下：
  // 1. Stage 的RDD的分区中存在没有对应TaskLocation序列的分区，即调用getCacheLocs方法获取不到某个分区的TaskLocation序列，
  //    则说明当前Stage的某个上游ShuffleMapStage的某个分区任务未执行。
  // 2. Stage 的上游ShuffleMapStage不可用（即调用ShuffleMapStage的isAvailable方法返回false）
  private def getMissingParentStages(stage: Stage): List[Stage] = {
    val missing = new HashSet[Stage]
    val visited = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    // 我们在这里手动维护一个堆栈，以防止递归访问引起的StackOverflowError
    val waitingForVisit = new Stack[RDD[_]]

    def visit(rdd: RDD[_]) {
      if (!visited(rdd)) {
        visited += rdd
        val rddHasUncachedPartitions = getCacheLocs(rdd).contains(Nil)
        if (rddHasUncachedPartitions) {
          for (dep <- rdd.dependencies) {
            dep match {
              case shufDep: ShuffleDependency[_, _, _] =>
                val mapStage = getOrCreateShuffleMapStage(shufDep, stage.firstJobId)
                if (!mapStage.isAvailable) {
                  missing += mapStage
                }
              case narrowDep: NarrowDependency[_] =>
                waitingForVisit.push(narrowDep.rdd)
            }
          }
        }
      }
    }

    waitingForVisit.push(stage.rdd)

    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }

    missing.toList
  }

  /**
   * Registers the given jobId among the jobs that need the given stage and
   * all of that stage's ancestors.
   */
  // updateJobIdStageIdMaps方法对Stage及Stage的所有祖先Stage进行如下处理
  // 用来更新JobId与Stage及其所有祖先的映射关系
  private def updateJobIdStageIdMaps(jobId: Int, stage: Stage): Unit = {
    @tailrec
    def updateJobIdStageIdMapsList(stages: List[Stage]) {
      if (stages.nonEmpty) {
        val s = stages.head
        // 将jobId添加到每个Stage的jobIds
        s.jobIds += jobId
        // 将jobId和与每个Stage的id之间的映射关系更新到jobidToStageIds
        jobIdToStageIds.getOrElseUpdate(jobId, new HashSet[Int]()) += s.id

        val parentsWithoutThisJobId = s.parents.filter { ! _.jobIds.contains(jobId) }
        updateJobIdStageIdMapsList(parentsWithoutThisJobId ++ stages.tail)
      }
    }
    updateJobIdStageIdMapsList(List(stage))
  }

  /**
   * Removes state for job and any stages that are not needed by any other job.  Does not
   * handle cancelling tasks or notifying the SparkListener about finished jobs/stages/tasks.
   *
   * @param job The job whose state to cleanup.
   */
  private def cleanupStateForJobAndIndependentStages(job: ActiveJob): Unit = {
    val registeredStages = jobIdToStageIds.get(job.jobId)
    if (registeredStages.isEmpty || registeredStages.get.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    } else {
      stageIdToStage.filterKeys(stageId => registeredStages.get.contains(stageId)).foreach {
        case (stageId, stage) =>
          val jobSet = stage.jobIds
          if (!jobSet.contains(job.jobId)) {
            logError(
              "Job %d not registered for stage %d even though that stage was registered for the job"
              .format(job.jobId, stageId))
          } else {
            def removeStage(stageId: Int) {
              // data structures based on Stage
              for (stage <- stageIdToStage.get(stageId)) {
                if (runningStages.contains(stage)) {
                  logDebug("Removing running stage %d".format(stageId))
                  runningStages -= stage
                }
                for ((k, v) <- shuffleIdToMapStage.find(_._2 == stage)) {
                  shuffleIdToMapStage.remove(k)
                }
                if (waitingStages.contains(stage)) {
                  logDebug("Removing stage %d from waiting set.".format(stageId))
                  waitingStages -= stage
                }
                if (failedStages.contains(stage)) {
                  logDebug("Removing stage %d from failed set.".format(stageId))
                  failedStages -= stage
                }
              }
              // data structures based on StageId
              stageIdToStage -= stageId
              logDebug("After removal of stage %d, remaining stages = %d"
                .format(stageId, stageIdToStage.size))
            }

            jobSet -= job.jobId
            if (jobSet.isEmpty) { // no other job needs this stage
              removeStage(stageId)
            }
          }
      }
    }
    jobIdToStageIds -= job.jobId
    jobIdToActiveJob -= job.jobId
    activeJobs -= job
    job.finalStage match {
      case r: ResultStage => r.removeActiveJob()
      case m: ShuffleMapStage => m.removeActiveJob(job)
    }
  }

  /**
   * Submit an action job to the scheduler.
   *
   * @param rdd target RDD to run tasks on
   * @param func a function to run on each partition of the RDD
   * @param partitions set of partitions to run on; some jobs may not want to compute on all
   *   partitions of the target RDD, e.g. for operations like first()
   * @param callSite where in the user program this job was called
   * @param resultHandler callback to pass each result to
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   *
   * @return a JobWaiter object that can be used to block until the job finishes executing
   *         or can be used to cancel the job.
   *
   * @throws IllegalArgumentException when partitions ids are illegal
   */
  def submitJob[T, U](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      resultHandler: (Int, U) => Unit,
      properties: Properties): JobWaiter[U] = {
    // Check to make sure we are not launching a task on a partition that does not exist.
    // 检查确保分区在指定范围内
    val maxPartitions = rdd.partitions.length
    partitions.find(p => p >= maxPartitions || p < 0).foreach { p =>
      throw new IllegalArgumentException(
        "Attempting to access a non-existent partition: " + p + ". " +
          "Total number of partitions: " + maxPartitions)
    }

    // 生成下一个Job的jobId
    val jobId = nextJobId.getAndIncrement()
    if (partitions.size == 0) {
      // 如果Job的分区数量等于0，则创建一个totalTasks属性为0的JobWaiter并返回。
      // 根据JobWaiter的实现，JobWaiter的jobPromise将被设置为Success
      // Return immediately if the job is running 0 tasks
      return new JobWaiter[U](this, jobId, 0, resultHandler)
    }

    // 如果Job的分区数量大于0，则创建真正等待Job执行完成的JobWaiter
    assert(partitions.size > 0)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    val waiter = new JobWaiter(this, jobId, partitions.size, resultHandler)

    // 向eventProcessActor（即DAGSchedulerEventProcessLoop）发送JobSubmitted事件。
    eventProcessLoop.post(JobSubmitted(
      jobId, rdd, func2, partitions.toArray, callSite, waiter,
      SerializationUtils.clone(properties)))

    // 返回JobWaiter
    waiter
  }

  /**
   * Run an action job on the given RDD and pass all the results to the resultHandler function as
   * they arrive.
   *
   * @param rdd target RDD to run tasks on
   * @param func a function to run on each partition of the RDD
   * @param partitions set of partitions to run on; some jobs may not want to compute on all
   *   partitions of the target RDD, e.g. for operations like first()
   * @param callSite where in the user program this job was called
   * @param resultHandler callback to pass each result to
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   *
   * @throws Exception when the job fails
   */
  // 用户提交的Job首先会被转换为一系列RDD，然后才交给DAGScheduler进行处理.runJob方法是这一过程的入口
  def runJob[T, U](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      partitions: Seq[Int],
      callSite: CallSite,
      resultHandler: (Int, U) => Unit,
      properties: Properties): Unit = {
    // 生成运行Job的启动时间
    val start = System.nanoTime
    // 调用submitJob方法提交Job。由于执行Job的过程是异步的，因此submitJob方法将立即返回JobWaiter对象
    val waiter = submitJob(rdd, func, partitions, callSite, resultHandler, properties)
    // Note: Do not call Await.ready(future) because that calls `scala.concurrent.blocking`,
    // which causes concurrent SQL executions to fail if a fork-join pool is used. Note that
    // due to idiosyncrasies in Scala, `awaitPermission` is not actually used anywhere so it's
    // safe to pass in null here. For more detail, see SPARK-13747.
    // 利用JobWaiter等待Job处理完毕。如果Job执行成功，根据处理结果打印相应的日志。
    // 如果Job执行失败，除打印日志外，还将抛出引起Job失败的异常信息
    val awaitPermission = null.asInstanceOf[scala.concurrent.CanAwait]
    waiter.completionFuture.ready(Duration.Inf)(awaitPermission)
    waiter.completionFuture.value.get match {
      case scala.util.Success(_) =>
        logInfo("Job %d finished: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
      case scala.util.Failure(exception) =>
        logInfo("Job %d failed: %s, took %f s".format
          (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
        // SPARK-8644: Include user stack trace in exceptions coming from DAGScheduler.
        val callerStackTrace = Thread.currentThread().getStackTrace.tail
        exception.setStackTrace(exception.getStackTrace ++ callerStackTrace)
        throw exception
    }
  }

  /**
   * Run an approximate job on the given RDD and pass all the results to an ApproximateEvaluator
   * as they arrive. Returns a partial result object from the evaluator.
   *
   * @param rdd target RDD to run tasks on
   * @param func a function to run on each partition of the RDD
   * @param evaluator [[ApproximateEvaluator]] to receive the partial results
   * @param callSite where in the user program this job was called
   * @param timeout maximum time to wait for the job, in milliseconds
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   */
  def runApproximateJob[T, U, R](
      rdd: RDD[T],
      func: (TaskContext, Iterator[T]) => U,
      evaluator: ApproximateEvaluator[U, R],
      callSite: CallSite,
      timeout: Long,
      properties: Properties): PartialResult[R] = {
    val listener = new ApproximateActionListener(rdd, func, evaluator, timeout)
    val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
    val partitions = (0 until rdd.partitions.length).toArray
    val jobId = nextJobId.getAndIncrement()
    eventProcessLoop.post(JobSubmitted(
      jobId, rdd, func2, partitions, callSite, listener, SerializationUtils.clone(properties)))
    listener.awaitResult()    // Will throw an exception if the job fails
  }

  /**
   * Submit a shuffle map stage to run independently and get a JobWaiter object back. The waiter
   * can be used to block until the job finishes executing or can be used to cancel the job.
   * This method is used for adaptive query planning, to run map stages and look at statistics
   * about their outputs before submitting downstream stages.
   *
   * @param dependency the ShuffleDependency to run a map stage for
   * @param callback function called with the result of the job, which in this case will be a
   *   single MapOutputStatistics object showing how much data was produced for each partition
   * @param callSite where in the user program this job was submitted
   * @param properties scheduler properties to attach to this job, e.g. fair scheduler pool name
   */
  def submitMapStage[K, V, C](
      dependency: ShuffleDependency[K, V, C],
      callback: MapOutputStatistics => Unit,
      callSite: CallSite,
      properties: Properties): JobWaiter[MapOutputStatistics] = {

    val rdd = dependency.rdd
    val jobId = nextJobId.getAndIncrement()
    if (rdd.partitions.length == 0) {
      throw new SparkException("Can't run submitMapStage on RDD with 0 partitions")
    }

    // We create a JobWaiter with only one "task", which will be marked as complete when the whole
    // map stage has completed, and will be passed the MapOutputStatistics for that stage.
    // This makes it easier to avoid race conditions between the user code and the map output
    // tracker that might result if we told the user the stage had finished, but then they queries
    // the map output tracker and some node failures had caused the output statistics to be lost.
    val waiter = new JobWaiter(this, jobId, 1, (i: Int, r: MapOutputStatistics) => callback(r))
    eventProcessLoop.post(MapStageSubmitted(
      jobId, dependency, callSite, waiter, SerializationUtils.clone(properties)))
    waiter
  }

  /**
   * Cancel a job that is running or waiting in the queue.
   */
  def cancelJob(jobId: Int): Unit = {
    logInfo("Asked to cancel job " + jobId)
    eventProcessLoop.post(JobCancelled(jobId))
  }

  /**
   * Cancel all jobs in the given job group ID.
   */
  def cancelJobGroup(groupId: String): Unit = {
    logInfo("Asked to cancel job group " + groupId)
    eventProcessLoop.post(JobGroupCancelled(groupId))
  }

  /**
   * Cancel all jobs that are running or waiting in the queue.
   */
  def cancelAllJobs(): Unit = {
    eventProcessLoop.post(AllJobsCancelled)
  }

  private[scheduler] def doCancelAllJobs() {
    // Cancel all running jobs.
    runningStages.map(_.firstJobId).foreach(handleJobCancellation(_,
      reason = "as part of cancellation of all jobs"))
    activeJobs.clear() // These should already be empty by this point,
    jobIdToActiveJob.clear() // but just in case we lost track of some jobs...
  }

  /**
   * Cancel all jobs associated with a running or scheduled stage.
   */
  def cancelStage(stageId: Int) {
    eventProcessLoop.post(StageCancelled(stageId))
  }

  /**
   * Resubmit any failed stages. Ordinarily called after a small amount of time has passed since
   * the last fetch failure.
   */
  private[scheduler] def resubmitFailedStages() {
    if (failedStages.size > 0) {
      // Failed stages may be removed by job cancellation, so failed might be empty even if
      // the ResubmitFailedStages event has been scheduled.
      logInfo("Resubmitting failed stages")
      clearCacheLocs()
      val failedStagesCopy = failedStages.toArray
      failedStages.clear()
      for (stage <- failedStagesCopy.sortBy(_.firstJobId)) {
        submitStage(stage)
      }
    }
  }

  /**
   * Check for waiting stages which are now eligible for resubmission.
   * Submits stages that depend on the given parent stage. Called when the parent stage completes
   * successfully.
   */
  // 提交Stage的所有处于等待中的子Stage
  private def submitWaitingChildStages(parent: Stage) {
    logTrace(s"Checking if any dependencies of $parent are now runnable")
    logTrace("running: " + runningStages)
    logTrace("waiting: " + waitingStages)
    logTrace("failed: " + failedStages)
    val childStages = waitingStages.filter(_.parents.contains(parent)).toArray
    waitingStages --= childStages
    for (stage <- childStages.sortBy(_.firstJobId)) {
      submitStage(stage)
    }
  }

  /** Finds the earliest-created active job that needs the stage */
  // TODO: Probably should actually find among the active jobs that need this
  // stage the one with the highest priority (highest-priority pool, earliest created).
  // That should take care of at least part of the priority inversion problem with
  // cross-job dependencies.
  // 用于找到Stage的所有已经激活的JobId
  private def activeJobForStage(stage: Stage): Option[Int] = {
    // 获取stage中所有的JobIds并排序
    val jobsThatUseStage: Array[Int] = stage.jobIds.toArray.sorted
    // 从jobIdToActiveJob中查看该JobId是否为ActiveJob
    jobsThatUseStage.find(jobIdToActiveJob.contains)
  }

  private[scheduler] def handleJobGroupCancelled(groupId: String) {
    // Cancel all jobs belonging to this job group.
    // First finds all active jobs with this group id, and then kill stages for them.
    val activeInGroup = activeJobs.filter { activeJob =>
      Option(activeJob.properties).exists {
        _.getProperty(SparkContext.SPARK_JOB_GROUP_ID) == groupId
      }
    }
    val jobIds = activeInGroup.map(_.jobId)
    jobIds.foreach(handleJobCancellation(_, "part of cancelled job group %s".format(groupId)))
  }

  private[scheduler] def handleBeginEvent(task: Task[_], taskInfo: TaskInfo) {
    // Note that there is a chance that this task is launched after the stage is cancelled.
    // In that case, we wouldn't have the stage anymore in stageIdToStage.
    val stageAttemptId = stageIdToStage.get(task.stageId).map(_.latestInfo.attemptId).getOrElse(-1)
    listenerBus.post(SparkListenerTaskStart(task.stageId, stageAttemptId, taskInfo))
  }

  private[scheduler] def handleTaskSetFailed(
      taskSet: TaskSet,
      reason: String,
      exception: Option[Throwable]): Unit = {
    stageIdToStage.get(taskSet.stageId).foreach { abortStage(_, reason, exception) }
  }

  private[scheduler] def cleanUpAfterSchedulerStop() {
    for (job <- activeJobs) {
      val error =
        new SparkException(s"Job ${job.jobId} cancelled because SparkContext was shut down")
      job.listener.jobFailed(error)
      // Tell the listeners that all of the running stages have ended.  Don't bother
      // cancelling the stages because if the DAG scheduler is stopped, the entire application
      // is in the process of getting stopped.
      val stageFailedMessage = "Stage cancelled because SparkContext was shut down"
      // The `toArray` here is necessary so that we don't iterate over `runningStages` while
      // mutating it.
      runningStages.toArray.foreach { stage =>
        markStageAsFinished(stage, Some(stageFailedMessage))
      }
      listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobFailed(error)))
    }
  }

  private[scheduler] def handleGetTaskResult(taskInfo: TaskInfo) {
    listenerBus.post(SparkListenerTaskGettingResult(taskInfo))
  }

  // 处理提交的Job任务
  private[scheduler] def handleJobSubmitted(jobId: Int,
      finalRDD: RDD[_],
      func: (TaskContext, Iterator[_]) => _,
      partitions: Array[Int],
      callSite: CallSite,
      listener: JobListener,
      properties: Properties) {
    var finalStage: ResultStage = null
    try {
      // New stage creation may throw an exception if, for example, jobs are run on a
      // HadoopRDD whose underlying HDFS files have been deleted.
      // 调用createResultStage方法创建ResultStage.创建Stage的过程可能发生异常（比如运行在HadoopRDD上的任务所依赖的底层HDFS文件被删除），
      // 当异常发生时需要主动调用JobWaiter的jobFailed方法。createResultStage方法在创建ResultStage的过程中会引起创建一系列Stage的连锁反应，
      // Job与这些Stage的关系将被放入jobIdToStageIds中。
      finalStage = createResultStage(finalRDD, func, partitions, jobId, callSite)
    } catch {
      case e: Exception =>
        logWarning("Creating new stage failed due to exception - job: " + jobId, e)
        listener.jobFailed(e)
        return
    }

    // 创建ActiveJob
    val job = new ActiveJob(jobId, finalStage, callSite, listener, properties)
    // 调用clearCacheLocs方法清空cacheLocs
    clearCacheLocs()
    logInfo("Got job %s (%s) with %d output partitions".format(
      job.jobId, callSite.shortForm, partitions.length))
    logInfo("Final stage: " + finalStage + " (" + finalStage.name + ")")
    logInfo("Parents of final stage: " + finalStage.parents)
    logInfo("Missing parents: " + getMissingParentStages(finalStage))

    // 生成Job提交时间
    val jobSubmissionTime = clock.getTimeMillis()

    // 将jobId与刚创建的ActiveJob之间的对应关系放入jobIdActiveJob中
    jobIdToActiveJob(jobId) = job
    // 将刚创建的ActiveJob放入activeJobs集合中
    activeJobs += job
    // 使ResultStage的_activeJob属性持有刚创建的ActiveJob
    finalStage.setActiveJob(job)
    // 获取当前JobId的所有StageId
    val stageIds = jobIdToStageIds(jobId).toArray
    // 获取当前Job的所有Stage对应的StageInfo(即数组stageInfos)
    val stageInfos = stageIds.flatMap(id => stageIdToStage.get(id).map(_.latestInfo))
    // 向LiveListenerBus投递SparListenerJobStart事件，进而引发所有关注此事件的监听器质性相应的操作
    listenerBus.post(
      SparkListenerJobStart(job.jobId, jobSubmissionTime, stageInfos, properties))
    // 调用submitStage方法提交ResultStage
    submitStage(finalStage)
  }

  private[scheduler] def handleMapStageSubmitted(jobId: Int,
      dependency: ShuffleDependency[_, _, _],
      callSite: CallSite,
      listener: JobListener,
      properties: Properties) {
    // Submitting this map stage might still require the creation of some parent stages, so make
    // sure that happens.
    var finalStage: ShuffleMapStage = null
    try {
      // New stage creation may throw an exception if, for example, jobs are run on a
      // HadoopRDD whose underlying HDFS files have been deleted.
      finalStage = getOrCreateShuffleMapStage(dependency, jobId)
    } catch {
      case e: Exception =>
        logWarning("Creating new stage failed due to exception - job: " + jobId, e)
        listener.jobFailed(e)
        return
    }

    val job = new ActiveJob(jobId, finalStage, callSite, listener, properties)
    clearCacheLocs()
    logInfo("Got map stage job %s (%s) with %d output partitions".format(
      jobId, callSite.shortForm, dependency.rdd.partitions.length))
    logInfo("Final stage: " + finalStage + " (" + finalStage.name + ")")
    logInfo("Parents of final stage: " + finalStage.parents)
    logInfo("Missing parents: " + getMissingParentStages(finalStage))

    val jobSubmissionTime = clock.getTimeMillis()
    jobIdToActiveJob(jobId) = job
    activeJobs += job
    finalStage.addActiveJob(job)
    val stageIds = jobIdToStageIds(jobId).toArray
    val stageInfos = stageIds.flatMap(id => stageIdToStage.get(id).map(_.latestInfo))
    listenerBus.post(
      SparkListenerJobStart(job.jobId, jobSubmissionTime, stageInfos, properties))
    submitStage(finalStage)

    // If the whole stage has already finished, tell the listener and remove it
    if (finalStage.isAvailable) {
      markMapStageJobAsFinished(job, mapOutputTracker.getStatistics(dependency))
    }
  }

  /** Submits stage, but first recursively submits any missing parents. */
  private def submitStage(stage: Stage) {
    // 找到使用当前Stage的所有ActiveJob的身份标识
    val jobId = activeJobForStage(stage)

    if (jobId.isDefined) {
      logDebug("submitStage(" + stage + ")")
      // 如果存在Stage对应的ActiveJob的身份标识并且当前Stage还未提交
      // （即waitingStages、runningStages、failedStages中都不包含当前Stage）
      if (!waitingStages(stage) && !runningStages(stage) && !failedStages(stage)) {
        // 调用getMissingParentStages方法获取当前Stage的所有未提交的父Stage
        val missing = getMissingParentStages(stage).sortBy(_.id)
        logDebug("missing: " + missing)

        if (missing.isEmpty) {
          // 如果不存在未提交的父Stage，那么调用submitMissingTasks方法提交当前Stage所有未提交的Task
          logInfo("Submitting " + stage + " (" + stage.rdd + "), which has no missing parents")
          submitMissingTasks(stage, jobId.get)
        } else {
          // 如果存在未提交的父Stage，那么将多次调用submitStage方法提交所有未提交的父Stage，
          // 并且将当前Stage加入waitingStages集合中（这表示当前Stage必须等待所有父Stage执行完成）
          for (parent <- missing) {
            submitStage(parent)
          }
          waitingStages += stage
        }
      }
    } else {
      // 如果不存在Stage对应的ActiveJob的身份标识，则调用abortStage方法终止依赖于当前Stage的所有Job。
      abortStage(stage, "No active job for stage " + stage.id, None)
    }
  }

  // 提交Task的入口。此方法在Stage没有不可用的父Stage时，提交当前Stage还未提交的任务
  /** Called when stage's parents are available and we can now do its task. */
  private def submitMissingTasks(stage: Stage, jobId: Int) {
    logDebug("submitMissingTasks(" + stage + ")")

    // 清空当前Stage的pendingPartitions.由于当前Stage的任务刚开始提交，所以需要清空便于记录需要计算的分区任务
    // Get our pending tasks and remember them in our pendingTasks entry
    stage.pendingPartitions.clear()

    // 调用Stage的findMissingPartitions方法，找出当前Stage的所有分区中还没有完成计算的分区的索引
    // First figure out the indexes of partition ids to compute.
    val partitionsToCompute: Seq[Int] = stage.findMissingPartitions()

    // 获取ActiveJob的properties。properties包含了当前Job的调度、group、描述等属性信息
    // Use the scheduling pool, job group, description, etc. from an ActiveJob associated
    // with this Stage
    val properties = jobIdToActiveJob(jobId).properties

    // 将当前Stage加入runningStages集合中，即当前Stage已经处于运行状态
    runningStages += stage
    // SparkListenerStageSubmitted should be posted before testing whether tasks are
    // serializable. If tasks are not serializable, a SparkListenerStageCompleted event
    // will be posted, which should always come after a corresponding SparkListenerStageSubmitted
    // event.
    // 调用OutputCommitCoordinator的stageStart方法，启动对当前Stage的输出提交到HDFS的协调。
    stage match {
      case s: ShuffleMapStage =>
        outputCommitCoordinator.stageStart(stage = s.id, maxPartitionId = s.numPartitions - 1)
      case s: ResultStage =>
        outputCommitCoordinator.stageStart(
          stage = s.id, maxPartitionId = s.rdd.partitions.length - 1)
    }

    // 调用DAGScheduler的getPreferredLocs方法，获取partitionsToCompute中的每一个分区的偏好位置。
    val taskIdToLocations: Map[Int, Seq[TaskLocation]] = try {
      stage match {
        case s: ShuffleMapStage =>
          partitionsToCompute.map { id => (id, getPreferredLocs(stage.rdd, id))}.toMap
        case s: ResultStage =>
          partitionsToCompute.map { id =>
            val p = s.partitions(id)
            (id, getPreferredLocs(stage.rdd, p))
          }.toMap
      }
    } catch {
      // 如果发生异常，则调用Stage的makeNewStageAttempt方法开始一次新的Stage执行尝试，
      // 然后向listenerBus投递SparkListenerStageSubmitted事件。
      case NonFatal(e) =>
        stage.makeNewStageAttempt(partitionsToCompute.size)
        listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))

        abortStage(stage, s"Task creation failed: $e\n${Utils.exceptionString(e)}", Some(e))
        runningStages -= stage
        return
    }

    // 调用Stage.makeNewStageAttempt方法开始Stage的执行尝试
    stage.makeNewStageAttempt(partitionsToCompute.size, taskIdToLocations.values.toSeq)
    // 并向listenerBus投递SparkListenerStageSubmitted事件
    listenerBus.post(SparkListenerStageSubmitted(stage.latestInfo, properties))

    // TODO: Maybe we can keep the taskBinary in Stage to avoid serializing it multiple times.
    // Broadcasted binary for the task, used to dispatch tasks to executors. Note that we broadcast
    // the serialized copy of the RDD and for each task we will deserialize it, which means each
    // task gets a different copy of the RDD. This provides stronger isolation between tasks that
    // might modify state of objects referenced in their closures. This is necessary in Hadoop
    // where the JobConf/Configuration object is not thread-safe.
    var taskBinary: Broadcast[Array[Byte]] = null
    try {
      // For ShuffleMapTask, serialize and broadcast (rdd, shuffleDep).
      // For ResultTask, serialize and broadcast (rdd, func).
      val taskBinaryBytes: Array[Byte] = stage match {
        // 如果当前Stage是ShuffleMapStage,那么对Stage的rdd和ShuffleDependency进行序列化
        case stage: ShuffleMapStage =>
          JavaUtils.bufferToArray(
            closureSerializer.serialize((stage.rdd, stage.shuffleDep): AnyRef))
        // 如果当前Stage是ResultStage,那么对Stage的rdd和对RDD的分区进行计算的函数func进行序列化
        case stage: ResultStage =>
          JavaUtils.bufferToArray(closureSerializer.serialize((stage.rdd, stage.func): AnyRef))
      }
      // 调用SparkContext的broadcast方法广播上一步生成的序列化对象
      taskBinary = sc.broadcast(taskBinaryBytes)
    } catch {
      // In the case of a failure during serialization, abort the stage.
      case e: NotSerializableException =>
        abortStage(stage, "Task not serializable: " + e.toString, Some(e))
        runningStages -= stage

        // Abort execution
        return
      case NonFatal(e) =>
        abortStage(stage, s"Task serialization failed: $e\n${Utils.exceptionString(e)}", Some(e))
        runningStages -= stage
        return
    }

    val tasks: Seq[Task[_]] = try {
      stage match {
        // 如果当前Stage是ShuffleMapStage，则为ShuffleMapStage的每一个分区创建一个ShuffleMapTask.
        case stage: ShuffleMapStage =>
          partitionsToCompute.map { id =>
            val locs = taskIdToLocations(id)
            val part = stage.rdd.partitions(id)
            new ShuffleMapTask(stage.id, stage.latestInfo.attemptId,
              taskBinary, part, locs, stage.latestInfo.taskMetrics, properties, Option(jobId),
              Option(sc.applicationId), sc.applicationAttemptId)
          }

        // 如果当前Stage是ResultStage,则为ResultStage的每一个分区创建一个ResultTask.
        case stage: ResultStage =>
          partitionsToCompute.map { id =>
            val p: Int = stage.partitions(id)
            val part = stage.rdd.partitions(p)
            val locs = taskIdToLocations(id)
            new ResultTask(stage.id, stage.latestInfo.attemptId,
              taskBinary, part, locs, id, properties, stage.latestInfo.taskMetrics,
              Option(jobId), Option(sc.applicationId), sc.applicationAttemptId)
          }
      }
    } catch {
      case NonFatal(e) =>
        abortStage(stage, s"Task creation failed: $e\n${Utils.exceptionString(e)}", Some(e))
        runningStages -= stage
        return
    }

    // 如果上一步创建了至少1个Task，那么将此Task处理的分区索引添加到Stage的pendingPartitions中，然后为这批Task创建TaskSet,
    // 并调用TaskScheduler的submitTasks方法提交此TaskSet.
    if (tasks.size > 0) {
      logInfo("Submitting " + tasks.size + " missing tasks from " + stage + " (" + stage.rdd + ")")
      stage.pendingPartitions ++= tasks.map(_.partitionId)
      logDebug("New pending partitions: " + stage.pendingPartitions)
      taskScheduler.submitTasks(new TaskSet(
        tasks.toArray, stage.id, stage.latestInfo.attemptId, jobId, properties))
      stage.latestInfo.submissionTime = Some(clock.getTimeMillis())
    } else {

      // 如果上一步没有创建任何Task,则意味着当前Stage没有Task需要提交执行，因此调动DAGScheduler的markStageAsFinished方法，
      // 将当前Stage标记为完成。
      // Because we posted SparkListenerStageSubmitted earlier, we should mark
      // the stage as completed here in case there are no tasks to run
      markStageAsFinished(stage, None)

      val debugString = stage match {
        case stage: ShuffleMapStage =>
          s"Stage ${stage} is actually done; " +
            s"(available: ${stage.isAvailable}," +
            s"available outputs: ${stage.numAvailableOutputs}," +
            s"partitions: ${stage.numPartitions})"
        case stage : ResultStage =>
          s"Stage ${stage} is actually done; (partitions: ${stage.numPartitions})"
      }
      logDebug(debugString)

      // 然后调用submitWaitingChildStages方法提交当前Stage的子Stage
      submitWaitingChildStages(stage)
    }
  }

  /**
   * Merge local values from a task into the corresponding accumulators previously registered
   * here on the driver.
   *
   * Although accumulators themselves are not thread-safe, this method is called only from one
   * thread, the one that runs the scheduling loop. This means we only handle one task
   * completion event at a time so we don't need to worry about locking the accumulators.
   * This still doesn't stop the caller from updating the accumulator outside the scheduler,
   * but that's not our problem since there's nothing we can do about that.
   */
  private def updateAccumulators(event: CompletionEvent): Unit = {
    val task = event.task
    val stage = stageIdToStage(task.stageId)
    try {
      event.accumUpdates.foreach { updates =>
        val id = updates.id
        // Find the corresponding accumulator on the driver and update it
        val acc: AccumulatorV2[Any, Any] = AccumulatorContext.get(id) match {
          case Some(accum) => accum.asInstanceOf[AccumulatorV2[Any, Any]]
          case None =>
            throw new SparkException(s"attempted to access non-existent accumulator $id")
        }
        acc.merge(updates.asInstanceOf[AccumulatorV2[Any, Any]])
        // To avoid UI cruft, ignore cases where value wasn't updated
        if (acc.name.isDefined && !updates.isZero) {
          stage.latestInfo.accumulables(id) = acc.toInfo(None, Some(acc.value))
          event.taskInfo.accumulables += acc.toInfo(Some(updates.value), Some(acc.value))
        }
      }
    } catch {
      case NonFatal(e) =>
        logError(s"Failed to update accumulators for task ${task.partitionId}", e)
    }
  }

  /**
   * Responds to a task finishing. This is called inside the event loop so it assumes that it can
   * modify the scheduler's internal state. Use taskEnded() to post a task end event from outside.
   */
  // handleTaskCompletion方法对执行完成的ResultTask进行处理
  private[scheduler] def handleTaskCompletion(event: CompletionEvent) {
    val task = event.task
    val taskId = event.taskInfo.id
    val stageId = task.stageId
    val taskType = Utils.getFormattedClassName(task)

    // 给定Stage的指定分区的任务执行完成后将调用OutputCommitCoordinator的taskCompleted方法
    outputCommitCoordinator.taskCompleted(
      stageId,
      task.partitionId,
      event.taskInfo.attemptNumber, // this is a task attempt number
      event.reason)

    // Reconstruct task metrics. Note: this may be null if the task has failed.
    val taskMetrics: TaskMetrics =
      if (event.accumUpdates.nonEmpty) {
        try {
          TaskMetrics.fromAccumulators(event.accumUpdates)
        } catch {
          case NonFatal(e) =>
            logError(s"Error when attempting to reconstruct metrics for task $taskId", e)
            null
        }
      } else {
        null
      }

    // The stage may have already finished when we get this event -- eg. maybe it was a
    // speculative task. It is important that we send the TaskEnd event in any case, so listeners
    // are properly notified and can chose to handle it. For instance, some listeners are
    // doing their own accounting and if they don't get the task end event they think
    // tasks are still running when they really aren't.
    listenerBus.post(SparkListenerTaskEnd(
       stageId, task.stageAttemptId, taskType, event.reason, event.taskInfo, taskMetrics))

    if (!stageIdToStage.contains(task.stageId)) {
      // Skip all the actions if the stage has been cancelled.
      return
    }

    val stage = stageIdToStage(task.stageId)
    event.reason match {
      case Success =>
        stage.pendingPartitions -= task.partitionId
        task match {

          // ResultTask的结果处理
          case rt: ResultTask[_, _] =>
            // Cast to ResultStage here because it's part of the ResultTask
            // TODO Refactor this out to a function that accepts a ResultStage
            val resultStage = stage.asInstanceOf[ResultStage]

            resultStage.activeJob match {
              case Some(job) =>
                if (!job.finished(rt.outputId)) {
                  updateAccumulators(event)
                  // 将ResultStage的ActiveJob的finished里对应分区的任务设置为完成状态
                  job.finished(rt.outputId) = true
                  // 并且将ActiveJob的已完成的任务数（numFinished）加1
                  job.numFinished += 1
                  // If the whole job has finished, remove it
                  if (job.numFinished == job.numPartitions) {
                    // 如果ActiveJob的所有任务都完成，则调用DAGScheduler的markStageAsFinished方法标记当前Stage完成，
                    markStageAsFinished(resultStage)
                    cleanupStateForJobAndIndependentStages(job)
                    // 并向LiveListenerBus投递SparkListenerJobEnd事件。
                    listenerBus.post(
                      SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobSucceeded))
                  }

                  // taskSucceeded runs some user code that might throw an exception. Make sure
                  // we are resilient against that.
                  try {
                    // 调用JobWaiter的taskSucceded方法，进而调用JobWaiter的resultHandler函数来处理Job中每个Task的执行结果
                    job.listener.taskSucceeded(rt.outputId, event.result)
                  } catch {
                    case e: Exception =>
                      // TODO: Perhaps we want to mark the resultStage as failed?
                      job.listener.jobFailed(new SparkDriverExecutionException(e))
                  }
                }
              case None =>
                logInfo("Ignoring result from " + rt + " because its job has finished")
            }

            // ShuffleMapTask的结果处理
          case smt: ShuffleMapTask =>
            val shuffleStage = stage.asInstanceOf[ShuffleMapStage]
            updateAccumulators(event)
            val status = event.result.asInstanceOf[MapStatus]
            val execId = status.location.executorId
            logDebug("ShuffleMapTask finished on " + execId)
            if (failedEpoch.contains(execId) && smt.epoch <= failedEpoch(execId)) {
              logInfo(s"Ignoring possibly bogus $smt completion from executor $execId")
            } else {
              // 将分区任务的MapStatus追加到Stage的outputLocs中
              shuffleStage.addOutputLoc(smt.partitionId, status)
            }

            if (runningStages.contains(shuffleStage) && shuffleStage.pendingPartitions.isEmpty) {
              // 将ShuffleMapStage标记为完成
              markStageAsFinished(shuffleStage)
              logInfo("looking for newly runnable stages")
              logInfo("running: " + runningStages)
              logInfo("waiting: " + waitingStages)
              logInfo("failed: " + failedStages)

              // We supply true to increment the epoch number here in case this is a
              // recomputation of the map outputs. In that case, some nodes may have cached
              // locations with holes (from when we detected the error) and will need the
              // epoch incremented to refetch them.
              // TODO: Only increment the epoch number if this is not the first time
              //       we registered these map outputs.
              // 将当前Stage的shuffleId和outputLocs中的MapStatus注册到MapOutputTrackerMaster的mapStatuses中
              // 这里注册的MapStatus将最终被reduce任务所用
              mapOutputTracker.registerMapOutputs(
                shuffleStage.shuffleDep.shuffleId,
                shuffleStage.outputLocInMapOutputTrackerFormat(),
                changeEpoch = true)

              clearCacheLocs()

              if (!shuffleStage.isAvailable) {
                // Some tasks had failed; let's resubmit this shuffleStage
                // TODO: Lower-level scheduler should also deal with this
                logInfo("Resubmitting " + shuffleStage + " (" + shuffleStage.name +
                  ") because some of its tasks had failed: " +
                  shuffleStage.findMissingPartitions().mkString(", "))
                // 有任务失败了，这是需要再次提交此ShuffleMapStage
                submitStage(shuffleStage)
              } else {
                // Mark any map-stage jobs waiting on this stage as finished
                if (shuffleStage.mapStageJobs.nonEmpty) {
                  // 获取Shuffle依赖的各个map任务输出Block大小的统计信息，
                  val stats = mapOutputTracker.getStatistics(shuffleStage.shuffleDep)
                  for (job <- shuffleStage.mapStageJobs) {
                    // 将ActiveJob标记为执行成功
                    markMapStageJobAsFinished(job, stats)
                  }
                }
                // 提交当前ShuffleMapStage的下游Stage
                submitWaitingChildStages(shuffleStage)
              }
            }
        }

      case Resubmitted =>
        logInfo("Resubmitted " + task + ", so marking it as still running")
        stage.pendingPartitions += task.partitionId

      case FetchFailed(bmAddress, shuffleId, mapId, reduceId, failureMessage) =>
        val failedStage = stageIdToStage(task.stageId)
        val mapStage = shuffleIdToMapStage(shuffleId)

        if (failedStage.latestInfo.attemptId != task.stageAttemptId) {
          logInfo(s"Ignoring fetch failure from $task as it's from $failedStage attempt" +
            s" ${task.stageAttemptId} and there is a more recent attempt for that stage " +
            s"(attempt ID ${failedStage.latestInfo.attemptId}) running")
        } else {
          // It is likely that we receive multiple FetchFailed for a single stage (because we have
          // multiple tasks running concurrently on different executors). In that case, it is
          // possible the fetch failure has already been handled by the scheduler.
          if (runningStages.contains(failedStage)) {
            logInfo(s"Marking $failedStage (${failedStage.name}) as failed " +
              s"due to a fetch failure from $mapStage (${mapStage.name})")
            markStageAsFinished(failedStage, Some(failureMessage))
          } else {
            logDebug(s"Received fetch failure from $task, but its from $failedStage which is no " +
              s"longer running")
          }

          if (disallowStageRetryForTest) {
            abortStage(failedStage, "Fetch failure will not retry stage due to testing config",
              None)
          } else if (failedStage.failedOnFetchAndShouldAbort(task.stageAttemptId)) {
            abortStage(failedStage, s"$failedStage (${failedStage.name}) " +
              s"has failed the maximum allowable number of " +
              s"times: ${Stage.MAX_CONSECUTIVE_FETCH_FAILURES}. " +
              s"Most recent failure reason: ${failureMessage}", None)
          } else {
            if (failedStages.isEmpty) {
              // Don't schedule an event to resubmit failed stages if failed isn't empty, because
              // in that case the event will already have been scheduled.
              // TODO: Cancel running tasks in the stage
              logInfo(s"Resubmitting $mapStage (${mapStage.name}) and " +
                s"$failedStage (${failedStage.name}) due to fetch failure")
              messageScheduler.schedule(new Runnable {
                override def run(): Unit = eventProcessLoop.post(ResubmitFailedStages)
              }, DAGScheduler.RESUBMIT_TIMEOUT, TimeUnit.MILLISECONDS)
            }
            failedStages += failedStage
            failedStages += mapStage
          }
          // Mark the map whose fetch failed as broken in the map stage
          if (mapId != -1) {
            mapStage.removeOutputLoc(mapId, bmAddress)
            mapOutputTracker.unregisterMapOutput(shuffleId, mapId, bmAddress)
          }

          // TODO: mark the executor as failed only if there were lots of fetch failures on it
          if (bmAddress != null) {
            handleExecutorLost(bmAddress.executorId, filesLost = true, Some(task.epoch))
          }
        }

      case commitDenied: TaskCommitDenied =>
        // Do nothing here, left up to the TaskScheduler to decide how to handle denied commits

      case exceptionFailure: ExceptionFailure =>
        // Tasks failed with exceptions might still have accumulator updates.
        updateAccumulators(event)

      case TaskResultLost =>
        // Do nothing here; the TaskScheduler handles these failures and resubmits the task.

      case _: ExecutorLostFailure | TaskKilled | UnknownReason =>
        // Unrecognized failure - also do nothing. If the task fails repeatedly, the TaskScheduler
        // will abort the job.
    }
  }

  /**
   * Responds to an executor being lost. This is called inside the event loop, so it assumes it can
   * modify the scheduler's internal state. Use executorLost() to post a loss event from outside.
   *
   * We will also assume that we've lost all shuffle blocks associated with the executor if the
   * executor serves its own blocks (i.e., we're not using external shuffle), the entire slave
   * is lost (likely including the shuffle service), or a FetchFailed occurred, in which case we
   * presume all shuffle data related to this executor to be lost.
   *
   * Optionally the epoch during which the failure was caught can be passed to avoid allowing
   * stray fetch failures from possibly retriggering the detection of a node as lost.
   */
  private[scheduler] def handleExecutorLost(
      execId: String,
      filesLost: Boolean,
      maybeEpoch: Option[Long] = None) {
    val currentEpoch = maybeEpoch.getOrElse(mapOutputTracker.getEpoch)
    if (!failedEpoch.contains(execId) || failedEpoch(execId) < currentEpoch) {
      failedEpoch(execId) = currentEpoch
      logInfo("Executor lost: %s (epoch %d)".format(execId, currentEpoch))
      blockManagerMaster.removeExecutor(execId)

      if (filesLost || !env.blockManager.externalShuffleServiceEnabled) {
        logInfo("Shuffle files lost for executor: %s (epoch %d)".format(execId, currentEpoch))
        // TODO: This will be really slow if we keep accumulating shuffle map stages
        for ((shuffleId, stage) <- shuffleIdToMapStage) {
          stage.removeOutputsOnExecutor(execId)
          mapOutputTracker.registerMapOutputs(
            shuffleId,
            stage.outputLocInMapOutputTrackerFormat(),
            changeEpoch = true)
        }
        if (shuffleIdToMapStage.isEmpty) {
          mapOutputTracker.incrementEpoch()
        }
        clearCacheLocs()
      }
    } else {
      logDebug("Additional executor lost message for " + execId +
               "(epoch " + currentEpoch + ")")
    }
  }

  // 用于将Executor的身份标识从failedEpoch中移除
  private[scheduler] def handleExecutorAdded(execId: String, host: String) {
    // remove from failedEpoch(execId) ?
    if (failedEpoch.contains(execId)) {
      logInfo("Host added was in lost list earlier: " + host)
      failedEpoch -= execId
    }
  }

  private[scheduler] def handleStageCancellation(stageId: Int) {
    stageIdToStage.get(stageId) match {
      case Some(stage) =>
        val jobsThatUseStage: Array[Int] = stage.jobIds.toArray
        jobsThatUseStage.foreach { jobId =>
          handleJobCancellation(jobId, s"because Stage $stageId was cancelled")
        }
      case None =>
        logInfo("No active jobs to kill for Stage " + stageId)
    }
  }

  private[scheduler] def handleJobCancellation(jobId: Int, reason: String = "") {
    if (!jobIdToStageIds.contains(jobId)) {
      logDebug("Trying to cancel unregistered job " + jobId)
    } else {
      failJobAndIndependentStages(
        jobIdToActiveJob(jobId), "Job %d cancelled %s".format(jobId, reason))
    }
  }

  /**
   * Marks a stage as finished and removes it from the list of running stages.
   */
  // 将Stage标记为完成
  private def markStageAsFinished(stage: Stage, errorMessage: Option[String] = None): Unit = {
    // 计算Stage的执行时间
    val serviceTime = stage.latestInfo.submissionTime match {
      case Some(t) => "%.03f".format((clock.getTimeMillis() - t) / 1000.0)
      case _ => "Unknown"
    }

    if (errorMessage.isEmpty) {
      // 如果质执行Stage的过程中没有发生错误，那么设置Stage的lastInfo(即StageInfo)的完成时间为系统当前时间，
      logInfo("%s (%s) finished in %s s".format(stage, stage.name, serviceTime))
      stage.latestInfo.completionTime = Some(clock.getTimeMillis())

      // Clear failure count for this stage, now that it's succeeded.
      // We only limit consecutive failures of stage attempts,so that if a stage is
      // re-used many times in a long-running job, unrelated failures don't eventually cause the
      // stage to be aborted.
      // 并调用Stage的clearFailures方法清空fetchfailedAttemptIds
      stage.clearFailures()
    } else {
      // 如果执行Stage的过程中发生错误，那么调用StageInfo的stageFailed方法保存失败原因和Stage的完成时间
      stage.latestInfo.stageFailed(errorMessage.get)
      logInfo(s"$stage (${stage.name}) failed in $serviceTime s due to ${errorMessage.get}")
    }

    // 调用OutputCommitCoordinator的stageEnd方法，停止对当前Stage的输出提交到HDFS的协调
    outputCommitCoordinator.stageEnd(stage.id)

    // 向LiveListenerBus投递SparkListenerStageCompleted事件
    listenerBus.post(SparkListenerStageCompleted(stage.latestInfo))
    // 将当前Stage从正在运行的Stage中移除
    runningStages -= stage
  }

  /**
   * Aborts all jobs depending on a particular Stage. This is called in response to a task set
   * being canceled by the TaskScheduler. Use taskSetFailed() to inject this event from outside.
   */
  private[scheduler] def abortStage(
      failedStage: Stage,
      reason: String,
      exception: Option[Throwable]): Unit = {
    if (!stageIdToStage.contains(failedStage.id)) {
      // Skip all the actions if the stage has been removed.
      return
    }
    val dependentJobs: Seq[ActiveJob] =
      activeJobs.filter(job => stageDependsOn(job.finalStage, failedStage)).toSeq
    failedStage.latestInfo.completionTime = Some(clock.getTimeMillis())
    for (job <- dependentJobs) {
      failJobAndIndependentStages(job, s"Job aborted due to stage failure: $reason", exception)
    }
    if (dependentJobs.isEmpty) {
      logInfo("Ignoring failure of " + failedStage + " because all jobs depending on it are done")
    }
  }

  /** Fails a job and all stages that are only used by that job, and cleans up relevant state. */
  private def failJobAndIndependentStages(
      job: ActiveJob,
      failureReason: String,
      exception: Option[Throwable] = None): Unit = {
    val error = new SparkException(failureReason, exception.getOrElse(null))
    var ableToCancelStages = true

    val shouldInterruptThread =
      if (job.properties == null) false
      else job.properties.getProperty(SparkContext.SPARK_JOB_INTERRUPT_ON_CANCEL, "false").toBoolean

    // Cancel all independent, running stages.
    val stages = jobIdToStageIds(job.jobId)
    if (stages.isEmpty) {
      logError("No stages registered for job " + job.jobId)
    }
    stages.foreach { stageId =>
      val jobsForStage: Option[HashSet[Int]] = stageIdToStage.get(stageId).map(_.jobIds)
      if (jobsForStage.isEmpty || !jobsForStage.get.contains(job.jobId)) {
        logError(
          "Job %d not registered for stage %d even though that stage was registered for the job"
            .format(job.jobId, stageId))
      } else if (jobsForStage.get.size == 1) {
        if (!stageIdToStage.contains(stageId)) {
          logError(s"Missing Stage for stage with id $stageId")
        } else {
          // This is the only job that uses this stage, so fail the stage if it is running.
          val stage = stageIdToStage(stageId)
          if (runningStages.contains(stage)) {
            try { // cancelTasks will fail if a SchedulerBackend does not implement killTask
              taskScheduler.cancelTasks(stageId, shouldInterruptThread)
              markStageAsFinished(stage, Some(failureReason))
            } catch {
              case e: UnsupportedOperationException =>
                logInfo(s"Could not cancel tasks for stage $stageId", e)
              ableToCancelStages = false
            }
          }
        }
      }
    }

    if (ableToCancelStages) {
      // SPARK-15783 important to cleanup state first, just for tests where we have some asserts
      // against the state.  Otherwise we have a *little* bit of flakiness in the tests.
      cleanupStateForJobAndIndependentStages(job)
      job.listener.jobFailed(error)
      listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobFailed(error)))
    }
  }

  /** Return true if one of stage's ancestors is target. */
  private def stageDependsOn(stage: Stage, target: Stage): Boolean = {
    if (stage == target) {
      return true
    }
    val visitedRdds = new HashSet[RDD[_]]
    // We are manually maintaining a stack here to prevent StackOverflowError
    // caused by recursively visiting
    val waitingForVisit = new Stack[RDD[_]]
    def visit(rdd: RDD[_]) {
      if (!visitedRdds(rdd)) {
        visitedRdds += rdd
        for (dep <- rdd.dependencies) {
          dep match {
            case shufDep: ShuffleDependency[_, _, _] =>
              val mapStage = getOrCreateShuffleMapStage(shufDep, stage.firstJobId)
              if (!mapStage.isAvailable) {
                waitingForVisit.push(mapStage.rdd)
              }  // Otherwise there's no need to follow the dependency back
            case narrowDep: NarrowDependency[_] =>
              waitingForVisit.push(narrowDep.rdd)
          }
        }
      }
    }
    waitingForVisit.push(stage.rdd)
    while (waitingForVisit.nonEmpty) {
      visit(waitingForVisit.pop())
    }
    visitedRdds.contains(target.rdd)
  }

  /**
   * Gets the locality information associated with a partition of a particular RDD.
   *
   * This method is thread-safe and is called from both DAGScheduler and SparkContext.
   *
   * @param rdd whose partitions are to be looked at
   * @param partition to lookup locality information for
   * @return list of machines that are preferred by the partition
   */
  // 用于获取RDD的指定分区的偏好位置
  private[spark]
  def getPreferredLocs(rdd: RDD[_], partition: Int): Seq[TaskLocation] = {
    getPreferredLocsInternal(rdd, partition, new HashSet)
  }

  /**
   * Recursive implementation for getPreferredLocs.
   *
   * This method is thread-safe because it only accesses DAGScheduler state through thread-safe
   * methods (getCacheLocs()); please be careful when modifying this method, because any new
   * DAGScheduler state accessed by it may require additional synchronization.
   */
  // getPreferredLocsInternal方法用于获取RDD的指定分区的偏好位置
  private def getPreferredLocsInternal(
      rdd: RDD[_],
      partition: Int,
      visited: HashSet[(RDD[_], Int)]): Seq[TaskLocation] = {
    // If the partition has already been visited, no need to re-visit.
    // This avoids exponential path exploration.  SPARK-695
    // 避免对RDD的指定分区的重复访问
    if (!visited.add((rdd, partition))) {
      // Nil has already been returned for previously visited partitions.
      return Nil
    }
    // If the partition is cached, return the cache locations
    // 获取RDD的指定分区的位置信息
    val cached = getCacheLocs(rdd)(partition)
    if (cached.nonEmpty) {
      return cached
    }
    // 获取RDD指定分区的偏好位置
    // If the RDD has some placement preferences (as is the case for input RDDs), get those
    val rddPrefs = rdd.preferredLocations(rdd.partitions(partition)).toList
    if (rddPrefs.nonEmpty) {
      return rddPrefs.map(TaskLocation(_))
    }

    // If the RDD has narrow dependencies, pick the first partition of the first narrow dependency
    // that has any placement preferences. Ideally we would choose based on transfer sizes,
    // but this will do for now.
    // 以窄依赖的RDD的同一分区的偏好位置作为当前RDD的此分区的偏好位置
    rdd.dependencies.foreach {
      case n: NarrowDependency[_] =>
        for (inPart <- n.getParents(partition)) {
          val locs = getPreferredLocsInternal(n.rdd, inPart, visited)
          if (locs != Nil) {
            return locs
          }
        }

      case _ =>
    }
    // 如果以上步骤都未能获取到RDD的指定分区的偏好位置，则返回Nil
    Nil
  }

  /** Mark a map stage job as finished with the given output stats, and report to its listener. */
  def markMapStageJobAsFinished(job: ActiveJob, stats: MapOutputStatistics): Unit = {
    // In map stage jobs, we only create a single "task", which is to finish all of the stage
    // (including reusing any previous map outputs, etc); so we just mark task 0 as done
    job.finished(0) = true
    job.numFinished += 1
    job.listener.taskSucceeded(0, stats)
    cleanupStateForJobAndIndependentStages(job)
    listenerBus.post(SparkListenerJobEnd(job.jobId, clock.getTimeMillis(), JobSucceeded))
  }

  def stop() {
    messageScheduler.shutdownNow()
    eventProcessLoop.stop()
    taskScheduler.stop()
  }

  eventProcessLoop.start()
}

// DAGSchedulerEventProcessLoop是DAGScheduler内部的事件循环处理器，用于处理DAGSchedulerEvent类型的事件。
private[scheduler] class DAGSchedulerEventProcessLoop(dagScheduler: DAGScheduler)
  extends EventLoop[DAGSchedulerEvent]("dag-scheduler-event-loop") with Logging {

  private[this] val timer = dagScheduler.metricsSource.messageProcessingTimer

  /**
   * The main event loop of the DAG scheduler.
   */
  override def onReceive(event: DAGSchedulerEvent): Unit = {
    val timerContext = timer.time()
    try {
      doOnReceive(event)
    } finally {
      timerContext.stop()
    }
  }

  private def doOnReceive(event: DAGSchedulerEvent): Unit = event match {
    case JobSubmitted(jobId, rdd, func, partitions, callSite, listener, properties) =>
      dagScheduler.handleJobSubmitted(jobId, rdd, func, partitions, callSite, listener, properties)

    case MapStageSubmitted(jobId, dependency, callSite, listener, properties) =>
      dagScheduler.handleMapStageSubmitted(jobId, dependency, callSite, listener, properties)

    case StageCancelled(stageId) =>
      dagScheduler.handleStageCancellation(stageId)

    case JobCancelled(jobId) =>
      dagScheduler.handleJobCancellation(jobId)

    case JobGroupCancelled(groupId) =>
      dagScheduler.handleJobGroupCancelled(groupId)

    case AllJobsCancelled =>
      dagScheduler.doCancelAllJobs()

    case ExecutorAdded(execId, host) =>
      dagScheduler.handleExecutorAdded(execId, host)

    case ExecutorLost(execId, reason) =>
      val filesLost = reason match {
        case SlaveLost(_, true) => true
        case _ => false
      }
      dagScheduler.handleExecutorLost(execId, filesLost)

    case BeginEvent(task, taskInfo) =>
      dagScheduler.handleBeginEvent(task, taskInfo)

    case GettingResultEvent(taskInfo) =>
      dagScheduler.handleGetTaskResult(taskInfo)

    case completion: CompletionEvent =>
      dagScheduler.handleTaskCompletion(completion)

    case TaskSetFailed(taskSet, reason, exception) =>
      dagScheduler.handleTaskSetFailed(taskSet, reason, exception)

    case ResubmitFailedStages =>
      dagScheduler.resubmitFailedStages()
  }

  override def onError(e: Throwable): Unit = {
    logError("DAGSchedulerEventProcessLoop failed; shutting down SparkContext", e)
    try {
      dagScheduler.doCancelAllJobs()
    } catch {
      case t: Throwable => logError("DAGScheduler failed to cancel all jobs.", t)
    }
    dagScheduler.sc.stopInNewThread()
  }

  override def onStop(): Unit = {
    // Cancel any active jobs in postStop hook
    dagScheduler.cleanUpAfterSchedulerStop()
  }
}

private[spark] object DAGScheduler {
  // The time, in millis, to wait for fetch failure events to stop coming in after one is detected;
  // this is a simplistic way to avoid resubmitting tasks in the non-fetchable map stage one by one
  // as more failure events come in
  val RESUBMIT_TIMEOUT = 200
}
