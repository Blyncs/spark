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

package org.apache.spark.status

import java.io.File
import java.util.Properties

import scala.util.Random

import org.apache.spark.{SparkConf, SparkFunSuite, Success, TaskState}
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.scheduler._
import org.apache.spark.util.{Benchmark, Utils}

// scalastyle:off
/**
 * Benchmark to...
 *
 * Regression seems to have occured here
 * https://github.com/Blyncs/spark/commit/a6bf3db20773ba65cbc4f2775db7bd215e78829a#diff-b43d5bd18e0f3b5f33d734a37389ed34
 *
 * build/sbt "core/test-only *AppStatusListenerBenchmark"
 */
// scalastyle:on
class AppStatusListenerBenchmark extends SparkFunSuite {

  val jobCount = 10
  val stageCount = 1000
  val taskCount = 10

  val benchmark = new Benchmark(
    "Benchmark AppStatusListener",
    jobCount * stageCount * taskCount,
    2
  )

  import config._

  private val conf = new SparkConf()
    .set(LIVE_ENTITY_UPDATE_PERIOD, 0L)
    .set(ASYNC_TRACKING_ENABLED, false)

  private var time = 0L
  private var taskIdTracker = 0L
  private var testDir: File = _
  private var store: ElementTrackingStore = _

  testDir = Utils.createTempDir()
  store = new ElementTrackingStore(KVUtils.open(testDir, getClass().getName()), conf)

  test(s"Benchmark AppStatusListener") {

    Seq (false, true).foreach { sort =>
      benchmark.addCase(s"running sort: $sort") { _ =>
        val testConf = conf.clone()
          .set(MAX_RETAINED_JOBS, 2)
          .set(MAX_RETAINED_STAGES, 2)
          .set(MAX_RETAINED_TASKS_PER_STAGE, 2)
          .set(MAX_RETAINED_DEAD_EXECUTORS, 1)
        val listener = new AppStatusListener(store, testConf, true, sort = sort)

        time += 1

        val jobsAndStages = (0 until jobCount).map { jobId =>
          time += 1
          val stages = (0 until stageCount).map { stageId =>
            new StageInfo(stageId, 0, s"stage$stageId", taskCount, Nil, Nil, s"details$stageId")
          }

          // Graph data is generated by the job start event, so fire it.
          listener.onJobStart(SparkListenerJobStart(jobId, time, stages, null))

          (jobId, stages)
        }

        val justStages = jobsAndStages.flatMap { case (_, stageInfos) =>
          stageInfos.map { s =>
            time += 1
            s.submissionTime = Some(time)
            listener.onStageSubmitted(SparkListenerStageSubmitted(s, new Properties()))
            s
          }
        }

        val stageAndTask = justStages.flatMap { s =>
          val tasks = createTasks(taskCount, Array("1"))
          tasks.map { t =>
            time += 1
            listener.onTaskStart(
              SparkListenerTaskStart(s.stageId, s.attemptNumber(), t)
            )
            (s, t)
          }
        }

        Random.shuffle(stageAndTask).foreach { case (stage, task) =>
          time += 1
          task.markFinished(TaskState.FINISHED, time)
          val metrics = TaskMetrics.empty
          metrics.setExecutorRunTime(42L)
          listener.onTaskEnd(SparkListenerTaskEnd(stage.stageId, stage.attemptNumber(),
            "taskType", Success, task, metrics))
        }

        justStages.foreach { s =>
          time += 1
          s.completionTime = Some(time)
          listener.onStageCompleted(SparkListenerStageCompleted(s))
        }

        time += 1
        listener.onJobEnd(SparkListenerJobEnd(1, time, JobSucceeded))
      }
    }

    benchmark.run()
  }

  private def nextTaskId(): Long = {
    taskIdTracker += 1
    taskIdTracker
  }

  private def createTasks(count: Int, execs: Array[String]): Seq[TaskInfo] = {
    (1 to count).map { id =>
      val exec = execs(id.toInt % execs.length)
      val taskId = nextTaskId()
      new TaskInfo(taskId, taskId.toInt, 1, time, exec, s"$exec.example.com",
        TaskLocality.PROCESS_LOCAL, id % 2 == 0)
    }
  }
}
