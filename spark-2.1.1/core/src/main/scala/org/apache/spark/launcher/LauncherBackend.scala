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

package org.apache.spark.launcher

import java.net.{InetAddress, Socket}

import org.apache.spark.SPARK_VERSION
import org.apache.spark.launcher.LauncherProtocol._
import org.apache.spark.util.{ThreadUtils, Utils}

/**
 * A class that can be used to talk to a launcher server. Users should extend this class to
 * provide implementation for the abstract methods.
 *
 * See `LauncherServer` for an explanation of how launcher communication works.
 */
// LauncherBackend是SchedulerBackend与LauncherServer通信组件
private[spark] abstract class LauncherBackend {

  // 读取与LauncherServer建立的Socket连接上的消息的线程
  private var clientThread: Thread = _
  // 即BackendConnection实例
  private var connection: BackendConnection = _
  // LauncherBackend的最后一次状态。lastState的类型是枚举类型SparkAppHandle.State，拥有以下几种状态：
  // UNKNOWN(false) CONNECTED(false) SUBMITTED(false) RUNNING(false) FINISHED(true) FAILED(true) KILLED(true) LOST(true)
  private var lastState: SparkAppHandle.State = _

  // clientThread是否与LauncherServer已经建立了Socket连接的状态
  @volatile private var _isConnected = false

  // 用于和LauncherServer建立连接
  def connect(): Unit = {
    val port = sys.env.get(LauncherProtocol.ENV_LAUNCHER_PORT).map(_.toInt)
    val secret = sys.env.get(LauncherProtocol.ENV_LAUNCHER_SECRET)
    if (port != None && secret != None) {
      // 创建与LauncherServer的Socket服务端建立连接的Socket
      val s = new Socket(InetAddress.getLoopbackAddress(), port.get)
      connection = new BackendConnection(s)
      // 通过此连接向LauncherServere发送Hello消息。Hello消息携带者应用程序的Spark版本号和密钥信息
      connection.send(new Hello(secret.get, SPARK_VERSION))
      // 创建并启动一个执行BackendConnection的run方法的线程
      clientThread = LauncherBackend.threadFactory.newThread(connection)
      // 启动线程
      clientThread.start()
      // 将 _isConnected设置为true
      _isConnected = true
    }
  }

  def close(): Unit = {
    if (connection != null) {
      try {
        connection.close()
      } finally {
        if (clientThread != null) {
          clientThread.join()
        }
      }
    }
  }

  // 用于向LauncherServer发送SetAppId消息。SetAppId消息携带着应用程序的身份标识
  def setAppId(appId: String): Unit = {
    if (connection != null) {
      connection.send(new SetAppId(appId))
    }
  }

  // 用于向LauncherServer发送SetState消息。SetState消息携带着LauncherBackend的最后一次状态
  def setState(state: SparkAppHandle.State): Unit = {
    if (connection != null && lastState != state) {
      connection.send(new SetState(state))
      lastState = state
    }
  }

  // 返回clientThread是否与LauncherServer已经建立了Socket连接的状态
  /** Return whether the launcher handle is still connected to this backend. */
  def isConnected(): Boolean = _isConnected

  /**
   * Implementations should provide this method, which should try to stop the application
   * as gracefully as possible.
   */
  // onStopRequest是LauncherBackend定义的处理LauncherServer的停止消息的抽象方法
  protected def onStopRequest(): Unit

  /**
   * Callback for when the launcher handle disconnects from this backend.
   */
  // 用于关闭Socket客户端与LauncherServer的Socket服务端建立的连接时，进行一些额外的处理，目前是个空方法
  protected def onDisconnected() : Unit = { }

  // 用于启动一个调用onStopRequest方法的线程
  private def fireStopRequest(): Unit = {
    val thread = LauncherBackend.threadFactory.newThread(new Runnable() {
      override def run(): Unit = Utils.tryLogNonFatalError {
        onStopRequest()
      }
    })
    thread.start()
  }

  // BackendConnection是LauncherBackend的内部组件，用于保持与LauncherServer的Socket连接，并通过此Socket连接收发消息。
  // BackendConnection继承了LauncherConnection,LauncherConnection提供了维护连接和收发消息的基本实现
  private class BackendConnection(s: Socket) extends LauncherConnection(s) {

    // 此方法只处理Stop这一种消息，对于stop消息，BackendConnection将调用外部类LauncherBackend的fireStopRequest方法停止Executor
    override protected def handle(m: Message): Unit = m match {
      case _: Stop =>
        fireStopRequest()

      case _ =>
        throw new IllegalArgumentException(s"Unexpected message type: ${m.getClass().getName()}")
    }

    override def close(): Unit = {
      try {
        super.close()
      } finally {
        onDisconnected()
        _isConnected = false
      }
    }

  }

}

private object LauncherBackend {

  val threadFactory = ThreadUtils.namedThreadFactory("LauncherBackend")

}
