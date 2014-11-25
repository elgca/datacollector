/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.runner.production;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import com.google.common.collect.EvictingQueue;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.config.DeliveryGuarantee;
import com.streamsets.pipeline.config.StageType;
import com.streamsets.pipeline.errorrecordstore.ErrorRecordStore;
import com.streamsets.pipeline.metrics.MetricsConfigurator;
import com.streamsets.pipeline.runner.*;
import com.streamsets.pipeline.snapshotstore.SnapshotStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProductionPipelineRunner implements PipelineRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProductionPipelineRunner.class);
  private static final int CACHE_MAX_NUMBER_OF_ERROR_RECORDS_PER_STAGE = 100;

  private final MetricRegistry metrics;
  private final SourceOffsetTracker offsetTracker;
  private final SnapshotStore snapshotStore;
  private final ErrorRecordStore errorRecordStore;

  private int batchSize;
  private String sourceOffset;
  private String newSourceOffset;
  private DeliveryGuarantee deliveryGuarantee;
  private final String pipelineName;

  private final Timer batchProcessingTimer;
  private final Meter batchCountMeter;
  private final Meter batchInputRecordsMeter;
  private final Meter batchOutputRecordsMeter;
  private final Meter batchErrorRecordsMeter;

  /*indicates if the execution must be stopped after the current batch*/
  private volatile boolean stop = false;
  /*indicates if the next batch of data should be captured, only the next batch*/
  private volatile boolean captureNextBatch = false;
  /*indicates the batch size to be captured*/
  private volatile int snapshotBatchSize;
  /*Cache last N error records per stage in memory*/
  private Map<String, EvictingQueue<ErrorRecord>> stageToErrorRecordsMap;

  public ProductionPipelineRunner(SnapshotStore snapshotStore, ErrorRecordStore errorRecordStore,
                                  SourceOffsetTracker offsetTracker, int batchSize,
                                  DeliveryGuarantee deliveryGuarantee, String pipelineName) {
    this.metrics = new MetricRegistry();
    this.offsetTracker = offsetTracker;
    this.batchSize = batchSize;

    batchProcessingTimer = MetricsConfigurator.createTimer(metrics, "pipeline.batchProcessing");
    batchCountMeter = MetricsConfigurator.createMeter(metrics, "pipeline.batchCount");
    batchInputRecordsMeter = MetricsConfigurator.createMeter(metrics, "pipeline.batchInputRecords");
    batchOutputRecordsMeter = MetricsConfigurator.createMeter(metrics, "pipeline.batchOutputRecords");
    batchErrorRecordsMeter = MetricsConfigurator.createMeter(metrics, "pipeline.batchErrorRecords");

    this.deliveryGuarantee = deliveryGuarantee;
    this.snapshotStore = snapshotStore;
    this.pipelineName = pipelineName;
    this.errorRecordStore = errorRecordStore;
    this.stageToErrorRecordsMap = new HashMap<>();
  }

  @Override
  public MetricRegistry getMetrics() {
    return metrics;
  }

  @Override
  public void run(Pipe[] pipes) throws StageException, PipelineRuntimeException {
    while(!offsetTracker.isFinished() && !stop) {
      runBatch(pipes);
    }
  }

  @Override
  public List<List<StageOutput>> getBatchesOutput() {
    List<List<StageOutput>> batchOutput = new ArrayList<>();
    if(snapshotStore.getSnapshotStatus(pipelineName).isExists()) {
      batchOutput.add(snapshotStore.retrieveSnapshot(pipelineName));
    }
    return batchOutput;
  }

  public String getSourceOffset() {
    return sourceOffset;
  }

  public String getNewSourceOffset() {
    return newSourceOffset;
  }

  public String getCommittedOffset() {
    return offsetTracker.getOffset();
  }

  /**
   * Stops execution of the pipeline after the current batch completes
   */
  public void stop() {
    this.stop = true;
  }

  public boolean wasStopped() {
    return stop;
  }

  public void captureNextBatch(int batchSize) {
    Preconditions.checkArgument(batchSize > 0);
    this.snapshotBatchSize = batchSize;
    this.captureNextBatch = true;

  }

  private void runBatch(Pipe[] pipes) throws PipelineRuntimeException, StageException {
    boolean committed = false;
    /*value true indicates that this batch is captured */
    boolean batchCaptured = false;
    PipeBatch pipeBatch;

    if(captureNextBatch) {
      batchCaptured = true;
      pipeBatch = new FullPipeBatch(offsetTracker, snapshotBatchSize, true /*snapshot stage output*/);
    } else {
      pipeBatch = new FullPipeBatch(offsetTracker, batchSize, false /*snapshot stage output*/);
    }

    long start = System.currentTimeMillis();
    sourceOffset = pipeBatch.getPreviousOffset();
    for (Pipe pipe : pipes) {
      //TODO Define an interface to handle delivery guarantee
      if (deliveryGuarantee == DeliveryGuarantee.AT_MOST_ONCE
          && pipe.getStage().getDefinition().getType() == StageType.TARGET && !committed) {
        offsetTracker.commitOffset();
        committed = true;
      }
      pipe.process(pipeBatch);
    }
    if (deliveryGuarantee == DeliveryGuarantee.AT_LEAST_ONCE) {
      offsetTracker.commitOffset();
    }

    batchProcessingTimer.update(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
    batchCountMeter.mark();
    batchInputRecordsMeter.mark(pipeBatch.getInputRecords());
    batchOutputRecordsMeter.mark(pipeBatch.getOutputRecords());
    batchErrorRecordsMeter.mark(pipeBatch.getErrorRecords());

    newSourceOffset = offsetTracker.getOffset();

    if(batchCaptured) {
      List<StageOutput> snapshot = pipeBatch.getSnapshotsOfAllStagesOutput();
      snapshotStore.storeSnapshot(pipelineName, snapshot);
      /*
       * Reset the capture snapshot variable only after capturing the snapshot
       * This guarantees that once captureSnapshot is called, the output is captured exactly once
       * */
      captureNextBatch = false;
      snapshotBatchSize = 0;
    }

    //dump all error records to store
    Map<String, ErrorRecords> errorRecords = pipeBatch.getErrorRecordSink().getErrorRecords();
    errorRecordStore.storeErrorRecords(pipelineName, errorRecords);
    //Retain X number of error records per stage
    retainErrorRecordsInMemory(errorRecords);
  }

  public SourceOffsetTracker getOffSetTracker() {
    return this.offsetTracker;
  }

  private void retainErrorRecordsInMemory(Map<String, ErrorRecords> errorRecords) {
    for(Map.Entry<String, ErrorRecords> e : errorRecords.entrySet()) {
      EvictingQueue<ErrorRecord> errorRecordList = stageToErrorRecordsMap.get(e.getKey());
      if(errorRecordList == null) {
        //replace with a data structure with an upper cap
        errorRecordList = EvictingQueue.create(CACHE_MAX_NUMBER_OF_ERROR_RECORDS_PER_STAGE);
        stageToErrorRecordsMap.put(e.getKey(), errorRecordList);
      }
      errorRecordList.addAll(errorRecords.get(e.getKey()).getErrorRecords());
    }
  }
}
