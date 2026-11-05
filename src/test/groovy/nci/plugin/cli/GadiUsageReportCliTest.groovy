package nci.plugin.cli

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Runs the command line tool against the scenario job logs in
 * src/test/resources/nci/plugin/scenarios and compares the reports it
 * writes with the golden copies in src/test/resources/nci/plugin/cli/golden.
 *
 * The golden reports were checked value by value against the hand-checked
 * table in GadiScenarioTest. After an intended change to the report, run
 *
 *   make cli
 *   JAR=$PWD/build/libs/gadi-usage-report-1.0.0.jar
 *   (cd src/test/resources/nci/plugin/cli/golden && java -jar "$JAR" ../../scenarios)
 *
 * then review the diff of the golden files before committing it.
 */
class GadiUsageReportCliTest extends Specification {

    @TempDir
    Path tmp

    ByteArrayOutputStream out = new ByteArrayOutputStream()
    ByteArrayOutputStream err = new ByteArrayOutputStream()

    /** Runs the tool as from the command line, with the reports written to outDir instead of the current directory. */
    private int run(List<String> args, Path outDir = tmp) {
        GadiUsageReportCli.run(args, new PrintStream(out, true), new PrintStream(err, true), outDir)
    }

    private static Path resource(String path) {
        Path.of(GadiUsageReportCliTest.getResource(path).toURI())
    }

    private static Path scenarios() { resource('/nci/plugin/scenarios') }

    private static Path golden(String file) { resource("/nci/plugin/cli/golden/${file}") }

    def "scenario logs: the CSV report matches the golden copy"() {
        when:
        int exitCode = run([scenarios().toString()])

        then:
        exitCode == 0
        tmp.resolve('UsageReport.csv').readLines() == golden('UsageReport.csv').readLines()
    }

    def "scenario logs: the JSON report matches the golden copy"() {
        when:
        run([scenarios().toString()])

        then: 'compared as JSON, since the order of the process groups is not fixed'
        def slurper = new JsonSlurper()
        slurper.parse(tmp.resolve('UsageReport.json').toFile()) == slurper.parse(golden('UsageReport.json').toFile())
    }

    def "scenario logs: the summary counts every log"() {
        when:
        run([scenarios().toString()])

        then:
        def summary = out.toString()
        summary.contains('Found 13 job logs')
        summary.contains('13 complete')
        summary.contains('0 incomplete')
        summary.contains('0 unreadable')
        summary.contains("Wrote ${tmp.resolve('UsageReport.csv').toAbsolutePath().normalize()}")
    }

    def "a task in a Nextflow work directory is named as Nextflow named it, from its job script"() {
        given:
        nextflowWorkDir('run1/94/348cfc33f75979f408e9e9d20d4d6e', 'MAIN:COUNT (sample_1, lane2)')

        when:
        int exitCode = run([tmp.resolve('work').toString()], tmp.resolve('out'))

        then: 'name and queue from .command.run, process from the name, work dir relative to the folder searched'
        exitCode == 0
        tmp.resolve('out/UsageReport.csv').readLines()[1] ==
            'run1/94/348cfc33f75979f408e9e9d20d4d6e,"MAIN:COUNT (sample_1, lane2)",MAIN:COUNT,normal,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0'
    }

