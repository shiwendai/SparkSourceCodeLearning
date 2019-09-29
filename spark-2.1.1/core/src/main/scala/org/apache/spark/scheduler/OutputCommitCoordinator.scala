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

import scala.collection.mutable

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.{RpcCallContext, RpcEndpoint, RpcEndpointRef, RpcEnv}

private sealed trait OutputCommitCoordinationMessage extends Serializable

private case object StopCoordinator extends OutputCommitCoordinationMessage
private case class AskPermissionToCommitOutput(stage: Int, partition: Int, attemptNumber: Int)

/**
 * Authority that decides whether tasks can commit output to HDFS. Uses a "first committer wins"
 * policy.
 *
 * OutputCommitCoordinator is instantiated in both the drivers and executors. On executors, it is
 * configured with a reference to the driver's OutputCommitCoordinatorEndpoint, so requests to
 * commit output will be forwarded to the driver's OutputCommitCoordinator.
 *
 * This class was introduced in SPARK-4879; see that JIRA issue (and the associated pull requests)
 * for an extensive design discussion.
 */
// OutputCommitCoordinator将决定任务是否可以提交输出到HDFS.无论是Driver还是Executor,在SparkEnv中都包含了
// 子组件OutputCommitCoordinator.在Driver上注册了OutputCommitCoordinatorEndpoint,所有Executor上的
// OutputCommitCoordinator都是通过OutputCommitCoordinatorEndpoint的RpcEndpointRef来询问Driver上的
// OutputcommitCoordinator，是否能够将输出提交到HDFS。
// OutputCommitCoordinator用于判定给定Stage的分区任务是否有权限将输出提交到HDFS，并对同一分区任务的多次任务尝试（TaskAttempt）进行协调
private[spark] class OutputCommitCoordinator(conf: SparkConf, isDriver: Boolean) extends Logging {

  // Initialized by SparkEnv
  var coordinatorRef: Option[RpcEndpointRef] = None

  private type StageId = Int
  private type PartitionId = Int
  private type TaskAttemptNumber = Int

  private val NO_AUTHORIZED_COMMITTER: TaskAttemptNumber = -1

  /**
   * Map from active stages's id => partition id => task attempt with exclusive lock on committing
   * output for that partition.
   *
   * Entries are added to the top-level map when stages start and are removed they finish
   * (either successfully or unsuccessfully).
   *
   * Access to this map should be guarded by synchronizing on the OutputCommitCoordinator instance.
   */
    // 缓存Stage的各个分区的任务尝试。
  private val authorizedCommittersByStage = mutable.Map[StageId, Array[TaskAttemptNumber]]()

  /**
   * Returns whether the OutputCommitCoordinator's internal data structures are all empty.
   */
  // 用于判断authorizedCommittersByStage是否为空
  def isEmpty: Boolean = {
    authorizedCommittersByStage.isEmpty
  }

  /**
   * Called by tasks to ask whether they can commit their output to HDFS.
   *
   * If a task attempt has been authorized to commit, then all other attempts to commit the same
   * task will be denied.  If the authorized task attempt fails (e.g. due to its executor being
   * lost), then a subsequent task attempt may be authorized to commit its output.
   *
   * @param stage the stage number
   * @param partition the partition number
   * @param attemptNumber how many times this task has been attempted
   *                      (see [[TaskContext.attemptNumber()]])
   * @return true if this task is authorized to commit, false otherwise
   */
  // 用于向OutputCommitCoordinatorEndpoint发送AskPermissionToCommitOutput，并根据
  // OutputCommitCoordinatorEndpoint的响应确认是否有权限将stage的指定分区的输出提交到HDFS上
  def canCommit(
      stage: StageId,
      partition: PartitionId,
      attemptNumber: TaskAttemptNumber): Boolean = {
    val msg = AskPermissionToCommitOutput(stage, partition, attemptNumber)
    coordinatorRef match {
      case Some(endpointRef) =>
        endpointRef.askWithRetry[Boolean](msg)
      case None =>
        logError(
          "canCommit called after coordinator was stopped (is SparkEnv shutdown in progress)?")
        false
    }
  }

  /**
   * Called by the DAGScheduler when a stage starts.
   *
   * @param stage the stage id.
   * @param maxPartitionId the maximum partition id that could appear in this stage's tasks (i.e.
   *                       the maximum possible value of `context.partitionId`).
   */
  // 用于启动给定Stage的输出提交到HDFS的协调机制，其实质为创建给定Stage的对应TaskAttemptNumber数组，并将
  // TaskAttemptNumber数组中的所有TaskAttemptNumber置为NO_AUTHORIZED_COMMITTER
  private[scheduler] def stageStart(
      stage: StageId,
      maxPartitionId: Int): Unit = {
    val arr = new Array[TaskAttemptNumber](maxPartitionId + 1)
    java.util.Arrays.fill(arr, NO_AUTHORIZED_COMMITTER)
    synchronized {
      authorizedCommittersByStage(stage) = arr
    }
  }

  // Called by DAGScheduler
  // 用于停止给定Stage的输出提交到HDFS的协调机制，其实质为创建给定Stage的对应TaskAttemptNumber数组从authrizedCommittersByStage中删除
  private[scheduler] def stageEnd(stage: StageId): Unit = synchronized {
    authorizedCommittersByStage.remove(stage)
  }

  // Called by DAGScheduler
  // 给定Stage的指定分区的任务执行完成后将调用taskCompleted方法
  private[scheduler] def taskCompleted(
      stage: StageId,
      partition: PartitionId,
      attemptNumber: TaskAttemptNumber,
      reason: TaskEndReason): Unit = synchronized {
    val authorizedCommitters = authorizedCommittersByStage.getOrElse(stage, {
      logDebug(s"Ignoring task completion for completed stage")
      return
    })
    reason match {
      // 任务执行成功
      case Success =>
      // The task output has been committed successfully

      // 任务提交被决绝
      case denied: TaskCommitDenied =>
        logInfo(s"Task was denied committing, stage: $stage, partition: $partition, " +
          s"attempt: $attemptNumber")
      // 其他失败原因： 此时需要将给定Stage的对应TaskAttemptNumber数组中指定分区的值修改为NO_AUTHORIZED_COMMITTER，
      // 以便之后的任务尝试能够有权限提交
      case otherReason =>
        if (authorizedCommitters(partition) == attemptNumber) {
          logDebug(s"Authorized committer (attemptNumber=$attemptNumber, stage=$stage, " +
            s"partition=$partition) failed; clearing lock")
          authorizedCommitters(partition) = NO_AUTHORIZED_COMMITTER
        }
    }
  }

  def stop(): Unit = synchronized {
    if (isDriver) {
      // 通过向OutputCommitCoordinatorEndpoint发送StopCoordinator消息以停止OutputCommitCoordinatorEndpoint，
      coordinatorRef.foreach(_ send StopCoordinator)
      coordinatorRef = None
      // 然后清空authorizedCommittersByStage
      authorizedCommittersByStage.clear()
    }
  }

  // Marked private[scheduler] instead of private so this can be mocked in tests
  // 用于判断给定的任务尝试是否有权限将给定Stage的指定分区的数据提交到HDFS
  private[scheduler] def handleAskPermissionToCommit(
      stage: StageId,
      partition: PartitionId,
      attemptNumber: TaskAttemptNumber): Boolean = synchronized {
    // 从authorizedCommittersByStage缓存中找到给定Stage的指定分区的TaskAttemptNumber
    authorizedCommittersByStage.get(stage) match {
      case Some(authorizedCommitters) =>
        authorizedCommitters(partition) match {
          // 当前是首次提交给定Stage的指定分区的输出，因此按照第一提交者胜利(first committer wins)策略，给定
          // TaskAttemptNumber（即attemptNumber）有权限将给定Stage的指定分区的输出提交到HDFS。为了告诉后来的
          // 任务尝试"已经有人捷足先登"，还需要将给定分区的索引与attemptNumber的关系保存到TaskAttempNumber数组中。
          case NO_AUTHORIZED_COMMITTER =>
            logDebug(s"Authorizing attemptNumber=$attemptNumber to commit for stage=$stage, " +
              s"partition=$partition")
            authorizedCommitters(partition) = attemptNumber
            true
            // 如果获取的attemptNumber不等于NO_AUTHORIZED_COMMITTER，则说明之前已经有任务尝试将给定Stage的值定分区的输出提交到HDFS，
            // 因此按照第一提价者胜利策略,给定TaskAttemptNumber没有权限将给定Stage的指定分区的输出提交到HDFS。
          case existingCommitter =>
            logDebug(s"Denying attemptNumber=$attemptNumber to commit for stage=$stage, " +
              s"partition=$partition; existingCommitter = $existingCommitter")
            false
        }
      case None =>
        logDebug(s"Stage $stage has completed, so not allowing attempt number $attemptNumber of" +
          s"partition $partition to commit")
        false
    }
  }
}

private[spark] object OutputCommitCoordinator {

  // This endpoint is used only for RPC
  private[spark] class OutputCommitCoordinatorEndpoint(
      override val rpcEnv: RpcEnv, outputCommitCoordinator: OutputCommitCoordinator)
    extends RpcEndpoint with Logging {

    logDebug("init") // force eager creation of logger

    override def receive: PartialFunction[Any, Unit] = {
      case StopCoordinator =>
        logInfo("OutputCommitCoordinator stopped!")
        stop()
    }

    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      case AskPermissionToCommitOutput(stage, partition, attemptNumber) =>
        context.reply(
          outputCommitCoordinator.handleAskPermissionToCommit(stage, partition, attemptNumber))
    }
  }
}
