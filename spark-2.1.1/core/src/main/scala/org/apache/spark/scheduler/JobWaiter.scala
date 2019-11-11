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

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Future, Promise}

import org.apache.spark.internal.Logging

/**
 * An object that waits for a DAGScheduler job to complete. As tasks finish, it passes their
 * results to the given handler function.
 */

// JobWaiter用于等待整个Job执行完毕，然后调用给定的处理函数对返回结果进行处理
private[spark] class JobWaiter[T](
    dagScheduler: DAGScheduler, // 当前JobWaiter等待执行完成的Job的调度者
    val jobId: Int, // 当前JobWaiter等待执行完成的Job的身份标识
    totalTasks: Int, // 等待完成的Job包括的Task数量
    resultHandler: (Int, T) => Unit) // 执行结果的处理器
  extends JobListener with Logging {

  // 等待完成的Job中 已经完成的Task数量
  private val finishedTasks = new AtomicInteger(0)
  // If the job is finished, this will be its result. In the case of 0 task jobs (e.g. zero
  // partition RDDs), we set the jobResult directly to JobSucceeded.
  // jobPromise 用来代表Job完成后的结果。如果totalTasks等于0，说明没有Task需要执行，此时jobPromise将直接设置为Success
  private val jobPromise: Promise[Unit] =
    if (totalTasks == 0) Promise.successful(()) else Promise()

  // Job是否已经完成
  def jobFinished: Boolean = jobPromise.isCompleted

  // JobPromise的Future
  def completionFuture: Future[Unit] = jobPromise.future

  /**
   * Sends a signal to the DAGScheduler to cancel the job. The cancellation itself is handled
   * asynchronously. After the low level scheduler cancels all the tasks belonging to this job, it
   * will fail this job with a SparkException.
   */
  // 取消对Job的执行
  def cancel() {
    dagScheduler.cancelJob(jobId)
  }

  //
  override def taskSucceeded(index: Int, result: Any): Unit = {
    // resultHandler call must be synchronized in case resultHandler itself is not thread safe.
    // 调用resultHandler函数来处理Job中每个Task的执行结果
    synchronized {
      resultHandler(index, result.asInstanceOf[T])
    }
    // 增加已完成的Task数量，如果Job的所有Task都已经完成，那么将jobPromise设置为Success
    if (finishedTasks.incrementAndGet() == totalTasks) {
      jobPromise.success(())
    }
  }

  // 实际执行将jobPromise设置为Failure
  override def jobFailed(exception: Exception): Unit = {
    if (!jobPromise.tryFailure(exception)) {
      logWarning("Ignore failure", exception)
    }
  }

}
