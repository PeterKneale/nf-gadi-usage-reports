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

package nci.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Records the Gadi resource usage of each task in a pipeline run,
 * reading it from the task's job log, and writes it to both a CSV and
 * a JSON report. At the end of the run the reports are published to
 * {@code gadi.publishTo}, if set.
 */
@Slf4j
@CompileStatic
class NfGadiObserver implements TraceObserver {

    final List<GadiReportWriter> writers
    private final GadiReportPublisher publisher

    /** How often to check for the job log while PBS is still writing it. */
    long pollIntervalMillis = 10_000

    /** How long to wait for the job log before reporting the task without usage values. */
    long maxWaitMillis = 120_000

    /** Writes the reports to the launch directory. */
    NfGadiObserver(Session session) {
        this(session, Paths.get(''))
    }

    /** @param reportFolder where to write the reports */
    NfGadiObserver(Session session, Path reportFolder) {
        this.writers = [
            new GadiReportWriterCsv(reportFolder),
            new GadiReportWriterJson(reportFolder),
        ] as List<GadiReportWriter>
        this.publisher = new GadiReportPublisher(session.config.navigate('gadi.publishTo'))
    }

    @Override
    void onFlowCreate(Session session) {
        log.info "Pipeline is starting! 🚀"
    }

    @Override
    void onFlowBegin() {
        writers*.start()
    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        String taskName = handler.task.name
        if (!isGadiJob(trace)) {
            writers.each { it.recordTask(taskName) }
            return
        }

        Path report = jobLog(trace)
        GadiUsage usage
        if (Files.exists(report)) {
            usage = new GadiUsageParser(report).parse()
        } else {
            // The work directory of a cached task may have been cleaned up
            log.warn "Cannot find log file ${report}, reporting this task without usage values"
            usage = new GadiUsage()
        }
        String queue = trace.get('queue') as String
        writers.each { it.recordTask(taskName, usage, queue) }
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        String taskName = handler.task.name
        if (!isGadiJob(trace)) {
            writers.each { it.recordTask(taskName) }
            return
        }

        GadiUsage usage = waitForUsage(jobLog(trace))
        String queue = trace.get('queue') as String
        writers.each { it.recordTask(taskName, usage, queue) }
    }

    @Override
    void onFlowComplete() {
        writers*.finish()
        publisher.publish(writers*.path)
        log.info "Pipeline complete! 👋"
    }

    /**
     * Waits for PBS to write the job log, then reads it. One time budget
     * covers both waiting for the log to appear and for it to be complete.
     *
     * @return the usage, with empty values if the log never appeared
     */
    private GadiUsage waitForUsage(Path report) {
        long waited = 0
        while (!Files.exists(report) && waited < maxWaitMillis) {
            log.info "Waiting for report file to be created: ${report}"
            sleep(pollIntervalMillis)
            waited += pollIntervalMillis
        }

        if (!Files.exists(report)) {
            log.warn "Cannot find log file ${report} after ${maxWaitMillis.intdiv(1000)}s, reporting this task without usage values"
            return new GadiUsage()
        }

        GadiUsageParser parser = new GadiUsageParser(report)

        // Poll quietly: fields are expected to be missing until PBS finishes writing the log
        GadiUsage usage = parser.parse(false)
        while (!usage.isComplete() && waited < maxWaitMillis) {
            sleep(pollIntervalMillis)
            waited += pollIntervalMillis
            log.info "Waiting for log file to complete"
            usage = parser.parse(false)
        }

        // Final read warns once about any field still missing
        return parser.parse()
    }

    private static boolean isGadiJob(TraceRecord trace) {
        String jobId = (trace.get('native_id') as String).trim()
        return jobId.contains('gadi-pbs')
    }

    private static Path jobLog(TraceRecord trace) {
        String workdir = trace.get('workdir').toString()
        return Path.of(workdir + '/.command.log')
    }
}
