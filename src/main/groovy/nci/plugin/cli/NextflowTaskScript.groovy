package nci.plugin.cli

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher

/**
 * What Nextflow wrote about a task into its job script ({@code .command.run}):
 * the task name from the script's header, and the queue from its PBS
 * directives.
 */
@CompileStatic
class NextflowTaskScript {

    /** The job script Nextflow writes in each task's work directory. */
    static final String TASK_SCRIPT = '.command.run'

    /**
     * The task name from the header line {@code ### name: '...'}, e.g.
     * "WORKFLOW:ALIGN (sample_1)"; null if unknown.
     */
    final String taskName

    /** The queue from the PBS directive {@code #PBS -q}; null if unknown. */
    final String queue

    private NextflowTaskScript(String taskName, String queue) {
        this.taskName = taskName
        this.queue = queue
    }

    /** Reads the job script in a work directory; values are null if it is missing. */
    static NextflowTaskScript read(Path workDir) {
        Path script = workDir.resolve(TASK_SCRIPT)
        if (!Files.isRegularFile(script)) {
            return new NextflowTaskScript(null, null)
        }

        String taskName = null
        String queue = null
        // Decode leniently: the script may contain characters that are not valid UTF-8
        String text = new String(Files.readAllBytes(script), StandardCharsets.UTF_8)
        text.eachLine { String line ->
            Matcher name = line =~ /^### name: '(.*)'\s*$/
            if (taskName == null && name.find()) {
                taskName = name.group(1)
            }
            Matcher queueDirective = line =~ /^#PBS\s+-q\s+(\S+)/
            if (queueDirective.find()) {
                queue = queueDirective.group(1)
            }
        }
        return new NextflowTaskScript(taskName, queue)
    }
}
