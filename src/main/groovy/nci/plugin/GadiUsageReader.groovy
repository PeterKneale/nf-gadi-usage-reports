package nci.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path

/**
 * Gets the {@link GadiUsageRecord} for a task from its work directory,
 * by reading the job log that Gadi's PBS writes there.
 *
 * Use {@link #read} for a task that has already finished, and
 * {@link #waitAndRead} for one that has only just finished, whose log
 * PBS may still be writing.
 */
@Slf4j
@CompileStatic
class GadiUsageReader {

    /** The job log PBS writes in each task's work directory. */
    static final String JOB_LOG = '.command.log'

    /** How often to check for the job log while PBS is still writing it. */
    final long pollIntervalMillis

    /** How long to wait for the job log before giving up. */
    final long maxWaitMillis

    GadiUsageReader(long pollIntervalMillis = 10_000, long maxWaitMillis = 120_000) {
        this.pollIntervalMillis = pollIntervalMillis
        this.maxWaitMillis = maxWaitMillis
    }

    /** The job log in a task's work directory. */
    static Path jobLog(Path workDir) {
        return workDir.resolve(JOB_LOG)
    }

    /**
     * Reads the usage of a task that has already finished.
     *
     * @return the usage, with empty values if the job log is missing,
     *         e.g. because the work directory has been cleaned up
     */
    GadiUsageRecord read(Path workDir) {
        Path logFile = jobLog(workDir)
        if (!Files.exists(logFile)) {
            log.warn "Cannot find log file ${logFile}, reporting this task without usage values"
            return new GadiUsageRecord()
        }
        return new GadiUsageParser(logFile).parse()
    }

    /**
     * Waits for PBS to write a task's job log, then reads it. One time
     * budget covers both waiting for the log to appear and for it to be
     * complete.
     *
     * @return the usage, with empty values if the log never appeared
     */
    GadiUsageRecord waitAndRead(Path workDir) {
        Path logFile = jobLog(workDir)

        long waited = 0
        while (!Files.exists(logFile) && waited < maxWaitMillis) {
            log.info "Waiting for report file to be created: ${logFile}"
            sleep(pollIntervalMillis)
            waited += pollIntervalMillis
        }

        if (!Files.exists(logFile)) {
            log.warn "Cannot find log file ${logFile} after ${maxWaitMillis.intdiv(1000)}s, reporting this task without usage values"
            return new GadiUsageRecord()
        }

        GadiUsageParser parser = new GadiUsageParser(logFile)

        // Poll quietly: fields are expected to be missing until PBS finishes writing the log
        GadiUsageRecord usage = parser.parse(false)
        while (!usage.isComplete() && waited < maxWaitMillis) {
            sleep(pollIntervalMillis)
            waited += pollIntervalMillis
            log.info "Waiting for log file to complete"
            usage = parser.parse(false)
        }

        // Final read warns once about any field still missing
        return parser.parse()
    }
}
