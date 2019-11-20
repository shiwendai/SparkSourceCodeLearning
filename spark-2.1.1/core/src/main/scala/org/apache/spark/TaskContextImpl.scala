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

package org.apache.spark

import java.util.Properties

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.memory.TaskMemoryManager
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.metrics.source.Source
import org.apache.spark.util._

private[spark] class TaskContextImpl(
    val stageId: Int,
    val partitionId: Int,
    override val taskAttemptId: Long, // 任务尝试的身份标识
    override val attemptNumber: Int,  // 任务尝试号
    override val taskMemoryManager: TaskMemoryManager,
    localProperties: Properties,
    @transient private val metricsSystem: MetricsSystem,
    // The default value is only used in tests.
    override val taskMetrics: TaskMetrics = TaskMetrics.empty)
  extends TaskContext
  with Logging {

  // 保存任务执行完成后需要回调的TaskCompletionListener的数组
  /** List of callback functions to execute when the task completes. */
  @transient private val onCompleteCallbacks = new ArrayBuffer[TaskCompletionListener]

  // 保存任务执行完成后需要回调的TaskFailureListener的数组
  /** List of callback functions to execute when the task fails. */
  @transient private val onFailureCallbacks = new ArrayBuffer[TaskFailureListener]

  // TaskContextImpl相对应的任务尝试是否已经被kill的状态。之所以用interrupted作为任务尝试被kill的状态变量，
  // 因为kill实际是通过对执行任务尝试的线程进行中断实现的。
  // Whether the corresponding task has been killed.
  @volatile private var interrupted: Boolean = false

  // TaskContextImpl相对应的任务尝试是否已经完成的状态
  // Whether the task has completed.
  @volatile private var completed: Boolean = false

  // TaskContextImpl相对应的任务尝试是否已经失败的状态
  // Whether the task has failed.
  @volatile private var failed: Boolean = false

  // 此方法用于向onCompleteCallbacks中添加TaskCompletionListener
  override def addTaskCompletionListener(listener: TaskCompletionListener): this.type = {
    onCompleteCallbacks += listener
    this
  }

  // 此方法用于向onFailureCallbacks中添加TaskFailureListener
  override def addTaskFailureListener(listener: TaskFailureListener): this.type = {
    onFailureCallbacks += listener
    this
  }

  // 标记Task执行失败
  /** Marks the task as failed and triggers the failure listeners. */
  private[spark] def markTaskFailed(error: Throwable): Unit = {
    // failure callbacks should only be called once
    // 判断Task是否已经被标记为失败，如果failed为true，则直接返回
    if (failed) return
    // 设置failed为true
    failed = true
    val errorMsgs = new ArrayBuffer[String](2)
    // Process failure callbacks in the reverse order of registration
    // 对onFailureCallbacks进行反向排序后，对onFailureCallBacks中的每一个TaskFailureListener，调用其onTaskFailure方法
    onFailureCallbacks.reverse.foreach { listener =>
      try {
        listener.onTaskFailure(this, error)
      } catch {
        // 如果调用onTaskFailure方法的过程中发生了异常，这些异常将被收集到errorMsgs中
        case e: Throwable =>
          errorMsgs += e.getMessage
          logError("Error in TaskFailureListener", e)
      }
    }
    // 如果errorMsgs不为空，则对外抛出携带errorMsgs的TaskCompletionListenerException
    if (errorMsgs.nonEmpty) {
      throw new TaskCompletionListenerException(errorMsgs, Option(error))
    }
  }

  // 标记Task执行完成
  /** Marks the task as completed and triggers the completion listeners. */
  private[spark] def markTaskCompleted(): Unit = {
    // 设置completed为true
    completed = true
    val errorMsgs = new ArrayBuffer[String](2)
    // Process complete callbacks in the reverse order of registration
    // 对onCompleteCallbacks进行反向排序，
    onCompleteCallbacks.reverse.foreach { listener =>
      try {
        // 对onCompleteCallbacks中每一个TaskCompletionListener，调用其onTaskCompetion方法
        listener.onTaskCompletion(this)
      } catch {
        // 如果调用onTaskCompletion方法的过程中发生了异常，这些异常将被搜集到errorMsgs中
        case e: Throwable =>
          errorMsgs += e.getMessage
          logError("Error in TaskCompletionListener", e)
      }
    }
    // 如果errorMsgs不为空，则对外抛出携带errorMsgs的TaskCompletionListenerException
    if (errorMsgs.nonEmpty) {
      throw new TaskCompletionListenerException(errorMsgs)
    }
  }

  // 标记Task已经被kill
  /** Marks the task for interruption, i.e. cancellation. */
  private[spark] def markInterrupted(): Unit = {
    interrupted = true
  }

  override def isCompleted(): Boolean = completed

  override def isRunningLocally(): Boolean = false

  override def isInterrupted(): Boolean = interrupted

  // 获取指定key对应的本地属性值
  override def getLocalProperty(key: String): String = localProperties.getProperty(key)

  override def getMetricsSources(sourceName: String): Seq[Source] =
    metricsSystem.getSourcesByName(sourceName)

  // 向TaskMetrics注册累加器
  private[spark] override def registerAccumulator(a: AccumulatorV2[_, _]): Unit = {
    taskMetrics.registerAccumulator(a)
  }

}
