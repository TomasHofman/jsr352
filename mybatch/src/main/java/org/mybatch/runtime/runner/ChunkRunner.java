/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mybatch.runtime.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import javax.batch.api.chunk.CheckpointAlgorithm;
import javax.batch.api.chunk.ItemProcessor;
import javax.batch.api.chunk.ItemReader;
import javax.batch.api.chunk.ItemWriter;
import javax.batch.api.chunk.listener.ChunkListener;
import javax.batch.api.chunk.listener.ItemProcessListener;
import javax.batch.api.chunk.listener.ItemReadListener;
import javax.batch.api.chunk.listener.ItemWriteListener;
import javax.batch.api.chunk.listener.SkipProcessListener;
import javax.batch.api.chunk.listener.SkipReadListener;
import javax.batch.api.chunk.listener.SkipWriteListener;
import javax.batch.operations.BatchRuntimeException;
import javax.batch.runtime.BatchStatus;

import org.mybatch.job.Chunk;
import org.mybatch.metadata.ExceptionClassFilterImpl;
import org.mybatch.runtime.context.StepContextImpl;

import static org.mybatch.util.BatchLogger.LOGGER;

public final class ChunkRunner extends AbstractRunner<StepContextImpl> implements Runnable {
    private static final int defaultCheckpointTimeout = 600;

    private Chunk chunk;
    private StepExecutionRunner stepRunner;
    private ItemReader itemReader;
    private ItemWriter itemWriter;
    private ItemProcessor itemProcessor;
    private String checkpointPolicy = "item";
    private CheckpointAlgorithm checkpointAlgorithm;
    private int itemCount = 10;
    private int timeLimit;  //in seconds
    private int skipLimit;  //default no limit
    private int retryLimit;  //default no limit

    private ExceptionClassFilterImpl skippableExceptionClasses;
    private ExceptionClassFilterImpl retryableExceptionClasses;
    private ExceptionClassFilterImpl noRollbackExceptionClasses;
    private int skipCount;
    private int retryCount;

    public ChunkRunner(StepContextImpl stepContext, CompositeExecutionRunner enclosingRunner, StepExecutionRunner stepRunner, Chunk chunk) {
        super(stepContext, enclosingRunner);
        this.stepRunner = stepRunner;
        this.chunk = chunk;

        org.mybatch.job.ItemReader readerElement = chunk.getReader();
        itemReader = batchContext.getJobContext().createArtifact(
                readerElement.getRef(), readerElement.getProperties(), batchContext);

        org.mybatch.job.ItemWriter writerElement = chunk.getWriter();
        itemWriter = batchContext.getJobContext().createArtifact(
                writerElement.getRef(), writerElement.getProperties(), batchContext);

        org.mybatch.job.ItemProcessor processorElement = chunk.getProcessor();
        if (processorElement != null) {
            itemProcessor = batchContext.getJobContext().createArtifact(
                    processorElement.getRef(), processorElement.getProperties(), batchContext);
        }

        String attrVal = chunk.getCheckpointPolicy();
        if (attrVal == null || attrVal.equals("item")) {
            attrVal = chunk.getItemCount();
            if (attrVal != null) {
                itemCount = Integer.parseInt(attrVal);
                if (itemCount < 1) {
                    throw LOGGER.invalidItemCount(itemCount);
                }
            }
            attrVal = chunk.getTimeLimit();
            if (attrVal != null) {
                timeLimit = Integer.parseInt(attrVal);
            }
        } else if (attrVal.equals("custom")) {
            checkpointPolicy = "custom";
            org.mybatch.job.CheckpointAlgorithm alg = chunk.getCheckpointAlgorithm();
            if (alg != null) {
                checkpointAlgorithm = batchContext.getJobContext().createArtifact(
                        alg.getRef(), alg.getProperties(), batchContext);
            } else {
                throw LOGGER.checkpointAlgorithmMissing(stepRunner.step.getId());
            }
        } else {
            throw LOGGER.invalidCheckpointPolicy(attrVal);
        }

        attrVal = chunk.getSkipLimit();
        if (attrVal != null) {
            skipLimit = Integer.parseInt(attrVal);
        }
        attrVal = chunk.getRetryLimit();
        if (attrVal != null) {
            retryLimit = Integer.parseInt(attrVal);
        }

        skippableExceptionClasses = (ExceptionClassFilterImpl) chunk.getSkippableExceptionClasses();
        retryableExceptionClasses = (ExceptionClassFilterImpl) chunk.getRetryableExceptionClasses();
        noRollbackExceptionClasses = (ExceptionClassFilterImpl) chunk.getNoRollbackExceptionClasses();
    }

    @Override
    public void run() {
        try {
            itemReader.open(null);
            itemWriter.open(null);

            readProcessWriteItems();

            itemReader.close();
            itemWriter.close();
        } catch (Throwable e) {
            Exception exception = e instanceof Exception ? (Exception) e : new BatchRuntimeException(e);
            batchContext.setException(exception);
            LOGGER.failToRunJob(e, batchContext.getJobContext().getJobName(), batchContext.getStepName(), chunk);
            batchContext.setBatchStatus(BatchStatus.FAILED);
        }
    }

