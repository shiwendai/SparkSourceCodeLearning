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

package org.apache.spark.deploy.worker

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.{Date, Locale, UUID}
import java.util.concurrent._
import java.util.concurrent.{Future => JFuture, ScheduledFuture => JScheduledFuture}

import scala.collection.mutable.{HashMap, HashSet, LinkedHashMap}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Random, Success}
import scala.util.control.NonFatal

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.deploy.{Command, ExecutorDescription, ExecutorState}
import org.apache.spark.deploy.DeployMessages._
import org.apache.spark.deploy.ExternalShuffleService
import org.apache.spark.deploy.master.{DriverState, Master}
import org.apache.spark.deploy.worker.ui.WorkerWebUI
import org.apache.spark.internal.Logging
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.rpc._
import org.apache.spark.util.{ThreadUtils, Utils}

private[deploy] class Worker(
    override val rpcEnv: RpcEnv,
    webUiPort: Int,
    cores: Int,
    memory: Int,
    masterRpcAddresses: Array[RpcAddress],
    endpointName: String, // Worker注册到RpcEnv的名称
    workDirPath: String = null, // 表示Worker的工作目录
    val conf: SparkConf,
    val securityMgr: SecurityManager)
  extends ThreadSafeRpcEndpoint with Logging {

  private val host = rpcEnv.address.host
  private val port = rpcEnv.address.port

  Utils.checkHost(host, "Expected hostname")
  assert (port > 0)

  // A scheduled executor used to send messages at the specified time.
  // 用于发送消息的调度执行器
  private val forwordMessageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("worker-forward-message-scheduler")

  // A separated thread to clean up the workDir. Used to provide the implicit parameter of `Future`
  // methods.
  // 用于清理Worker的工作目录的线程执行器
  private val cleanupThreadExecutor = ExecutionContext.fromExecutorService(
    ThreadUtils.newDaemonSingleThreadExecutor("worker-cleanup-thread"))

  // For worker and executor IDs
  private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
  // Send a heartbeat every (heartbeat timeout) / 4 milliseconds
  // 向Master发送心跳的时间间隔，默认60/4=15s
  private val HEARTBEAT_MILLIS = conf.getLong("spark.worker.timeout", 60) * 1000 / 4

  // Model retries to connect to the master, after Hadoop's model.
  // The first six attempts to reconnect are in shorter intervals (between 5 and 15 seconds)
  // Afterwards, the next 10 attempts are between 30 and 90 seconds.
  // A bit of randomness is introduced so that not all of the workers attempt to reconnect at
  // the same time.
  // 代表连接Master的前六次尝试
  private val INITIAL_REGISTRATION_RETRIES = 6
  // 代表连接Master最多有16次尝试
  private val TOTAL_REGISTRATION_RETRIES = INITIAL_REGISTRATION_RETRIES + 10
  // 为了确保尝试的时间间隔不至于过小的下边界
  private val FUZZ_MULTIPLIER_INTERVAL_LOWER_BOUND = 0.500
  // 加入随机数是为了避免各个Worker在同一时间发送心跳
  private val REGISTRATION_RETRY_FUZZ_MULTIPLIER = {
    val randomNumberGenerator = new Random(UUID.randomUUID.getMostSignificantBits)
    randomNumberGenerator.nextDouble + FUZZ_MULTIPLIER_INTERVAL_LOWER_BOUND
  }
  private val INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS = (math.round(10 *
    REGISTRATION_RETRY_FUZZ_MULTIPLIER))
  private val PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS = (math.round(60
    * REGISTRATION_RETRY_FUZZ_MULTIPLIER))

  // 是否对旧的Application产生的文件夹及文件进行清理
  private val CLEANUP_ENABLED = conf.getBoolean("spark.worker.cleanup.enabled", false)
  // How often worker will clean up old app folders
  // 对旧的Application产生的文件夹及文件进行清理的时间间隔
  private val CLEANUP_INTERVAL_MILLIS =
    conf.getLong("spark.worker.cleanup.interval", 60 * 30) * 1000
  // TTL for app folders/data;  after TTL expires it will be cleaned up
  // Application产生的文件夹及文件保留的时间，默认为7天
  private val APP_DATA_RETENTION_SECONDS =
    conf.getLong("spark.worker.cleanup.appDataTtl", 7 * 24 * 3600)

  private val testing: Boolean = sys.props.contains("spark.testing")
  // Master的RpcEndpointRef
  private var master: Option[RpcEndpointRef] = None
  // 处于激活状态的Master的URL
  private var activeMasterUrl: String = ""
  // 处于激活状态的Master的WebUI的URL
  private[worker] var activeMasterWebUiUrl : String = ""
  // Worker的WebUI的URL
  private var workerWebUiUrl: String = ""
  // Worker的URL，格式为spark://$name@${rpcAddress.host}:${rpcAddress.port}
  private val workerUri = RpcEndpointAddress(rpcEnv.address, endpointName).toString
  // 标记Worker是否已经注册到Master
  private var registered = false
  // 标记Worker是否已经连接到Master
  private var connected = false
  // Worker的身份标识
  private val workerId = generateWorkerId()
  // 环境变量SPARK_HOME的值
  private val sparkHome =
    if (testing) {
      assert(sys.props.contains("spark.test.home"), "spark.test.home is not set!")
      new File(sys.props("spark.test.home"))
    } else {
      new File(sys.env.get("SPARK_HOME").getOrElse("."))
    }

  // 由java.io.File表示的Worker的工作目录
  var workDir: File = null
  // 已经完成的Executor的身份标识与ExecutorRunner之间的映射关系
  val finishedExecutors = new LinkedHashMap[String, ExecutorRunner]
  // Driver的身份标识与DriverRunner之间的映射关系
  val drivers = new HashMap[String, DriverRunner]
  // Executor的身份标识与ExecutorRunner之间的映射关系
  val executors = new HashMap[String, ExecutorRunner]
  // 已完成的Driver的身份标识与DriverRunner之间的映射关系
  val finishedDrivers = new LinkedHashMap[String, DriverRunner]
  // 已完成的Application的ID与对应的目录集合之间的映射关系
  val appDirectories = new HashMap[String, Seq[String]]
  // 已完成的Application的ID的集合
  val finishedApps = new HashSet[String]

  // 保留的Executor的数量
  val retainedExecutors = conf.getInt("spark.worker.ui.retainedExecutors",
    WorkerWebUI.DEFAULT_RETAINED_EXECUTORS)
  // 保留的Driver的数量
  val retainedDrivers = conf.getInt("spark.worker.ui.retainedDrivers",
    WorkerWebUI.DEFAULT_RETAINED_DRIVERS)

  // The shuffle service is not actually started unless configured.
  // 外部的Shuffle服务。之前介绍过外部Shuffle服务的客户端ExternalShuffleClient.
  // 这里则是外部Shuffle服务的服务端实现类ExternalShuffleService.这里虽然创建了
  // ExternalShuffleService，但是只有配置了外部的Shuffle服务时，才会启动它。
  private val shuffleService = new ExternalShuffleService(conf, securityMgr)

  // Worker的公共地址，如果设置了环境变量SPARK_PUBLIC_DNS,则为环境变量SPARK_PUBLIC_DNS的值，否则为host
  private val publicAddress = {
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else host
  }

  // Worker的WebUI
  private var webUi: WorkerWebUI = null

  // 连接尝试次数的计数器
  private var connectionAttemptCount = 0

  private val metricsSystem = MetricsSystem.createMetricsSystem("worker", conf, securityMgr)
  private val workerSource = new WorkerSource(this)

  // 由于Worker向Master进行注册的过程是异步的，此变量保存线程返回的java.util.concurrent.Future
  private var registerMasterFutures: Array[JFuture[_]] = null
  // Worker向Master进行注册重试的定时器
  private var registrationRetryTimer: Option[JScheduledFuture[_]] = None

  // A thread pool for registering with masters. Because registering with a master is a blocking
  // action, this thread pool must be able to create "masterRpcAddresses.size" threads at the same
  // time so that we can register with all masters.
  // Worker向Master进行注册的线程池。此线程池的大小与Master节点的数量一样
  private val registerMasterThreadPool = ThreadUtils.newDaemonCachedThreadPool(
    "worker-register-master-threadpool",
    masterRpcAddresses.length // Make sure we can register with all masters at the same time
  )

  // 当前Worker已经使用的内核数
  var coresUsed = 0
  // 当前Worker已经使用的内存大小
  var memoryUsed = 0

  def coresFree: Int = cores - coresUsed
  def memoryFree: Int = memory - memoryUsed

  private def createWorkDir() {
    workDir = Option(workDirPath).map(new File(_)).getOrElse(new File(sparkHome, "work"))
    try {
      // This sporadically fails - not sure why ... !workDir.exists() && !workDir.mkdirs()
      // So attempting to create and then check if directory was created or not.
      workDir.mkdirs()
      if ( !workDir.exists() || !workDir.isDirectory) {
        logError("Failed to create work directory " + workDir)
        System.exit(1)
      }
      assert (workDir.isDirectory)
    } catch {
      case e: Exception =>
        logError("Failed to create work directory " + workDir, e)
        System.exit(1)
    }
  }

  override def onStart() {
    assert(!registered)
    logInfo("Starting Spark worker %s:%d with %d cores, %s RAM".format(
      host, port, cores, Utils.megabytesToString(memory)))
    logInfo(s"Running Spark version ${org.apache.spark.SPARK_VERSION}")
    logInfo("Spark home: " + sparkHome)
    createWorkDir()
    shuffleService.startIfEnabled()
    webUi = new WorkerWebUI(this, workDir, webUiPort)
    webUi.bind()

    workerWebUiUrl = s"http://$publicAddress:${webUi.boundPort}"
    registerWithMaster()

    metricsSystem.registerSource(workerSource)
    metricsSystem.start()
    // Attach the worker metrics servlet handler to the web ui after the metrics system is started.
    metricsSystem.getServletHandlers.foreach(webUi.attachHandler)
  }

  private def changeMaster(masterRef: RpcEndpointRef, uiUrl: String) {
    // activeMasterUrl it's a valid Spark url since we receive it from master.
    activeMasterUrl = masterRef.address.toSparkURL
    activeMasterWebUiUrl = uiUrl
    master = Some(masterRef)
    connected = true
    if (conf.getBoolean("spark.ui.reverseProxy", false)) {
      logInfo(s"WorkerWebUI is available at $activeMasterWebUiUrl/proxy/$workerId")
    }
    // Cancel any outstanding re-registration attempts because we found a new master
    cancelLastRegistrationRetry()
  }

  // 此方法用于向所有的Master注册当前Worker，只有处于领导状态的Master来处理Worker的注册.
  // 此方法实际上是遍历masterRpcAddresses中的每个Master的RPC地址，然后向registerMsterThreadPool提交向Master注册Worker的任务。
  // 此任务实际是调用重载的registerWithMaster方法用于完成最后的注册
  private def tryRegisterAllMasters(): Array[JFuture[_]] = {
    masterRpcAddresses.map { masterAddress =>
      registerMasterThreadPool.submit(new Runnable {
        override def run(): Unit = {
          try {
            logInfo("Connecting to master " + masterAddress + "...")
            val masterEndpoint = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
            registerWithMaster(masterEndpoint)
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
          }
        }
      })
    }
  }

  /**
   * Re-register with the master because a network failure or a master failure has occurred.
   * If the re-registration attempt threshold is exceeded, the worker exits with error.
   * Note that for thread-safety this should only be called from the rpcEndpoint.
   */
  // reregisterWithMaster方法用于在Worker的registered为false时，重新向Master注册
  private def reregisterWithMaster(): Unit = {
    Utils.tryOrExit {
      connectionAttemptCount += 1
      if (registered) {
        cancelLastRegistrationRetry()
      } else if (connectionAttemptCount <= TOTAL_REGISTRATION_RETRIES) {
        logInfo(s"Retrying connection to master (attempt # $connectionAttemptCount)")
        /**
         * Re-register with the active master this worker has been communicating with. If there
         * is none, then it means this worker is still bootstrapping and hasn't established a
         * connection with a master yet, in which case we should re-register with all masters.
         *
         * It is important to re-register only with the active master during failures. Otherwise,
         * if the worker unconditionally attempts to re-register with all masters, the following
         * race condition may arise and cause a "duplicate worker" error detailed in SPARK-4592:
         *
         *   (1) Master A fails and Worker attempts to reconnect to all masters
         *   (2) Master B takes over and notifies Worker
         *   (3) Worker responds by registering with Master B
         *   (4) Meanwhile, Worker's previous reconnection attempt reaches Master B,
         *       causing the same Worker to register with Master B twice
         *
         * Instead, if we only register with the known active master, we can assume that the
         * old master must have died because another master has taken over. Note that this is
         * still not safe if the old master recovers within this interval, but this is a much
         * less likely scenario.
         */
        master match {
          case Some(masterRef) =>
            // registered == false && master != None means we lost the connection to master, so
            // masterRef cannot be used and we need to recreate it again. Note: we must not set
            // master to None due to the above comments.
            if (registerMasterFutures != null) {
              registerMasterFutures.foreach(_.cancel(true))
            }
            val masterAddress = masterRef.address
            registerMasterFutures = Array(registerMasterThreadPool.submit(new Runnable {
              override def run(): Unit = {
                try {
                  logInfo("Connecting to master " + masterAddress + "...")
                  val masterEndpoint = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
                  registerWithMaster(masterEndpoint)
                } catch {
                  case ie: InterruptedException => // Cancelled
                  case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
                }
              }
            }))
          case None =>
            if (registerMasterFutures != null) {
              registerMasterFutures.foreach(_.cancel(true))
            }
            // We are retrying the initial registration
            registerMasterFutures = tryRegisterAllMasters()
        }
        // We have exceeded the initial registration retry threshold
        // All retries from now on should use a higher interval
        if (connectionAttemptCount == INITIAL_REGISTRATION_RETRIES) {
          registrationRetryTimer.foreach(_.cancel(true))
          registrationRetryTimer = Some(
            forwordMessageScheduler.scheduleAtFixedRate(new Runnable {
              override def run(): Unit = Utils.tryLogNonFatalError {
                self.send(ReregisterWithMaster)
              }
            }, PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS,
              PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS,
              TimeUnit.SECONDS))
        }
      } else {
        logError("All masters are unresponsive! Giving up.")
        System.exit(1)
      }
    }
  }

  /**
   * Cancel last registeration retry, or do nothing if no retry
   */
  private def cancelLastRegistrationRetry(): Unit = {
    if (registerMasterFutures != null) {
      registerMasterFutures.foreach(_.cancel(true))
      registerMasterFutures = null
    }
    registrationRetryTimer.foreach(_.cancel(true))
    registrationRetryTimer = None
  }

  // 此方法用于向Master注册Worker
  private def registerWithMaster() {
    // onDisconnected may be triggered multiple times, so don't attempt registration
    // if there are outstanding registration attempts scheduled.
    registrationRetryTimer match {
      case None =>
        registered = false
        registerMasterFutures = tryRegisterAllMasters() // 向所有的Master注册当前Worker，只有处于领导状态的Master来处理Worker的注册
        connectionAttemptCount = 0
        // 创建定时任务registrationRetryTimer，按照常量INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS
        // 指定的间隔向Worker自身发送ReregisterWithMaster消息。
        registrationRetryTimer = Some(forwordMessageScheduler.scheduleAtFixedRate(
          new Runnable {
            override def run(): Unit = Utils.tryLogNonFatalError {
              Option(self).foreach(_.send(ReregisterWithMaster))
            }
          },
          INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS,
          INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS,
          TimeUnit.SECONDS))
      case Some(_) =>
        logInfo("Not spawning another attempt to register with the master, since there is an" +
          " attempt scheduled already.")
    }
  }

  // 此方法主要向Master发送RegisterWorker消息，并对返回的结果使用handleRegisterResponse方法处理
  private def registerWithMaster(masterEndpoint: RpcEndpointRef): Unit = {
    masterEndpoint.ask[RegisterWorkerResponse](RegisterWorker(
      workerId, host, port, self, cores, memory, workerWebUiUrl))
      .onComplete {
        // This is a very fast action so we can use "ThreadUtils.sameThread"
        case Success(msg) =>
          Utils.tryLogNonFatalError {
            handleRegisterResponse(msg)
          }
        case Failure(e) =>
          logError(s"Cannot register with master: ${masterEndpoint.address}", e)
          System.exit(1)
      }(ThreadUtils.sameThread)
  }

  // Master将给Worker回复三种可能的消息
  private def handleRegisterResponse(msg: RegisterWorkerResponse): Unit = synchronized {
    msg match {

        // 处理RegisteredWorker消息。如果Master回复了此消息，说明Worker已经成功在Master上注册
      case RegisteredWorker(masterRef, masterWebUiUrl) =>
        logInfo("Successfully registered with master " + masterRef.address.toSparkURL)
        registered = true // 将registered守则为true
        // 调用changeMaster方法修改激活的Master的信息。changeMaster方法还调用cancelLastRegistrationRetry方法取消注册尝试
        // （即取消了调用tryRegisterAllMasters方法产生的注册任务和registrationRetryTimer）
        changeMaster(masterRef, masterWebUiUrl)
        // 定时向Master发送心跳信息
        forwordMessageScheduler.scheduleAtFixedRate(new Runnable {
          override def run(): Unit = Utils.tryLogNonFatalError {
            self.send(SendHeartbeat)
          }
        }, 0, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS)

        // 如果允许清理工作目录，向forwordMessageScheduler提交以CLEANUP_INTERVAL_MILLIS作为间隔向
        // Worker自身发送WorkDirCleanup消息的定时任务
        if (CLEANUP_ENABLED) {
          logInfo(
            s"Worker cleanup enabled; old application directories will be deleted in: $workDir")
          forwordMessageScheduler.scheduleAtFixedRate(new Runnable {
            override def run(): Unit = Utils.tryLogNonFatalError {
              self.send(WorkDirCleanup)
            }
          }, CLEANUP_INTERVAL_MILLIS, CLEANUP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }


        val execs = executors.values.map { e =>
          new ExecutorDescription(e.appId, e.execId, e.cores, e.state)
        }
        // 向Master发送WorkerLatestState消息
        masterRef.send(WorkerLatestState(workerId, execs.toList, drivers.keys.toSeq))

        // 处理RegisterWorkerFailed消息。如果Master回复了此消息，说明Worker在Master上注册失败了。
        // 如果Worker还未向任何Master节点注册成功，那么退出Worker进程
      case RegisterWorkerFailed(message) =>
        if (!registered) {
          logError("Worker registration failed: " + message)
          System.exit(1)
        }

        // 处理MasterInStandby消息。如果Master回复了此消息，说明Master处于Standby的状态，并不是领导身份，此时Worker不做任何处理
      case MasterInStandby =>
        // Ignore. Master not yet ready.
    }
  }

  override def receive: PartialFunction[Any, Unit] = synchronized {
    case SendHeartbeat =>
      if (connected) { sendToMaster(Heartbeat(workerId, self)) }

    case WorkDirCleanup =>
      // Spin up a separate thread (in a future) to do the dir cleanup; don't tie up worker
      // rpcEndpoint.
      // Copy ids so that it can be used in the cleanup thread.
      val appIds = executors.values.map(_.appId).toSet
      val cleanupFuture = concurrent.Future {
        val appDirs = workDir.listFiles()
        if (appDirs == null) {
          throw new IOException("ERROR: Failed to list files in " + appDirs)
        }
        appDirs.filter { dir =>
          // the directory is used by an application - check that the application is not running
          // when cleaning up
          val appIdFromDir = dir.getName
          val isAppStillRunning = appIds.contains(appIdFromDir)
          dir.isDirectory && !isAppStillRunning &&
          !Utils.doesDirectoryContainAnyNewFiles(dir, APP_DATA_RETENTION_SECONDS)
        }.foreach { dir =>
          logInfo(s"Removing directory: ${dir.getPath}")
          Utils.deleteRecursively(dir)
        }
      }(cleanupThreadExecutor)

      cleanupFuture.onFailure {
        case e: Throwable =>
          logError("App dir cleanup failed: " + e.getMessage, e)
      }(cleanupThreadExecutor)

      // Master被选举为领导时，将会向Worker发送MasterChanged消息
    case MasterChanged(masterRef, masterWebUiUrl) =>
      logInfo("Master has changed, new master is at " + masterRef.address.toSparkURL)
      changeMaster(masterRef, masterWebUiUrl) // 调用changeMaster方法改变激活的Master的相关信息
      // 通过遍历executors中的每个ExecutorRunner，利用ExecutorRunner的信息创建ExecutorDescription
      val execs = executors.values.
        map(e => new ExecutorDescription(e.appId, e.execId, e.cores, e.state))
      // Master接收到WorkerSchedulerStateResponse消息后将更新Worker的调度状态。
      masterRef.send(WorkerSchedulerStateResponse(workerId, execs.toList, drivers.keys.toSeq))

      // Master在处理Heartbeat消息时有可能向Worker发送ReconnectWorker消息，Worker的receive方法接收到
    // ReconnectWorker消息后只再次调用了registerWithMaster方法向Master注册Worker
    case ReconnectWorker(masterUrl) =>
      logInfo(s"Master with url $masterUrl requested this worker to reconnect.")
      registerWithMaster()

    case LaunchExecutor(masterUrl, appId, execId, appDesc, cores_, memory_) =>
      if (masterUrl != activeMasterUrl) {
        logWarning("Invalid Master (" + masterUrl + ") attempted to launch executor.")
      } else {
        try {
          logInfo("Asked to launch executor %s/%d for %s".format(appId, execId, appDesc.name))

          // Create the executor's working directory
          val executorDir = new File(workDir, appId + "/" + execId) // 在Worker的工作目录下创建Executor的工作目录
          if (!executorDir.mkdirs()) {
            throw new IOException("Failed to create directory " + executorDir)
          }

          // Create local dirs for the executor. These are passed to the executor via the
          // SPARK_EXECUTOR_DIRS environment variable, and deleted by the Worker when the
          // application finishes.
          // 给Executor创建本地目录，并将这些目录缓存到Worker的appDirectories属性中。这些目录将通过环境变量SPARK_EXECUTOR_DIRS
          // 传递给Executor，并在Application完成后有Worker删除。
          val appLocalDirs = appDirectories.getOrElse(appId,
            Utils.getOrCreateLocalRootDirs(conf).map { dir =>
              val appDir = Utils.createDirectory(dir, namePrefix = "executor")
              Utils.chmod700(appDir)
              appDir.getAbsolutePath()
            }.toSeq)
          appDirectories(appId) = appLocalDirs

          // 创建ExecutorRunner
          val manager = new ExecutorRunner(
            appId,
            execId,
            appDesc.copy(command = Worker.maybeUpdateSSLSettings(appDesc.command, conf)),
            cores_,
            memory_,
            self,
            workerId,
            host,
            webUi.boundPort,
            publicAddress,
            sparkHome,
            executorDir,
            workerUri,
            conf,
            appLocalDirs, ExecutorState.RUNNING)
          // 并将ExecutorRunner放入exeutors.
          executors(appId + "/" + execId) = manager
          // 启动ExecutorRunner
          manager.start()
          // 更新已经使用的内核数和已经使用的内存
          coresUsed += cores_
          memoryUsed += memory_
          // 向Master发送ExecutorStateChanged消息以跟新Executor的状态
          sendToMaster(ExecutorStateChanged(appId, execId, manager.state, None, None))
        } catch {
          case e: Exception =>
            logError(s"Failed to launch executor $appId/$execId for ${appDesc.name}.", e)
            if (executors.contains(appId + "/" + execId)) {
              executors(appId + "/" + execId).kill()
              executors -= appId + "/" + execId
            }
            sendToMaster(ExecutorStateChanged(appId, execId, ExecutorState.FAILED,
              Some(e.toString), None))
        }
      }

    case executorStateChanged @ ExecutorStateChanged(appId, execId, state, message, exitStatus) =>
      handleExecutorStateChanged(executorStateChanged)

    case KillExecutor(masterUrl, appId, execId) =>
      if (masterUrl != activeMasterUrl) {
        logWarning("Invalid Master (" + masterUrl + ") attempted to kill executor " + execId)
      } else {
        val fullId = appId + "/" + execId
        executors.get(fullId) match {
          case Some(executor) =>
            logInfo("Asked to kill executor " + fullId)
            executor.kill()
          case None =>
            logInfo("Asked to kill unknown executor " + fullId)
        }
      }

    case LaunchDriver(driverId, driverDesc) =>
      logInfo(s"Asked to launch driver $driverId")
      val driver = new DriverRunner( // // 创建DriverRunner
        conf,
        driverId,
        workDir,
        sparkHome,
        driverDesc.copy(command = Worker.maybeUpdateSSLSettings(driverDesc.command, conf)),
        self,
        workerUri,
        securityMgr)
      // 将Driver的身份标识与DriverRunner的关系放入drivers中缓存
      drivers(driverId) = driver
      // 启动DriverRunner
      driver.start()

      // 更新Worker已经使用的内核数和内存大小
      coresUsed += driverDesc.cores
      memoryUsed += driverDesc.mem

    case KillDriver(driverId) =>
      logInfo(s"Asked to kill driver $driverId")
      drivers.get(driverId) match {
        case Some(runner) =>
          runner.kill()
        case None =>
          logError(s"Asked to kill unknown driver $driverId")
      }

    case driverStateChanged @ DriverStateChanged(driverId, state, exception) =>
      handleDriverStateChanged(driverStateChanged)

    case ReregisterWithMaster =>
      reregisterWithMaster()

    case ApplicationFinished(id) =>
      finishedApps += id
      maybeCleanupApplication(id)
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RequestWorkerState =>
      context.reply(WorkerStateResponse(host, port, workerId, executors.values.toList,
        finishedExecutors.values.toList, drivers.values.toList,
        finishedDrivers.values.toList, activeMasterUrl, cores, memory,
        coresUsed, memoryUsed, activeMasterWebUiUrl))
  }

  override def onDisconnected(remoteAddress: RpcAddress): Unit = {
    if (master.exists(_.address == remoteAddress)) {
      logInfo(s"$remoteAddress Disassociated !")
      masterDisconnected()
    }
  }

  private def masterDisconnected() {
    logError("Connection to master failed! Waiting for master to reconnect...")
    connected = false
    registerWithMaster()
  }

  private def maybeCleanupApplication(id: String): Unit = {
    val shouldCleanup = finishedApps.contains(id) && !executors.values.exists(_.appId == id)
    if (shouldCleanup) {
      finishedApps -= id
      appDirectories.remove(id).foreach { dirList =>
        logInfo(s"Cleaning up local directories for application $id")
        dirList.foreach { dir =>
          Utils.deleteRecursively(new File(dir))
        }
      }
      shuffleService.applicationRemoved(id)
    }
  }

  /**
   * Send a message to the current master. If we have not yet registered successfully with any
   * master, the message will be dropped.
   */
  private def sendToMaster(message: Any): Unit = {
    master match {
      case Some(masterRef) => masterRef.send(message)
      case None =>
        logWarning(
          s"Dropping $message because the connection to master has not yet been established")
    }
  }

  private def generateWorkerId(): String = {
    "worker-%s-%s-%d".format(createDateFormat.format(new Date), host, port)
  }

  override def onStop() {
    cleanupThreadExecutor.shutdownNow()
    metricsSystem.report()
    cancelLastRegistrationRetry()
    forwordMessageScheduler.shutdownNow()
    registerMasterThreadPool.shutdownNow()
    executors.values.foreach(_.kill())
    drivers.values.foreach(_.kill())
    shuffleService.stop()
    webUi.stop()
    metricsSystem.stop()
  }

  private def trimFinishedExecutorsIfNecessary(): Unit = {
    // do not need to protect with locks since both WorkerPage and Restful server get data through
    // thread-safe RpcEndPoint
    if (finishedExecutors.size > retainedExecutors) {
      finishedExecutors.take(math.max(finishedExecutors.size / 10, 1)).foreach {
        case (executorId, _) => finishedExecutors.remove(executorId)
      }
    }
  }

  private def trimFinishedDriversIfNecessary(): Unit = {
    // do not need to protect with locks since both WorkerPage and Restful server get data through
    // thread-safe RpcEndPoint
    if (finishedDrivers.size > retainedDrivers) {
      finishedDrivers.take(math.max(finishedDrivers.size / 10, 1)).foreach {
        case (driverId, _) => finishedDrivers.remove(driverId)
      }
    }
  }

  private[worker] def handleDriverStateChanged(driverStateChanged: DriverStateChanged): Unit = {
    val driverId = driverStateChanged.driverId
    val exception = driverStateChanged.exception
    val state = driverStateChanged.state
    state match {
      case DriverState.ERROR =>
        logWarning(s"Driver $driverId failed with unrecoverable exception: ${exception.get}")
      case DriverState.FAILED =>
        logWarning(s"Driver $driverId exited with failure")
      case DriverState.FINISHED =>
        logInfo(s"Driver $driverId exited successfully")
      case DriverState.KILLED =>
        logInfo(s"Driver $driverId was killed by user")
      case _ =>
        logDebug(s"Driver $driverId changed state to $state")
    }
    sendToMaster(driverStateChanged)
    val driver = drivers.remove(driverId).get
    finishedDrivers(driverId) = driver
    trimFinishedDriversIfNecessary()
    memoryUsed -= driver.driverDesc.mem
    coresUsed -= driver.driverDesc.cores
  }

  // 此方法用于处理Executor的状态变化，handleExecutorStateChanged处理更新Worker中此Executor有关的缓存信息外，
  // 还向Master转发了ExecutorStateChanged消息。
  private[worker] def handleExecutorStateChanged(executorStateChanged: ExecutorStateChanged):
    Unit = {
    sendToMaster(executorStateChanged)
    val state = executorStateChanged.state
    if (ExecutorState.isFinished(state)) {
      val appId = executorStateChanged.appId
      val fullId = appId + "/" + executorStateChanged.execId
      val message = executorStateChanged.message
      val exitStatus = executorStateChanged.exitStatus
      executors.get(fullId) match {
        case Some(executor) =>
          logInfo("Executor " + fullId + " finished with state " + state +
            message.map(" message " + _).getOrElse("") +
            exitStatus.map(" exitStatus " + _).getOrElse(""))
          executors -= fullId
          finishedExecutors(fullId) = executor
          trimFinishedExecutorsIfNecessary()
          coresUsed -= executor.cores
          memoryUsed -= executor.memory
        case None =>
          logInfo("Unknown Executor " + fullId + " finished with state " + state +
            message.map(" message " + _).getOrElse("") +
            exitStatus.map(" exitStatus " + _).getOrElse(""))
      }
      maybeCleanupApplication(appId)
    }
  }
}

private[deploy] object Worker extends Logging {
  val SYSTEM_NAME = "sparkWorker"
  val ENDPOINT_NAME = "Worker"

  def main(argStrings: Array[String]) {
    Utils.initDaemon(log)
    // 创建SparkConf
    val conf = new SparkConf
    // 创建WorkerArguments以对执行main函数的参数进行解析，并将Spark属性配置文件中的以spark.开头的属性保存到SparkConf
    val args = new WorkerArguments(argStrings, conf)
    // 调用startRpcEnvAndEndpoint方法创建并启动Worker对象
    val rpcEnv = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, args.cores,
      args.memory, args.masters, args.workDir, conf = conf)
    rpcEnv.awaitTermination()
  }

  def startRpcEnvAndEndpoint(
      host: String,
      port: Int,
      webUiPort: Int,
      cores: Int,
      memory: Int,
      masterUrls: Array[String],
      workDir: String,
      workerNumber: Option[Int] = None,
      conf: SparkConf = new SparkConf): RpcEnv = {

    // The LocalSparkCluster runs multiple local sparkWorkerX RPC Environments
    val systemName = SYSTEM_NAME + workerNumber.map(_.toString).getOrElse("")
    val securityMgr = new SecurityManager(conf)
    val rpcEnv = RpcEnv.create(systemName, host, port, conf, securityMgr)
    val masterAddresses = masterUrls.map(RpcAddress.fromSparkURL(_))
    rpcEnv.setupEndpoint(ENDPOINT_NAME, new Worker(rpcEnv, webUiPort, cores, memory,
      masterAddresses, ENDPOINT_NAME, workDir, conf, securityMgr))
    rpcEnv
  }

  def isUseLocalNodeSSLConfig(cmd: Command): Boolean = {
    val pattern = """\-Dspark\.ssl\.useNodeLocalConf\=(.+)""".r
    val result = cmd.javaOpts.collectFirst {
      case pattern(_result) => _result.toBoolean
    }
    result.getOrElse(false)
  }

  def maybeUpdateSSLSettings(cmd: Command, conf: SparkConf): Command = {
    val prefix = "spark.ssl."
    val useNLC = "spark.ssl.useNodeLocalConf"
    if (isUseLocalNodeSSLConfig(cmd)) {
      val newJavaOpts = cmd.javaOpts
          .filter(opt => !opt.startsWith(s"-D$prefix")) ++
          conf.getAll.collect { case (key, value) if key.startsWith(prefix) => s"-D$key=$value" } :+
          s"-D$useNLC=true"
      cmd.copy(javaOpts = newJavaOpts)
    } else {
      cmd
    }
  }
}
