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

package org.apache.spark.scheduler.cluster

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Future

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.deploy.{ApplicationDescription, Command}
import org.apache.spark.deploy.client.{StandaloneAppClient, StandaloneAppClientListener}
import org.apache.spark.internal.Logging
import org.apache.spark.launcher.{LauncherBackend, SparkAppHandle}
import org.apache.spark.rpc.RpcEndpointAddress
import org.apache.spark.scheduler._
import org.apache.spark.util.Utils

/**
 * A [[SchedulerBackend]] implementation for Spark's standalone cluster manager.
 */
// SchedulerBackend存在的最大价值是代理TaskSchedulerImpl,将集群分配给Application的资源进一步分配给Task。
private[spark] class StandaloneSchedulerBackend(
    scheduler: TaskSchedulerImpl,
    sc: SparkContext,
    masters: Array[String])
  extends CoarseGrainedSchedulerBackend(scheduler, sc.env.rpcEnv)
  with StandaloneAppClientListener
  with Logging {

  // 即StandaloneAppClient
  private var client: StandaloneAppClient = null
  // 标记StandaloneSchedulerBackend是否正在停止
  private val stopping = new AtomicBoolean(false)
  // 即运行器后端接口LauncerBackend
  private val launcherBackend = new LauncherBackend() {
    override protected def onStopRequest(): Unit = stop(SparkAppHandle.State.KILLED)
  }

  @volatile var shutdownCallback: StandaloneSchedulerBackend => Unit = _
  // Application的ID
  @volatile private var appId: String = _

  // 使用Java的信号量实现的栅栏，用于等待Application向Master注册完成后，将Application的当前状态（此时正在运行，即RUNNING）告知LauncerServer
  private val registrationBarrier = new Semaphore(0)

  // Application可以申请获得的最大内核数，如果设置了maxCores
  private val maxCores = conf.getOption("spark.cores.max").map(_.toInt)
  // Application期望获得的内核数，如果设置了maxCores，则为maxCores,否则为0。
  private val totalExpectedCores = maxCores.getOrElse(0)

  override def start() {
    // 创建并注册DriverEndpoint
    super.start()
    // 调用LauncherBackend的connect方法与LauncerServer建立连接
    launcherBackend.connect()

    // The endpoint for executors to talk to us
    // 生成Driver URL,格式为spark://$name@${rpcAddress.host}:${rpcAddress.port}。Executor将通过此URL与Driver通信
    val driverUrl = RpcEndpointAddress(
      sc.conf.get("spark.driver.host"),
      sc.conf.get("spark.driver.port").toInt,
      CoarseGrainedSchedulerBackend.ENDPOINT_NAME).toString
    // 拼接参数列表args
    val args = Seq(
      "--driver-url", driverUrl,
      "--executor-id", "{{EXECUTOR_ID}}",
      "--hostname", "{{HOSTNAME}}",
      "--cores", "{{CORES}}",
      "--app-id", "{{APP_ID}}",
      "--worker-url", "{{WORKER_URL}}")
    // 获取额外的Java参数extraJavaOpts
    val extraJavaOpts = sc.conf.getOption("spark.executor.extraJavaOptions")
      .map(Utils.splitCommandString).getOrElse(Seq.empty)
    // 额外的类路径classPathEntries
    val classPathEntries = sc.conf.getOption("spark.executor.extraClassPath")
      .map(_.split(java.io.File.pathSeparator).toSeq).getOrElse(Nil)
    // 额外的库路径libraryPathEntries
    val libraryPathEntries = sc.conf.getOption("spark.executor.extraLibraryPath")
      .map(_.split(java.io.File.pathSeparator).toSeq).getOrElse(Nil)

    // When testing, expose the parent class path to the child. This is processed by
    // compute-classpath.{cmd,sh} and makes all needed jars available to child processes
    // when the assembly is built with the "*-provided" profiles enabled.
    val testingClassPath =
      if (sys.props.contains("spark.testing")) {
        sys.props("java.class.path").split(java.io.File.pathSeparator).toSeq
      } else {
        Nil
      }

    // Start executors with a few necessary configs for registering with the scheduler
    // 从SparkConf中获取需要传递给Executor用于启动的配置sparkJavaOpts。
    val sparkJavaOpts = Utils.sparkJavaOpts(conf, SparkConf.isExecutorStartupConf)
    // 将sparkJavaOpts与extraJavaOpts合并到javaOpts中
    val javaOpts = sparkJavaOpts ++ extraJavaOpts
    // 创建Command对象。样例类Command定义了执行Executor的命令。
    val command = Command("org.apache.spark.executor.CoarseGrainedExecutorBackend",
      args, sc.executorEnvs, classPathEntries ++ testingClassPath, libraryPathEntries, javaOpts)
    // 获取Spark UI的http地址appUIAddress
    val appUIAddress = sc.ui.map(_.appUIAddress).getOrElse("")
    // 获取每个Executor分配的内核数
    val coresPerExecutor = conf.getOption("spark.executor.cores").map(_.toInt)
    // If we're using dynamic allocation, set our initial executor limit to 0 for now.
    // ExecutorAllocationManager will send the real initial limit to the Master later.
    // Executor的初始限制initalExecutorLimit（如果启用了动态分配Executor，那么initialExecutorLimit被设置为0，
    // ExecutorAllocationManager之后会将真实的初始限制值传递给Master）
    val initialExecutorLimit =
      if (Utils.isDynamicAllocationEnabled(conf)) {
        Some(0)
      } else {
        None
      }
    // 创建Application描述信息
    val appDesc = new ApplicationDescription(sc.appName, maxCores, sc.executorMemory, command,
      appUIAddress, sc.eventLogDir, sc.eventLogCodec, coresPerExecutor, initialExecutorLimit)
    // 创建并启动StandaloneAppClient。StandaloneAppClient的start方法将创建ClientEndpoint，并向SparkContext的SparkEnv
    // 的RpcEnv注册ClientEndpoint，进而引起对ClientEndpoint的启动和向Master注册Application.
    client = new StandaloneAppClient(sc.env.rpcEnv, masters, appDesc, this, conf)
    client.start()
    // 调用LauncherBackend的setState方法向LauncherServer传递应用已经提交(SUBMITTED)的状态
    launcherBackend.setState(SparkAppHandle.State.SUBMITTED)
    // 等待注册Application完成。当注册Application成功之后，Master将向ClientEndpoint发送RegisteredApplication消息，
    // 进而调用StandaloneSchedulerBackend的connected方法释放信号，这样waitForRegistration方法将可以获得信号量。
    waitForRegistration()
    // 向LauncherServer传递应用正在运行（RUNNING）的状态
    launcherBackend.setState(SparkAppHandle.State.RUNNING)
  }

  override def stop(): Unit = {
    stop(SparkAppHandle.State.FINISHED)
  }

  override def connected(appId: String) {
    logInfo("Connected to Spark cluster with app ID " + appId)
    this.appId = appId
    notifyContext()
    launcherBackend.setAppId(appId)
  }

  override def disconnected() {
    notifyContext()
    if (!stopping.get) {
      logWarning("Disconnected from Spark cluster! Waiting for reconnection...")
    }
  }

  override def dead(reason: String) {
    notifyContext()
    if (!stopping.get) {
      launcherBackend.setState(SparkAppHandle.State.KILLED)
      logError("Application has been killed. Reason: " + reason)
      try {
        scheduler.error(reason)
      } finally {
        // Ensure the application terminates, as we can no longer run jobs.
        sc.stopInNewThread()
      }
    }
  }

  override def executorAdded(fullId: String, workerId: String, hostPort: String, cores: Int,
    memory: Int) {
    logInfo("Granted executor ID %s on hostPort %s with %d cores, %s RAM".format(
      fullId, hostPort, cores, Utils.megabytesToString(memory)))
  }

  override def executorRemoved(
      fullId: String, message: String, exitStatus: Option[Int], workerLost: Boolean) {
    val reason: ExecutorLossReason = exitStatus match {
      case Some(code) => ExecutorExited(code, exitCausedByApp = true, message)
      case None => SlaveLost(message, workerLost = workerLost)
    }
    logInfo("Executor %s removed: %s".format(fullId, message))
    removeExecutor(fullId.split("/")(1), reason)
  }

  override def sufficientResourcesRegistered(): Boolean = {
    totalCoreCount.get() >= totalExpectedCores * minRegisteredRatio
  }

  override def applicationId(): String =
    Option(appId).getOrElse {
      logWarning("Application ID is not initialized yet.")
      super.applicationId
    }

  /**
   * Request executors from the Master by specifying the total number desired,
   * including existing pending and running executors.
   *
   * @return whether the request is acknowledged.
   */
  // 调用StandaloneAppClient的requestTotalExecutors方法向Master请求所需的所有资源。
  protected override def doRequestTotalExecutors(requestedTotal: Int): Future[Boolean] = {
    Option(client) match {
      case Some(c) => c.requestTotalExecutors(requestedTotal)
      case None =>
        logWarning("Attempted to request executors before driver fully initialized.")
        Future.successful(false)
    }
  }

  /**
   * Kill the given list of executors through the Master.
   * @return whether the kill request is acknowledged.
   */
  protected override def doKillExecutors(executorIds: Seq[String]): Future[Boolean] = {
    Option(client) match {
      case Some(c) => c.killExecutors(executorIds)
      case None =>
        logWarning("Attempted to kill executors before driver fully initialized.")
        Future.successful(false)
    }
  }

  private def waitForRegistration() = {
    registrationBarrier.acquire()
  }

  private def notifyContext() = {
    registrationBarrier.release()
  }

  // 首先调用父类CoarseGrainedSchedulerBackend的stop方法，然后调用StandaloneAppClient的stop方法停止StandaloneAppClient
  // 最后回调关闭函数。
  private def stop(finalState: SparkAppHandle.State): Unit = {
    if (stopping.compareAndSet(false, true)) {
      try {
        super.stop()
        client.stop()

        val callback = shutdownCallback
        if (callback != null) {
          callback(this)
        }
      } finally {
        launcherBackend.setState(finalState)
        launcherBackend.close()
      }
    }
  }

}
