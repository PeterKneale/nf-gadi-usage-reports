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

import java.nio.file.Path
import java.nio.file.Paths

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
    }

    @Override
    void onFlowCreate(Session session) {
        log.info "Pipeline is starting! 🚀"
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
        GadiUsageRecord usage = reader.read(workDir(trace))
        report.recordTask(task, usage)
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        GadiTask task = task(handler, trace)
        if (!isGadiJob(trace)) {
            report.recordTask(task)
            return
        }

        // The task has only just finished, so PBS may still be writing its log
        GadiUsageRecord usage = reader.waitAndRead(workDir(trace))
        report.recordTask(task, usage)
    }

    @Override
    void onFlowComplete() {
        report.finish()
        publisher.publish(report.paths)
        log.info "Pipeline complete! 👋"
    }

    /** False for a task with no job ID, e.g. one that failed before it was submitted. */
    private static boolean isGadiJob(TraceRecord trace) {
        String jobId = trace.get('native_id') as String
        return jobId != null && jobId.contains('gadi-pbs')
    }

    private static Path workDir(TraceRecord trace) {
        return Path.of(trace.get('workdir').toString())
    }

    private static GadiTask task(TaskHandler handler, TraceRecord trace) {
        String queue = trace.get('queue') as String
        String workDir = trace.get('workdir') as String
        return GadiTask.of(handler.task.name, queue, workDir)
    }
}
