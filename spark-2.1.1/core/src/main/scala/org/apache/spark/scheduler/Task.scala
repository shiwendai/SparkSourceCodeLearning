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

import java.io.{DataInputStream, DataOutputStream}
import java.nio.ByteBuffer
import java.util.Properties

import scala.collection.mutable
import scala.collection.mutable.HashMap

import org.apache.spark._
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.memory.{MemoryMode, TaskMemoryManager}
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.util._

/**
 * A unit of execution. We have two kinds of Task's in Spark:
 *
 *  - [[org.apache.spark.scheduler.ShuffleMapTask]]
 *  - [[org.apache.spark.scheduler.ResultTask]]
 *
 * A Spark job consists of one or more stages. The very last stage in a job consists of multiple
 * ResultTasks, while earlier stages consist of ShuffleMapTasks. A ResultTask executes the task
 * and sends the task output back to the driver application. A ShuffleMapTask executes the task
 * and divides the task output to multiple buckets (based on the task's partitioner).
 *
 * @param stageId id of the stage this task belongs to
 * @param stageAttemptId attempt id of the stage this task belongs to
 * @param partitionId index of the number in the RDD
 * @param metrics a `TaskMetrics` that is created at driver side and sent to executor side.
 * @param localProperties copy of thread-local properties set by the user on the driver side.
 *
 * The parameters below are optional:
 * @param jobId id of the job this task belongs to
 * @param appId id of the app this task belongs to
 * @param appAttemptId attempt id of the app this task belongs to
 */
