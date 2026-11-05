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
    final void recordTask(GadiTask task, GadiUsageRecord usage) {
        Map<String, String> values = reportValues(usage)
        values['queue'] = task.queue
        record(task, values)
    }

    /** Records a task that did not run on Gadi, so has no usage values. */
    final void recordTask(GadiTask task) {
        record(task, [:])
    }

    /** Called once after the last task. */
    void finish() {
    }

    /**
     * Records one task's report values, keyed as in the JSON report
     * (see {@link #reportValues}) plus 'queue' for Gadi tasks.
     */
    protected abstract void record(GadiTask task, Map<String, String> values)

    private static Map<String, String> reportValues(GadiUsageRecord usage) {
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
