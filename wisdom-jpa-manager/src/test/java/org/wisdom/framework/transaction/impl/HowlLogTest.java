/*
 * #%L
 * Wisdom-Framework
 * %%
 * Copyright (C) 2013 - 2014 Wisdom Framework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.wisdom.framework.transaction.impl;

import org.apache.geronimo.transaction.log.XidImpl2;
import org.apache.geronimo.transaction.manager.TransactionLog;
import org.apache.geronimo.transaction.manager.XidFactory;
import org.junit.Before;
import org.junit.Test;

import javax.transaction.xa.Xid;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;

public class HowlLogTest {

    private static final File basedir = new File(System.getProperty("basedir", System.getProperty("user.dir")));
    private static final String LOG_FILE_NAME = "howl_test";


    protected void closeTransactionLog(TransactionLog transactionLog) throws Exception {
        ((HowlLog) transactionLog).stop();
    }


    protected TransactionLog createTransactionLog() throws Exception {
        XidFactory xidFactory = new XidFactoryImpl("hi".getBytes());
        HowlLog howlLog = new HowlLog(
                "org.objectweb.howl.log.BlockLogBuffer", //                "bufferClassName",
                4, //                "bufferSizeKBytes",
                true, //                "checksumEnabled",
                true,
                20, //                "flushSleepTime",
                "txlog", //                "logFileDir",
                "log", //                "logFileExt",
                LOG_FILE_NAME, //                "logFileName",
                200, //                "maxBlocksPerFile",
                10, //                "maxBuffers",
                2, //                "maxLogFiles",
                2, //                "minBuffers",
                10,//                "threadsWaitingForceThreshold"});
                true,
                xidFactory, new File(basedir, "target")
        );
        howlLog.start();
        return howlLog;
    }

    @Before
    public void setUp() throws Exception {
        File logFile = new File(basedir, "target/" + LOG_FILE_NAME + "_1.log");
        if (logFile.exists()) {
            logFile.delete();
        }
        logFile = new File(basedir, "target/" + LOG_FILE_NAME + "_2.log");
        if (logFile.exists()) {
            logFile.delete();
        }
    }

    private Object startBarrier = new Object();
    private Object stopBarrier = new Object();
    private int startedThreads = 0;
    private int stoppedThreads = 0;
    long totalDuration = 0;
    private Xid xid;
    private List names;
    final Object mutex = new Object();
    long totalXidCount = 0;
    private Writer resultsXML;
    private Writer resultsCSV;


    @Test
    public void testTransactionLog() throws Exception {
        File resultFileXML = new File("target/howllog" + ".xml");
        resultsXML = new FileWriter(resultFileXML);
        resultsXML.write("<log-test>\n");
        File resultFileCSV = new File("target/howllog" + ".csv");
        resultsCSV = new FileWriter(resultFileCSV);
        resultsCSV.write("workerCount,xidCount,TotalXids,missingXids,DurationMilliseconds,XidsPerSecond,AverageForceTime,AverageBytesPerForce,AverageLatency\n");
        int xidCount = Integer.getInteger("xa.log.test.xid.count", 50).intValue();
        int minWorkerCount = Integer.getInteger("xa.log.test.worker.count.min", 20).intValue();
        int maxWorkerCount = Integer.getInteger("xa.log.test.worker.count.max", 40).intValue();
        int workerCountStep = Integer.getInteger("xa.log.test.worker.count.step", 20).intValue();
        int repCount = Integer.getInteger("xa.log.test.repetition.count", 1).intValue();
        long maxTime = Long.getLong("xa.log.test.max.time.seconds", 30).longValue() * 1000;
        int overtime = 0;
        try {
            for (int workers = minWorkerCount; workers <= maxWorkerCount; workers += workerCountStep) {
                for (int reps = 0; reps < repCount; reps++) {
                    if (testTransactionLog(workers, xidCount) > maxTime) {
                        overtime++;
                        if (overtime > 1) {
                            return;
                        }
                    }
                    resultsCSV.flush();
                    resultsXML.flush();
                }
            }
        } finally {
            resultsXML.write("</log-test>\n");
            resultsXML.flush();
            resultsXML.close();
            resultsCSV.flush();
            resultsCSV.close();
        }
    }


    public long testTransactionLog(int workers, int xidCount) throws Exception {
        TransactionLog transactionLog = createTransactionLog();

        xid = new XidImpl2(new byte[Xid.MAXGTRIDSIZE]);
        names = Collections.EMPTY_LIST;

        long startTime = journalTest(transactionLog, workers, xidCount);

        long stopTime = System.currentTimeMillis();

        printSpeedReport(transactionLog, startTime, stopTime, workers, xidCount);
        closeTransactionLog(transactionLog);
        return stopTime - startTime;
    }


    private long journalTest(final TransactionLog logger, final int workers, final int xidCount)
            throws Exception {
        totalXidCount = 0;
        startedThreads = 0;
        stoppedThreads = 0;
        totalDuration = 0;
        for (int i = 0; i < workers; i++) {
            new Thread() {
                public void run() {
                    long localXidCount = 0;
                    boolean exception = false;
                    long localDuration = 0;
                    try {
                        synchronized (startBarrier) {
                            ++startedThreads;
                            startBarrier.notifyAll();
                            while (startedThreads < (workers + 1)) startBarrier.wait();
                        }
                        long localStartTime = System.currentTimeMillis();

                        for (int i = 0; i < xidCount; i++) {
// journalize COMMITTING record
                            Object logMark = logger.prepare(xid, names);
//localXidCount++;

// journalize FORGET record
                            logger.commit(xid, logMark);
                            localXidCount++;
                        }
                        localDuration = System.currentTimeMillis() - localStartTime;
                    } catch (Exception e) {
                        System.err.println(Thread.currentThread().getName());
                        e.printStackTrace(System.err);
                        exception = true;
                    } finally {
                        synchronized (mutex) {
                            totalXidCount += localXidCount;
                            totalDuration += localDuration;
                        }
                        synchronized (stopBarrier) {
                            ++stoppedThreads;
                            stopBarrier.notifyAll();
                        }
                    }

                }
            }
                    .start();
        }

// Wait for all the workers to be ready..
        long startTime = 0;
        synchronized (startBarrier) {
            while (startedThreads < workers) startBarrier.wait();
            ++startedThreads;
            startBarrier.notifyAll();
            startTime = System.currentTimeMillis();
        }

// Wait for all the workers to finish.
        synchronized (stopBarrier) {
            while (stoppedThreads < workers) stopBarrier.wait();
        }

        return startTime;

    }

    void printSpeedReport(TransactionLog logger, long startTime, long stopTime, int workers, int xidCount) throws IOException {
        long mc = ((long) xidCount) * workers;
        long duration = (stopTime - startTime);
        long xidsPerSecond = (totalXidCount * 1000 / (duration));
        int averageForceTime = logger.getAverageForceTime();
        int averageBytesPerForce = logger.getAverageBytesPerForce();
        long averageLatency = totalDuration / totalXidCount;
        resultsXML.write("<run><workers>" + workers + "</workers><xids-per-thread>" + xidCount + "</xids-per-thread><expected-total-xids>" + mc + "</expected-total-xids><missing-xids>" + (mc - totalXidCount) + "</missing-xids><totalDuration-milliseconds>" + duration + "</totalDuration-milliseconds><xids-per-second>" + xidsPerSecond + "</xids-per-second></run>\n");
        resultsXML.write(logger.getXMLStats() + "\n");
        resultsCSV.write("" + workers + "," + xidCount + "," + mc + "," + (mc - totalXidCount) + "," + duration + "," + xidsPerSecond + "," + averageForceTime + "," + averageBytesPerForce + "," + averageLatency + "\n");

    }

}