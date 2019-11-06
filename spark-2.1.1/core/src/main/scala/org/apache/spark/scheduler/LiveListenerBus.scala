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

import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.util.DynamicVariable

import org.apache.spark.{SparkContext, SparkException}
import org.apache.spark.internal.config._
import org.apache.spark.util.Utils

/**
 * Asynchronously passes SparkListenerEvents to registered SparkListeners.
 *
 * Until `start()` is called, all posted events are only buffered. Only after this listener bus
 * has started will events be actually propagated to all attached listeners. This listener bus
 * is stopped when `stop()` is called, and it will drop further events after stopping.
 */
// 实现了将事件异步投递给监听器，达到实时刷新UI界面数据的效果
private[spark] class LiveListenerBus(val sparkContext: SparkContext) extends SparkListenerBus {

  self =>

  import LiveListenerBus._

  // Cap the capacity of the event queue so we get an explicit error (rather than
  // an OOM exception) if it's perpetually being added to more quickly than it's being drained.
  private lazy val EVENT_QUEUE_CAPACITY = validateAndGetQueueSize()

  // 是SparkListenerEvent事件的阻塞队列
  private lazy val eventQueue = new LinkedBlockingQueue[SparkListenerEvent](EVENT_QUEUE_CAPACITY)

  private def validateAndGetQueueSize(): Int = {
    val queueSize = sparkContext.conf.get(LISTENER_BUS_EVENT_QUEUE_SIZE)
    if (queueSize <= 0) {
      throw new SparkException("spark.scheduler.listenerbus.eventqueue.size must be > 0!")
    }
    queueSize
  }

  // Indicate if `start()` is called
  // 标记LiveListenerBus的启动状态的AtomicBoolean类型的变量
  private val started = new AtomicBoolean(false)

  // Indicate if `stop()` is called
  // 标记LiveListenerBus的停止状态的AtomicBoolean类型的变量
  private val stopped = new AtomicBoolean(false)

  // 使用AtomicLong类型对删除的事件进行计数，每当日志打印了droppedEventsCounter后，会将droppedEventsCounter重置为0
  /** A counter for dropped events. It will be reset every time we log it. */
  private val droppedEventsCounter = new AtomicLong(0L)

  /** When `droppedEventsCounter` was logged last time in milliseconds. */
  // 用于记录最后一次日志打印droppedEventsCounter的时间戳
  @volatile private var lastReportTimestamp = 0L

  // Indicate if we are processing some event
  // Guarded by `self`
  // 用来标记当前正有时间被listener线程处理
  private var processingEvent = false

  // 用于标记是否由于eventQueue已满，导致新的事件被删除
  private val logDroppedEvent = new AtomicBoolean(false)

  // A counter that represents the number of events produced and consumed in the queue
  // 用于当有新的事件到来时释放信号量，当对事件进行处理时获取信号量,
  // 对于构造参数为0时，所有acquire（）调用都会阻塞，tryAcquire（）调用将返回false，直到你执行release（）
  private val eventLock = new Semaphore(0)

  private val listenerThread = new Thread(name) {
    setDaemon(true)
    override def run(): Unit = Utils.tryOrStopSparkContext(sparkContext) {
      LiveListenerBus.withinListenerThread.withValue(true) {
        while (true) {
          // 不断获取信号量（当可以获取信号量是，说明还有事件未处理）
          eventLock.acquire()
          // 通过同步控制，将processingEvent设置为true
          self.synchronized {
            processingEvent = true
          }
          try {
            // 从eventQueue中获取事件
            val event = eventQueue.poll
            if (event == null) {
              // Get out of the while loop and shutdown the daemon thread
              if (!stopped.get) {
                throw new IllegalStateException("Polling `null` from eventQueue means" +
                  " the listener bus has been stopped. So `stopped` must be true")
              }
              return
            }
            // 调用超类ListenerBus的postToAll方法（postToAll方法对监听器进行遍历，并调用SparkListenerBus
            // 的doPostEvent方法对事件进行匹配后执行监听器的相应方法）
            postToAll(event)
          } finally {
            // 每次循环结束依然需要通过同步控制，将processingEvent设置为false
            self.synchronized {
              processingEvent = false
            }
          }
        }
      }
    }
  }

  /**
   * Start sending events to attached listeners.
   *
   * This first sends out all buffered events posted before this listener bus has started, then
   * listens for any additional events asynchronously while the listener bus is still running.
   * This should only be called once.
   *
   */
  def start(): Unit = {
    if (started.compareAndSet(false, true)) {
      listenerThread.start()
    } else {
      throw new IllegalStateException(s"$name already started!")
    }
  }

  // 将事件放入eventQueue中
  def post(event: SparkListenerEvent): Unit = {
    // 判断LiveListenerBus是否已经处于停止状态
    if (stopped.get) {
      // Drop further events to make `listenerThread` exit ASAP
      logError(s"$name has already stopped! Dropping event $event")
      return
    }
    // 向eventQueue中添加事件
    val eventAdded = eventQueue.offer(event)
    if (eventAdded) {
      // 如果添加成功，则释放信号量，进而催化listenerThread有效工作。
      eventLock.release()
    } else {
      // 如果eventQueue已满造成添加失败，则移除事件，并对删除事件计算器droppedEventsCounter进行自增
      onDropEvent(event)
      droppedEventsCounter.incrementAndGet()
    }

    // 如果有事件被删除，并且当前系统时间距离上一次打印droppedEventsCounter超过了60秒，则将droppedEventsCounter打印到日志
    val droppedEvents = droppedEventsCounter.get
    if (droppedEvents > 0) {
      // Don't log too frequently
      if (System.currentTimeMillis() - lastReportTimestamp >= 60 * 1000) {
        // There may be multiple threads trying to decrease droppedEventsCounter.
        // Use "compareAndSet" to make sure only one thread can win.
        // And if another thread is increasing droppedEventsCounter, "compareAndSet" will fail and
        // then that thread will update it.
        if (droppedEventsCounter.compareAndSet(droppedEvents, 0)) {
          val prevLastReportTimestamp = lastReportTimestamp
          lastReportTimestamp = System.currentTimeMillis()
          logWarning(s"Dropped $droppedEvents SparkListenerEvents since " +
            new java.util.Date(prevLastReportTimestamp))
        }
      }
    }
  }

  /**
   * For testing only. Wait until there are no more events in the queue, or until the specified
   * time has elapsed. Throw `TimeoutException` if the specified time elapsed before the queue
   * emptied.
   * Exposed for testing.
   */
  @throws(classOf[TimeoutException])
  def waitUntilEmpty(timeoutMillis: Long): Unit = {
    val finishTime = System.currentTimeMillis + timeoutMillis
    while (!queueIsEmpty) {
      if (System.currentTimeMillis > finishTime) {
        throw new TimeoutException(
          s"The event queue is not empty after $timeoutMillis milliseconds")
      }
      /* Sleep rather than using wait/notify, because this is used only for testing and
       * wait/notify add overhead in the general case. */
      Thread.sleep(10)
    }
  }

  /**
   * For testing only. Return whether the listener daemon thread is still alive.
   * Exposed for testing.
   */
  def listenerThreadIsAlive: Boolean = listenerThread.isAlive

  /**
   * Return whether the event queue is empty.
   *
   * The use of synchronized here guarantees that all events that once belonged to this queue
   * have already been processed by all attached listeners, if this returns true.
   */
  private def queueIsEmpty: Boolean = synchronized { eventQueue.isEmpty && !processingEvent }

  /**
   * Stop the listener bus. It will wait until the queued events have been processed, but drop the
   * new events after stopping.
   */
  def stop(): Unit = {
    if (!started.get()) {
      throw new IllegalStateException(s"Attempted to stop $name that has not yet started!")
    }
    if (stopped.compareAndSet(false, true)) {
      // Call eventLock.release() so that listenerThread will poll `null` from `eventQueue` and know
      // `stop` is called.
      eventLock.release()
      listenerThread.join()
    } else {
      // Keep quiet
    }
  }

  /**
   * If the event queue exceeds its capacity, the new events will be dropped. The subclasses will be
   * notified with the dropped events.
   *
   * Note: `onDropEvent` can be called in any thread.
   */
  def onDropEvent(event: SparkListenerEvent): Unit = {
    if (logDroppedEvent.compareAndSet(false, true)) {
      // Only log the following message once to avoid duplicated annoying logs.
      logError("Dropping SparkListenerEvent because no remaining room in event queue. " +
        "This likely means one of the SparkListeners is too slow and cannot keep up with " +
        "the rate at which tasks are being started by the scheduler.")
    }
  }
}

private[spark] object LiveListenerBus {
  // Allows for Context to check whether stop() call is made within listener thread
  // 允许Context检查是否在侦听器线程内进行了stop（）调用
  val withinListenerThread: DynamicVariable[Boolean] = new DynamicVariable[Boolean](false)

  /** The thread name of Spark listener bus */
  val name = "SparkListenerBus"
}