    def "every task in every run below the folder is reported, each traceable by its work dir"() {
        given: 'two runs of the same pipeline, so the same task names appear in both'
        nextflowWorkDir('run1/94/348cfc33f75979f408e9e9d20d4d6e', 'MAIN:COUNT (sample_1)')
        nextflowWorkDir('run1/a1/0b2c3d4e5f60718293a4b5c6d7e8f9', 'MAIN:ALIGN (sample_1)')
        nextflowWorkDir('run2/17/ffeeddccbbaa99887766554433221100', 'MAIN:COUNT (sample_1)')

        when:
        int exitCode = run([tmp.resolve('work').toString()], tmp.resolve('out'))
        def rows = tmp.resolve('out/UsageReport.csv').readLines().drop(1)
        def json = new JsonSlurper().parse(tmp.resolve('out/UsageReport.json').toFile()) as Map

        then: 'one report holds all three tasks'
        exitCode == 0
        out.toString().contains('Found 3 job logs')
        rows*.split(',')*.getAt(1) == ['MAIN:COUNT (sample_1)', 'MAIN:ALIGN (sample_1)', 'MAIN:COUNT (sample_1)']
        rows*.split(',')*.getAt(0) == [
            'run1/94/348cfc33f75979f408e9e9d20d4d6e',
            'run1/a1/0b2c3d4e5f60718293a4b5c6d7e8f9',
            'run2/17/ffeeddccbbaa99887766554433221100',
        ]

        and: 'the JSON groups the same process across runs'
        json.keySet() == ['MAIN:COUNT', 'MAIN:ALIGN'] as Set
        json['MAIN:COUNT']*.work_dir == ['run1/94/348cfc33f75979f408e9e9d20d4d6e', 'run2/17/ffeeddccbbaa99887766554433221100']
    }

    def "without a job script, a task is named by its work dir"() {
        given:
        Path workDir = tmp.resolve('work/logs_only/task_1')
        Files.createDirectories(workDir)
        Files.copy(scenarios().resolve('tool_featurecounts/.command.log'), workDir.resolve('.command.log'))

        when:
        run([tmp.resolve('work').toString()], tmp.resolve('out'))

        then:
        tmp.resolve('out/UsageReport.csv').readLines()[1] ==
            'logs_only/task_1,logs_only/task_1,logs_only/task_1,,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0'
    }

    @IgnoreIf({ System.getProperty('user.name') == 'root' })   // root can read anything
    def "a log that cannot be read is listed, the rest are still reported, and the exit code is 1"() {
        given:
        nextflowWorkDir('aa/readable', 'MAIN:COUNT (sample_1)')
        Path unreadable = nextflowWorkDir('bb/unreadable', 'MAIN:COUNT (sample_2)').resolve('.command.log')
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString('---------'))

        when:
        int exitCode = run([tmp.resolve('work').toString()], tmp.resolve('out'))

        then:
        exitCode == 1
        out.toString().contains('1 complete')
        out.toString().contains('1 unreadable')
        out.toString().contains('bb/unreadable')
        tmp.resolve('out/UsageReport.csv').readLines().size() == 3   // header and both tasks
    }

    def "a folder with no job logs gives exit code 1"() {
        when:
        int exitCode = run([tmp.toString()], tmp.resolve('out'))

        then:
        exitCode == 1
        err.toString().contains('No .command.log files found')
    }

    def "wrong arguments print the usage and give exit code 2: #description"() {
        expect:
        run(args) == 2
        err.toString().contains('Usage:')

        where:
        description           | args
        'no folder'           | []
        'unknown option'      | ['--bogus', '.']
        '--out is not an option'| ['.', '--out', 'reports']
        'two folders'         | ['.', '..']
    }

    def "a folder that does not exist gives exit code 2"() {
        expect:
        run([tmp.resolve('missing').toString()]) == 2
        err.toString().contains('Not a folder')
    }

    def "--help prints the usage and gives exit code 0"() {
        expect:
        run(['--help']) == 0
        out.toString().contains('Usage:')
    }

    /**
     * A Nextflow-style work directory below tmp/work, holding the featurecounts
     * scenario log and a job script with Nextflow's header and PBS directives.
     */
    private Path nextflowWorkDir(String path, String taskName) {
        Path workDir = tmp.resolve("work/${path}")
        Files.createDirectories(workDir)
        Files.copy(scenarios().resolve('tool_featurecounts/.command.log'), workDir.resolve('.command.log'))
        workDir.resolve('.command.run').text = """\
            |#!/bin/bash
            |#PBS -N nf-COUNT_sample
            |#PBS -q normal
            |### ---
            |### name: '${taskName}'
            |### container: 'example'
            |""".stripMargin()
        return workDir
    }
}
