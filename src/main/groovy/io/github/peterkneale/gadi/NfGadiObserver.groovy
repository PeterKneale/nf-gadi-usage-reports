/*
 * Copyright 2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.peterkneale.gadi

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records the Gadi resource usage of each task in a pipeline run in a
 * {@link GadiUsageReport}, reading it from the task's work directory
 * with a {@link GadiUsageReader}. At the end of the run the reports are
 * published to {@code gadi.publishTo}, if set.
 */
@Slf4j
@CompileStatic
class NfGadiObserver implements TraceObserver {

    final GadiUsageReport report
    private final GadiUsageReader reader
    private final GadiReportPublisher publisher

    /** The run's work directory, which task work directories are reported relative to. */
    private final Path runWorkDir

    /**
     * Waits for finished tasks' logs, see {@link #onProcessComplete}. Daemon
     * threads, so a wait still in progress never keeps Nextflow from exiting.
     */
    private final ExecutorService readers = Executors.newCachedThreadPool { Runnable waiting ->
        Thread thread = new Thread(waiting, 'nf-gadi-usage-reader')
        thread.daemon = true
        return thread
    }

    /** Writes the reports to the launch directory. */
    NfGadiObserver(Session session) {
        this(session, Paths.get(''), new GadiUsageReader())
    }

    /**
     * @param reportFolder where to write the reports
     * @param reader reads each task's usage, e.g. with shorter waits in tests
     */
    NfGadiObserver(Session session, Path reportFolder, GadiUsageReader reader) {
        this.report = new GadiUsageReport(reportFolder)
        this.reader = reader
        this.publisher = new GadiReportPublisher(session.config.navigate('gadi.publishTo'))
        this.runWorkDir = session.workDir
    }

    @Override
    void onFlowBegin() {
        report.start()
    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        GadiTask task = task(handler, trace)
        if (!isGadiJob(trace)) {
            report.recordTask(task)
            return
        }

        // A cached task finished in an earlier run, so its log is complete if it still exists
        GadiUsageRecord usage = readUsage(task) { reader.read(workDir(trace)) }
        report.recordTask(task, usage)
    }

    /**
     * The task has only just finished, so PBS may still be writing its log.
     * Waiting for it here would hold up Nextflow, which calls this on the
     * thread that handles every task's completion, so the wait is done on
     * another thread and the task is recorded when its log is read.
     */
    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        GadiTask task = task(handler, trace)
        if (!isGadiJob(trace)) {
            report.recordTask(task)
            return
        }

        Path workDir = workDir(trace)
        readers.execute {
            try {
                GadiUsageRecord usage = readUsage(task) { reader.waitAndRead(workDir) }
                report.recordTask(task, usage)
            } catch (Exception e) {
                log.error "Cannot record ${task.name} in the usage report: ${e}"
            }
        }
    }

    /** Waits for the logs of the last tasks to be read, then writes and publishes the reports. */
    @Override
    void onFlowComplete() {
        awaitReaders()
        report.finish()
        publisher.publish(report.paths)
    }

    /** Time allowed, beyond the reader's own wait, for reading and recording the last tasks' logs. */
    static final long AWAIT_MARGIN_MILLIS = 30_000

    private void awaitReaders() {
        readers.shutdown()
        // A task that finished just before the run did may wait its full time budget
        long timeoutMillis = reader.maxWaitMillis + reader.pollIntervalMillis + AWAIT_MARGIN_MILLIS
        if (!readers.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
            log.warn "Gave up waiting for the job logs of the last tasks to finish, so they are missing from the usage reports"
        }
    }

    /**
     * Reads a task's usage, reporting the task without usage values if its
     * log cannot be read, e.g. a permissions or file system error. The run
     * itself is never affected by a problem with the report.
     */
    private static GadiUsageRecord readUsage(GadiTask task, Closure<GadiUsageRecord> read) {
        try {
            return read.call()
        } catch (Exception e) {
            log.warn "Cannot read the job log of ${task.name}, reporting this task without usage values: ${e}"
            return new GadiUsageRecord()
        }
    }

    /** False for a task with no job ID, e.g. one that failed before it was submitted. */
    private static boolean isGadiJob(TraceRecord trace) {
        String jobId = trace.get('native_id') as String
        return jobId != null && jobId.contains('gadi-pbs')
    }

    private static Path workDir(TraceRecord trace) {
        return Path.of(trace.get('workdir').toString())
    }

    private GadiTask task(TaskHandler handler, TraceRecord trace) {
        String queue = trace.get('queue') as String
        return GadiTask.of(handler.task.name, queue, reportedWorkDir(workDir(trace)))
    }

    /**
     * A task's work directory relative to the run's, e.g. "94/348cfc33...",
     * as the command line tool reports it. Absolute if the task's work
     * directory is somewhere else.
     */
    private String reportedWorkDir(Path workDir) {
        if (runWorkDir != null && workDir.startsWith(runWorkDir)) {
            return runWorkDir.relativize(workDir).toString()
        }
        return workDir.toString()
    }
}
