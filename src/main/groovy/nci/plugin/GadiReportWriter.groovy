package nci.plugin

import groovy.transform.CompileStatic

import java.nio.file.Path
import java.time.Duration

/**
 * Writes one usage report file. Call {@link #start()} before the first
 * task, {@link #recordTask} for each task, and {@link #finish()} at the end.
 *
 * Every report has the same fields for each task, listed in {@link #FIELDS}:
 * the CSV report's columns and the JSON report's keys.
 */
@CompileStatic
abstract class GadiReportWriter {

    /** File name of every report, before its format's extension. */
    static final String REPORT_NAME = 'UsageReport'

    /** The fields reported for each task, in order. */
    static final List<String> FIELDS = [
        'work_dir',
        'name',
        'process',
        'queue',
        'service_units',
        'cpus',
        'cpu_time',
        'used_walltime',
        'requested_walltime',
        'used_memory',
        'requested_memory',
        'used_jobfs',
        'requested_jobfs',
        'efficiency',
        'exit_code',
    ].asImmutable()

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
        record(row(task, usage))
    }

    /** Records a task that did not run on Gadi, so has no usage values. */
    final void recordTask(GadiTask task) {
        record(row(task, new GadiUsageRecord()))
    }

    /** Called once after the last task. */
    void finish() {
    }

    /** Records one task's row: its {@link #FIELDS}, in order. */
    protected abstract void record(Map<String, String> row)

    /** A task's report values, keyed and ordered as {@link #FIELDS}. */
    static Map<String, String> row(GadiTask task, GadiUsageRecord usage) {
        Double efficiency = GadiEfficiencyCalculator.calculate(usage)

        Map<String, String> row = [
            work_dir:           task.workDir,
            name:               task.name,
            process:            task.process,
            queue:              task.queue,
            service_units:      usage.serviceUnits?.toPlainString(),
            cpus:               usage.cpus?.toString(),
            cpu_time:           format(usage.cpuTime),
            used_walltime:      format(usage.walltimeUsed),
            requested_walltime: format(usage.walltimeRequested),
            used_memory:        usage.memoryUsed?.toString(),
            requested_memory:   usage.memoryRequested?.toString(),
            used_jobfs:         usage.jobfsUsed?.toString(),
            requested_jobfs:    usage.jobfsRequested?.toString(),
            efficiency:         efficiency == null ? null : String.format('%.2f', efficiency),
            exit_code:          usage.exitCode?.toString(),
        ] as LinkedHashMap<String, String>
        return row
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
