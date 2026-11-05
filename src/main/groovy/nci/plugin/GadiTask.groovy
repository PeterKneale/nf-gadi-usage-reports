package nci.plugin

import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * A task as it appears in the usage report: who it is and where it ran,
 * as opposed to the resources it used ({@link GadiUsageRecord}).
 */
@Immutable
@CompileStatic
class GadiTask {
    /** The task name, e.g. "ALIGN (sample_1)" or "WORKFLOW:ALIGN (sample_1)". */
    String name

    /** The process the task belongs to; reports group tasks by it. */
    String process

    /** The Gadi queue the task ran in; null if unknown. */
    String queue

    /**
     * The task's work directory, relative to the run's work directory or
     * the folder searched, e.g. "94/348cfc33..."; null if unknown.
     */
    String workDir

    /** A task whose process is taken from its name, e.g. "ALIGN" for "ALIGN (sample_1)". */
    static GadiTask of(String name, String queue = null, String workDir = null) {
        return new GadiTask(name, processName(name), queue, workDir)
    }

    /** The process a task name belongs to: everything before the first space. */
    static String processName(String taskName) {
        return taskName.split(/\s+/)[0]
    }
}
