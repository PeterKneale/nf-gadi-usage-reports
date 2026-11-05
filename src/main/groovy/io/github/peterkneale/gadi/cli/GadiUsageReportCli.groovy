package io.github.peterkneale.gadi.cli

import groovy.transform.CompileStatic
import io.github.peterkneale.gadi.GadiTask
import io.github.peterkneale.gadi.GadiUsageReader
import io.github.peterkneale.gadi.GadiUsageRecord
import io.github.peterkneale.gadi.GadiUsageReport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Writes one usage report for the Gadi job logs already in a folder,
 * without Nextflow: finds every {@code .command.log} below the folder
 * and records each as one task in UsageReport.csv and UsageReport.json.
 *
 * <pre>
 * java -jar gadi-usage-report.jar &lt;folder&gt;
 * </pre>
 */
@CompileStatic
class GadiUsageReportCli {

    static final String USAGE = '''\
        Usage: java -jar gadi-usage-report.jar <folder>

        Writes UsageReport.csv and UsageReport.json, in the current directory,
        for every Gadi job log (.command.log) found in <folder> and its
        subfolders, e.g. a Nextflow work directory holding any number of
        pipeline runs.

          --help   show this message
        '''.stripIndent()

    /** How many incomplete or unreadable logs to list by path in the summary. */
    static final int MAX_LISTED = 10

    static void main(String[] args) {
        System.exit(run(args as List<String>, System.out, System.err, Paths.get('')))
    }

    /**
     * @param outDir where to write the reports: the current directory when
     *        run from the command line
     * @return the exit code: 0 on success, 1 if no logs were found or any
     *         could not be read, 2 if the arguments are wrong
     */
    static int run(List<String> args, PrintStream out, PrintStream err, Path outDir) {
        Path folder = null

        for (String arg : args) {
            if (arg == '--help' || arg == '-h') {
                out.print(USAGE)
                return 0
            } else if (arg.startsWith('-')) {
                err.println("Unknown option ${arg}")
                err.print(USAGE)
                return 2
            } else if (folder == null) {
                folder = Paths.get(arg)
            } else {
                err.println("Only one folder can be given")
                err.print(USAGE)
                return 2
            }
        }

        if (folder == null) {
            err.print(USAGE)
            return 2
        }
        if (!Files.isDirectory(folder)) {
            err.println("Not a folder: ${folder}")
            return 2
        }

        List<Path> workDirs = findWorkDirs(folder)
        if (workDirs.isEmpty()) {
            err.println("No ${GadiUsageReader.JOB_LOG} files found in ${folder}")
            return 1
        }

        Files.createDirectories(outDir)
        GadiUsageReport report = new GadiUsageReport(outDir)
        GadiUsageReader reader = new GadiUsageReader()
        List<String> incomplete = []
        List<String> unreadable = []

        report.start()
        for (Path workDir : workDirs) {
            String relative = relativeName(folder, workDir)
            NextflowTaskScript script = NextflowTaskScript.read(workDir)

            // Named as Nextflow named it; by its work directory if the job script is missing
            String taskName = script.taskName ?: relative
            GadiTask task = GadiTask.of(taskName, script.queue, relative)

            GadiUsageRecord usage
            try {
                usage = reader.read(workDir)
                if (!usage.isComplete()) {
                    incomplete << relative
                }
            } catch (Exception e) {
                unreadable << "${relative}: ${e.message}".toString()
                usage = new GadiUsageRecord()
            }
            report.recordTask(task, usage)
        }
        report.finish()

        printSummary(out, folder, workDirs.size(), incomplete, unreadable, report.paths)
        return unreadable.isEmpty() ? 0 : 1
    }

    /** Every folder below the given one that holds a job log, in path order. */
    static List<Path> findWorkDirs(Path folder) {
        Stream<Path> paths = Files.walk(folder)
        try {
            return paths
                .filter { Path p -> p.fileName?.toString() == GadiUsageReader.JOB_LOG && Files.isRegularFile(p) }
                .map { Path p -> p.parent }
                .sorted()
                .collect(Collectors.toList())
        } finally {
            paths.close()
        }
    }

    /** A work directory's path relative to the folder searched, e.g. "run1/94/348cfc33...". */
    private static String relativeName(Path folder, Path workDir) {
        String relative = folder.relativize(workDir).toString()
        return relative.isEmpty() ? '.' : relative
    }

    private static void printSummary(PrintStream out, Path folder, int found,
                                     List<String> incomplete, List<String> unreadable, List<Path> reports) {
        int complete = found - incomplete.size() - unreadable.size()
        out.println("Found ${found} job ${found == 1 ? 'log' : 'logs'} in ${folder}")
        out.println("  ${complete} complete")
        out.println("  ${incomplete.size()} incomplete (some usage values missing)")
        listSome(out, incomplete)
        out.println("  ${unreadable.size()} unreadable")
        listSome(out, unreadable)
        reports.each { Path path -> out.println("Wrote ${path.toAbsolutePath().normalize()}") }
    }

    private static void listSome(PrintStream out, List<String> items) {
        items.take(MAX_LISTED).each { out.println("      ${it}") }
        if (items.size() > MAX_LISTED) {
            out.println("      ... and ${items.size() - MAX_LISTED} more")
        }
    }
}