    private void readProcessWriteItems() throws Exception {
        List<Object> outputList = new ArrayList<Object>();
        ProcessingInfo processingInfo = ProcessingInfo.get();
        int checkpointTimeout = 0;
        Object item = null;
        boolean allItemsProcessed = false;
        while (!allItemsProcessed) {
            try {
                if (processingInfo.startingNewChunk) {
                    for (ChunkListener l : stepRunner.chunkListeners) {
                        l.beforeChunk();
                    }
                    checkpointTimeout = checkpointTimeout();
                    beginCheckpoint(processingInfo);
                }
                item = readItem(processingInfo);
                if (item != null) {
                    processItem(item, processingInfo, outputList);
                }

                if (isReadyToCheckpoint(processingInfo) || item == null) {
                    doCheckpoint(processingInfo, outputList);
                    for (ChunkListener l : stepRunner.chunkListeners) {
                        l.afterChunk();
                    }
                }
            } catch (Exception e) {
                for (ChunkListener l : stepRunner.chunkListeners) {
                    l.onError(e);
                }
                throw e;
            }
            allItemsProcessed = item == null;
        }
    }

    private Object readItem(final ProcessingInfo processingInfo) throws Exception {
        Object result = null;
        try {
            for (ItemReadListener l : stepRunner.itemReadListeners) {
                l.beforeRead();
            }
            result = itemReader.readItem();
            for (ItemReadListener l : stepRunner.itemReadListeners) {
                l.afterRead(result);
            }
        } catch (Exception e) {
            for (ItemReadListener l : stepRunner.itemReadListeners) {
                l.onReadError(e);
            }
            if (maySkip(e)) {
                for (SkipReadListener l : stepRunner.skipReadListeners) {
                    l.onSkipReadItem(e);
                }
                skipCount++;
                readItem(processingInfo);
            } else {
                throw e;
            }
        }
        return result;
    }

    private void processItem(final Object item, final ProcessingInfo processingInfo, final List<Object> outputList) throws Exception {
        Object output;
        boolean skippedProcessing = false;
        if (itemProcessor != null) {
            try {
                for (ItemProcessListener l : stepRunner.itemProcessListeners) {
                    l.beforeProcess(item);
                }
                output = itemProcessor.processItem(item);
                for (ItemProcessListener l : stepRunner.itemProcessListeners) {
                    l.afterProcess(item, output);
                }
            } catch (Exception e) {
                for (ItemProcessListener l : stepRunner.itemProcessListeners) {
                    l.onProcessError(item, e);
                }
                if (maySkip(e)) {
                    for (SkipProcessListener l : stepRunner.skipProcessListeners) {
                        l.onSkipProcessItem(item, e);
                    }
                    skipCount++;
                    output = null;
                    skippedProcessing = true;
                } else {
                    throw e;
                }
            }
        } else {
            output = item;
        }
        //a normal processing can also return null to exclude the processing result from writer.
        if (output != null) {
            outputList.add(output);
        }
        if (!skippedProcessing) {
            processingInfo.count++;
        }
    }

    private int checkpointTimeout() throws Exception {
        if (checkpointPolicy.equals("item")) {
            return defaultCheckpointTimeout;
        } else {
            return checkpointAlgorithm.checkpointTimeout();
        }
    }

    private void beginCheckpoint(final ProcessingInfo processingInfo) throws Exception {
        if (checkpointPolicy.equals("item")) {
            if (timeLimit > 0) {
                Timer timer = new Timer("chunk-checkpoint-timer", true);
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        processingInfo.timerExpired = true;
                    }
                }, timeLimit * 1000);
            }
        } else {
            checkpointAlgorithm.beginCheckpoint();
        }
        processingInfo.startingNewChunk = false;
    }

    private boolean isReadyToCheckpoint(final ProcessingInfo processingInfo) throws Exception {
        if (checkpointPolicy.equals("item")) {
            if (processingInfo.count >= itemCount) {
                return true;
            }
            if (timeLimit > 0) {
                return processingInfo.timerExpired;
            }
            return false;
        }
        return checkpointAlgorithm.isReadyToCheckpoint();
    }

    private void doCheckpoint(final ProcessingInfo processingInfo, final List<Object> outputList) throws Exception {
        if (outputList.size() > 0) {
            try {
                for (ItemWriteListener l : stepRunner.itemWriteListeners) {
                    l.beforeWrite(outputList);
                }
                itemWriter.writeItems(outputList);
                for (ItemWriteListener l : stepRunner.itemWriteListeners) {
                    l.afterWrite(outputList);
                }
            } catch (Exception e) {
                for (ItemWriteListener l : stepRunner.itemWriteListeners) {
                    l.onWriteError(outputList, e);
                }
                if (maySkip(e)) {
                    for (SkipWriteListener l : stepRunner.skipWriteListeners) {
                        l.onSkipWriteItem(outputList, e);
                    }
                    skipCount++;
                } else {
                    throw e;
                }
            }
            outputList.clear();
        }
        if (!checkpointPolicy.equals("item")) {
            checkpointAlgorithm.endCheckpoint();
        }
        processingInfo.reset();
    }

    private boolean maySkip(Exception e) {
        return skippableExceptionClasses != null &&
                ((skipLimit > 0 && skipCount < skipLimit) || skipLimit <= 0) &&
                skippableExceptionClasses.matches(e.getClass());
    }

    private boolean mayRetry(Exception e) {
        return retryableExceptionClasses != null &&
                ((retryLimit > 0 && retryCount < retryLimit) || retryLimit <= 0) &&
                retryableExceptionClasses.matches(e.getClass());
    }

    private static final class ProcessingInfo {
        private static ProcessingInfo instance = new ProcessingInfo();

        int count;
        boolean timerExpired;
        boolean startingNewChunk = true;

        private ProcessingInfo() {
        }

        static ProcessingInfo get() {
            return instance;
        }

        void reset() {
            count = 0;
            timerExpired = false;
            startingNewChunk = true;
        }
    }
}