// Task是Spark中作业运行的最小单位，为了容错，每个Task可能会有一到多次任务尝试。
// Task主要包括ShuffleMapTask和ResultTask两种。每次任务尝试都会申请单独的连续内存，以执行计算。
private[spark] abstract class Task[T](
    val stageId: Int, // Task所属Stage的身份标识
    val stageAttemptId: Int, // Stage尝试的身份标识
    val partitionId: Int, // Task对应的分区索引
    // The default value is only used in tests.
    val metrics: TaskMetrics = TaskMetrics.registered,
    @transient var localProperties: Properties = new Properties, // Task执行所需的属性信息
    val jobId: Option[Int] = None, // Task所属Job身份标识
    val appId: Option[String] = None, // Task所属Application的身份标识，即SparkContext的_application属性
    // Task所属Application尝试的身份标识，即SparkContext的_applictionAttemptId属性
    val appAttemptId: Option[String] = None) extends Serializable {

  /**
   * Called by [[org.apache.spark.executor.Executor]] to run this task.
   *
   * @param taskAttemptId an identifier for this task attempt that is unique within a SparkContext.
   * @param attemptNumber how many times this task has been attempted (0 for the first attempt)
   * @return the result of the task along with updates of Accumulators.
   */
  final def run(
      taskAttemptId: Long, // 任务尝试的标识符在SparkContext中是唯一的
      attemptNumber: Int,  // 任务尝试的次数
      metricsSystem: MetricsSystem): T = {
    // 调用BlockManager的registerTask方法将任务尝试注册到BlockInfoManager
    SparkEnv.get.blockManager.registerTask(taskAttemptId)
    // 创建任务尝试的上下文
    context = new TaskContextImpl(
      stageId,
      partitionId,
      taskAttemptId,
      attemptNumber,
      taskMemoryManager,
      localProperties,
      metricsSystem,
      metrics)
    // 调用TaskContext的伴生对象的setTaskContext方法将TaskContextImpl设置到ThreadLocal中
    TaskContext.setTaskContext(context)
    // 获取运行尝试任务的线程，并有taskThread属性保存
    taskThread = Thread.currentThread()

    // 如果任务尝试已经被kill，则调用kill方法，将任务尝试及TaskContextImpl标记为kill（因为interruptThread为false）
    if (_killed) {
      kill(interruptThread = false)
    }

    // 创建调用者上下文，CallerContext是Utils工具类中提供的保存调用者上下文信息的类型
    new CallerContext("TASK", appId, appAttemptId, jobId, Option(stageId), Option(stageAttemptId),
      Option(taskAttemptId), Option(attemptNumber)).setCurrentContext()

    try {
      // 调用子类实现的runTask方法运行任务尝试。
      runTask(context)
    } catch {
      // 如果执行runTask方法时捕获到任何错误，则调用TaskContextImpl的markTaskFailed方法，
      // 执行所有TaskFailureListener的onTaskFailure方法
      case e: Throwable =>
        // Catch all errors; run task failure callbacks, and rethrow the exception.
        try {
          context.markTaskFailed(e)
        } catch {
          case t: Throwable =>
            e.addSuppressed(t)
        }
        throw e
    } finally {
      // Call the task completion callbacks.
      // 无论任务尝试是否成功，最后再finally中调用TaskContextImpl的markTaskCompleted方法,
      // 执行所有TaskCompletionListener的onTaskCompletion方法
      context.markTaskCompleted()
      try {
        // 调用MemoryStore的releaseUnrollMemoryForThisTask方法，释放任务尝试所占用的堆内存和堆外内存，
        // 以便唤醒任何等待MemoryManager管理的执行内存的任务尝试
        Utils.tryLogNonFatalError {
          // Release memory used by this thread for unrolling blocks
          SparkEnv.get.blockManager.memoryStore.releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP)
          SparkEnv.get.blockManager.memoryStore.releaseUnrollMemoryForThisTask(MemoryMode.OFF_HEAP)
          // Notify any tasks waiting for execution memory to be freed to wake up and try to
          // acquire memory again. This makes impossible the scenario where a task sleeps forever
          // because there are no other tasks left to notify it. Since this is safe to do but may
          // not be strictly necessary, we should revisit whether we can remove this in the future.
          val memoryManager = SparkEnv.get.memoryManager
          memoryManager.synchronized { memoryManager.notifyAll() }
        }
      } finally {
        // 最后调用TaskContext伴生对象的unset方法，移除ThreadLocal中保存的当前Task线程的TaskContextImpl
        TaskContext.unset()
      }
    }
  }

  // Task内存管理器
  private var taskMemoryManager: TaskMemoryManager = _

  // 用于设置Task的taskMemoryManager
  def setTaskMemoryManager(taskMemoryManager: TaskMemoryManager): Unit = {
    this.taskMemoryManager = taskMemoryManager
  }

  // 运行Task的接口
  def runTask(context: TaskContext): T

  // 获取当前Task偏好位置信息
  def preferredLocations: Seq[TaskLocation] = Nil

  // MapOutputTracker跟踪的纪元，此属性由TaskScheduler设置，用于故障迁移
  // Map output tracker epoch. Will be set by TaskScheduler.
  var epoch: Long = -1

  // Task context, to be initialized in run().
  // TaskContextImpl将被设置到ThreadLocal中，以保证其线程安全
  @transient protected var context: TaskContextImpl = _

  // 运行任务尝试的线程
  // The actual Thread on which the task is running, if any. Initialized in run().
  @volatile @transient private var taskThread: Thread = _

  // A flag to indicate whether the task is killed. This is used in case context is not yet
  // initialized when kill() is invoked.
  // Task是否被kill的状态
  @volatile @transient private var _killed = false

  // 对RDD进行反序列化所花费的时间
  protected var _executorDeserializeTime: Long = 0
  // 对RDD进行反序列化所花费的CPU时间
  protected var _executorDeserializeCpuTime: Long = 0

  /**
   * Whether the task has been killed.
   */
  // 用于判断任务尝试是否已经被"杀死"
  def killed: Boolean = _killed

  /**
   * Returns the amount of time spent deserializing the RDD and function to be run.
   */
  def executorDeserializeTime: Long = _executorDeserializeTime
  def executorDeserializeCpuTime: Long = _executorDeserializeCpuTime

  /**
   * Collect the latest values of accumulators used in this task. If the task failed,
   * filter out the accumulators whose values should not be included on failures.
   */
  // 收集Task使用的累加器的最新值，并更新到TaskMetrics中
  def collectAccumulatorUpdates(taskFailed: Boolean = false): Seq[AccumulatorV2[_, _]] = {
    if (context != null) {
      context.taskMetrics.internalAccums.filter { a =>
        // RESULT_SIZE accumulator is always zero at executor, we need to send it back as its
        // value will be updated at driver side.
        // Note: internal accumulators representing task metrics always count failed values
        !a.isZero || a.name == Some(InternalAccumulator.RESULT_SIZE)
      // zero value external accumulators may still be useful, e.g. SQLMetrics, we should not filter
      // them out.
      } ++ context.taskMetrics.externalAccums.filter(a => !taskFailed || a.countFailedValues)
    } else {
      Seq.empty
    }
  }

  /**
   * Kills a task by setting the interrupted flag to true. This relies on the upper level Spark
   * code and user code to properly handle the flag. This function should be idempotent so it can
   * be called multiple times.
   * If interruptThread is true, we will also call Thread.interrupt() on the Task's executor thread.
   */
  // 用于kill任务尝试线程，如果interruptThread为false，则只会将Task和TaskContextImpl标记为已经被kill。
  // 如果interruptThread为true，还会利用Java线程的中断机制中断任务尝试线程。
  def kill(interruptThread: Boolean) {
    _killed = true
    if (context != null) {
      context.markInterrupted()
    }
    if (interruptThread && taskThread != null) {
      taskThread.interrupt()
    }
  }
}

