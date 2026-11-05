package io.github.peterkneale.gadi

import groovy.transform.CompileStatic

import java.nio.file.Path

/**
 * The usage report for one run: a CSV and a JSON report written side by
 * side in one folder. Call {@link #start()} first, {@link #recordTask}
 * for each task, and {@link #finish()} at the end.
 */
@CompileStatic
class GadiUsageReport {

    private final List<GadiReportWriter> writers

    /** @param folder where to write UsageReport.csv and UsageReport.json */
    GadiUsageReport(Path folder) {
        this.writers = [
            new GadiReportWriterCsv(folder),
            new GadiReportWriterJson(folder),
        ] as List<GadiReportWriter>
    }

    /** The report files, CSV then JSON. */
    List<Path> getPaths() {
        return writers*.path
    }

    void start() {
        writers*.start()
    }

    /** Records a task that ran on Gadi; usage values are empty if its log could not be read. */
    void recordTask(GadiTask task, GadiUsageRecord usage) {
        writers.each { it.recordTask(task, usage) }
    }

    /** Records a task that did not run on Gadi, so has no usage values. */
    void recordTask(GadiTask task) {
        writers.each { it.recordTask(task) }
    }

    void finish() {
        writers*.finish()
    }
}
