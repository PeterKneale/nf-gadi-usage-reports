package nci.plugin

import groovy.transform.CompileStatic

import java.nio.file.Path
import java.time.Duration

/**
 * Writes one usage report file. Call {@link #start()} before the first
 * task, {@link #recordTask} for each task, and {@link #finish()} at the end.
 */
@CompileStatic
abstract class GadiReportWriter {

    /** File name of every report, before its format's extension. */
    static final String REPORT_NAME = 'UsageReport'

    /** The report file this writer writes. */
    final Path path

    protected GadiReportWriter(Path path) {
        this.path = path
    }

    /** Called once before any task is recorded. */
    void start() {
    }

    /** Records a task that ran on Gadi; usage values are empty if its log could not be read. */
    final void recordTask(String taskName, GadiUsage usage, String queue) {
        Map<String, String> values = reportValues(usage)
        values['queue'] = queue
        record(taskName, values)
    }

    /** Records a task that did not run on Gadi, so has no usage values. */
    final void recordTask(String taskName) {
        record(taskName, [:])
    }

    /** Called once after the last task. */
    void finish() {
    }

    /**
     * Records one task's report values, keyed as in the JSON report
     * (see {@link #reportValues}) plus 'queue' for Gadi tasks.
     */
    protected abstract void record(String taskName, Map<String, String> values)

    /** The process a task belongs to, e.g. "ALIGN" for "ALIGN (sample_1)". */
    protected static String processName(String taskName) {
        return taskName.split(/\s+/)[0]
    }

    private static Map<String, String> reportValues(GadiUsage usage) {
        Double efficiency = GadiEfficiencyCalculator.calculate(usage)

        // Key order matters: it is the field order in the JSON report
        return [
            sus:               usage.serviceUnits?.toPlainString(),
            walltime:          format(usage.walltimeUsed),
            cputime:           format(usage.cpuTime),
            cpus:              usage.cpus?.toString(),
            memory:            usage.memoryUsed?.toString(),
            efficiency:        efficiency == null ? null : String.format('%.2f', efficiency),
            exitCode:          usage.exitCode?.toString(),
            requestedMemory:   usage.memoryRequested?.toString(),
            requestedWalltime: format(usage.walltimeRequested),
            requestedJobFS:    usage.jobfsRequested?.toString(),
            usedJobFS:         usage.jobfsUsed?.toString(),
        ]
    }

    /** Formats a duration as Gadi logs it, e.g. "13:55:28". */
    private static String format(Duration duration) {
        if (duration == null) {
            return null
        }
        long seconds = duration.seconds
        return String.format('%02d:%02d:%02d', seconds.intdiv(3600), (seconds % 3600).intdiv(60), seconds % 60)
    }
}