/**
 * Handles transmission of tasks and their dependencies, because this can be slightly tricky. We
 * need to send the list of JARs and files added to the SparkContext with each task to ensure that
 * worker nodes find out about it, but we can't make it part of the Task because the user's code in
 * the task might depend on one of the JARs. Thus we serialize each task as multiple objects, by
 * first writing out its dependencies.
 */
private[spark] object Task {
  /**
   * Serialize a task and the current app dependencies (files and JARs added to the SparkContext)
   */
  def serializeWithDependencies(
      task: Task[_],
      currentFiles: mutable.Map[String, Long],
      currentJars: mutable.Map[String, Long],
      serializer: SerializerInstance)
    : ByteBuffer = {

    val out = new ByteBufferOutputStream(4096)
    val dataOut = new DataOutputStream(out)

    // Write currentFiles
    dataOut.writeInt(currentFiles.size)
    for ((name, timestamp) <- currentFiles) {
      dataOut.writeUTF(name)
      dataOut.writeLong(timestamp)
    }

    // Write currentJars
    dataOut.writeInt(currentJars.size)
    for ((name, timestamp) <- currentJars) {
      dataOut.writeUTF(name)
      dataOut.writeLong(timestamp)
    }

    // Write the task properties separately so it is available before full task deserialization.
    val propBytes = Utils.serialize(task.localProperties)
    dataOut.writeInt(propBytes.length)
    dataOut.write(propBytes)

    // Write the task itself and finish
    dataOut.flush()
    val taskBytes = serializer.serialize(task)
    Utils.writeByteBuffer(taskBytes, out)
    out.close()
    out.toByteBuffer
  }

  /**
   * Deserialize the list of dependencies in a task serialized with serializeWithDependencies,
   * and return the task itself as a serialized ByteBuffer. The caller can then update its
   * ClassLoaders and deserialize the task.
   *
   * @return (taskFiles, taskJars, taskProps, taskBytes)
   */
  def deserializeWithDependencies(serializedTask: ByteBuffer)
    : (HashMap[String, Long], HashMap[String, Long], Properties, ByteBuffer) = {

    val in = new ByteBufferInputStream(serializedTask)
    val dataIn = new DataInputStream(in)

    // Read task's files
    val taskFiles = new HashMap[String, Long]()
    val numFiles = dataIn.readInt()
    for (i <- 0 until numFiles) {
      taskFiles(dataIn.readUTF()) = dataIn.readLong()
    }

    // Read task's JARs
    val taskJars = new HashMap[String, Long]()
    val numJars = dataIn.readInt()
    for (i <- 0 until numJars) {
      taskJars(dataIn.readUTF()) = dataIn.readLong()
    }

    val propLength = dataIn.readInt()
    val propBytes = new Array[Byte](propLength)
    dataIn.readFully(propBytes, 0, propLength)
    val taskProps = Utils.deserialize[Properties](propBytes)

    // Create a sub-buffer for the rest of the data, which is the serialized Task object
    val subBuffer = serializedTask.slice()  // ByteBufferInputStream will have read just up to task
    (taskFiles, taskJars, taskProps, subBuffer)
  }
}
