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

package org.apache.spark.streaming.eventhubs.checkpoint

import scala.collection.mutable.ListBuffer

import org.apache.spark.internal.Logging
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.eventhubs.EventHubDirectDStream
import org.apache.spark.streaming.scheduler.{StreamingListener, StreamingListenerBatchCompleted}

/**
 * The listener asynchronously commits the temp checkpoint to the path which is read by DStream
 * driver. It monitors the input size to prevent those empty batches from committing checkpoints
 */
private[eventhubs] class ProgressTrackingListener private (
    ssc: StreamingContext, progressDirectory: String) extends StreamingListener with Logging {

  override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted): Unit = {
    logInfo(s"Batch ${batchCompleted.batchInfo.batchTime} completed")
    val batchTime = batchCompleted.batchInfo.batchTime.milliseconds
    if (batchCompleted.batchInfo.outputOperationInfos.forall(_._2.failureReason.isEmpty)) {
      val progressTracker = ProgressTracker.getInstance
      // build current offsets
      val allEventDStreams = ProgressTracker.eventHubDirectDStreams
      // merge with the temp directory
      val progressInLastBatch = progressTracker.collectProgressRecordsForBatch(batchTime)
      logInfo(s"progressInLastBatch $progressInLastBatch")
      val contentToCommit = allEventDStreams.map {
        dstream => ((dstream.eventHubNameSpace, dstream.id), dstream.currentOffsetsAndSeqNums)
      }.toMap.map { case (namespace, currentOffsets) =>
        (namespace, currentOffsets ++ progressInLastBatch.getOrElse(namespace._1, Map()))
      }
      ssc.graph.synchronized {
        progressTracker.commit(contentToCommit, batchTime)
        logInfo(s"commit ending offset of Batch $batchTime $contentToCommit")
        // NOTE: we need to notifyAll here to handle multiple EventHubDirectStreams in application
        ssc.graph.notifyAll()
      }
    }
  }
}

private[eventhubs] object ProgressTrackingListener {

  private var _progressTrackerListener: ProgressTrackingListener = _

  private def getOrCreateProgressTrackerListener(
      ssc: StreamingContext,
      progressDirectory: String) = {
    if (_progressTrackerListener == null) {
      _progressTrackerListener = new ProgressTrackingListener(ssc, progressDirectory)
      ssc.scheduler.listenerBus.listeners.add(0, _progressTrackerListener)
    }
    _progressTrackerListener
  }

  private[eventhubs] def reset(ssc: StreamingContext): Unit = {
    ssc.scheduler.listenerBus.listeners.remove(0)
    _progressTrackerListener = null
  }

  def initInstance(
      ssc: StreamingContext,
      progressDirectory: String): ProgressTrackingListener = this.synchronized {
    getOrCreateProgressTrackerListener(ssc, progressDirectory)
  }
}

