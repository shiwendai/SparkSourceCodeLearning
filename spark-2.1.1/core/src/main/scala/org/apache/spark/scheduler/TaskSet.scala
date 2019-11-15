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

import java.util.Properties

/**
 * A set of tasks submitted together to the low-level TaskScheduler, usually representing
 * missing partitions of a particular stage.
 */

// TaskSet可以有一到多个本地性级别，但在给Task分配本地性时只能是其中的一个。
// TaskSet中所有的Task都具有相同的允许使用的本地性级别，但在运行期可能因为资源不足、运行时间等因素，
// 导致同一TaskSet中的的各个Task的本地性级别可能不同

// TaskSet是整个调度池中对Task进行调度管理的基本单位，由调度池中的TaskSetManager来管理
private[spark] class TaskSet(
    val tasks: Array[Task[_]], // TaskSet所包含的Task的数组
    val stageId: Int, // Task所属Stage的身份标识
    val stageAttemptId: Int, // Stage尝试的身份标识
    val priority: Int, // 优先级。通常以JobId作为优先级
    val properties: Properties) { // 包括了与Job有关的调度、Job group、描述等属性的Properties。
  // TaskSet的身份标识
  val id: String = stageId + "." + stageAttemptId

  override def toString: String = "TaskSet " + id
}
