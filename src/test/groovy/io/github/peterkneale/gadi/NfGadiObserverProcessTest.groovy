package io.github.peterkneale.gadi

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Drives the observer end to end, writing real reports to a temporary folder.
 *
 * A task that finishes during the run is recorded in the background, once
 * its log has been read, so a test completes the run with onFlowComplete()
 * before reading the reports.
 */
class NfGadiObserverProcessTest extends Specification {

    static final String HEADER = 'work_dir,name,process,queue,service_units,cpus,cpu_time,used_walltime,requested_walltime,' +
        'used_memory,requested_memory,used_jobfs,requested_jobfs,efficiency,exit_code'

    @TempDir
    Path tmp

    NfGadiObserver observer
    Path report

    def setup() {
        report = tmp.resolve('UsageReport.csv')
        observer = observer(new GadiUsageReader(1, 3))   // keep the wait loops fast
        observer.onFlowBegin()            // writes the CSV header
    }

    /** An observer for a run whose work directory is the scenarios folder, writing to tmp and publishing to tmp/published. */
    private NfGadiObserver observer(GadiUsageReader reader) {
        def config = [gadi: [publishTo: tmp.resolve('published').toString()]]
        def session = Stub(Session) {
            getConfig() >> config
            getWorkDir() >> scenarios()
        }
        return new NfGadiObserver(session, tmp, reader)
    }

    private TaskHandler handler(String name) {
        Stub(TaskHandler) { getTask() >> Stub(TaskRun) { getName() >> name } }
    }

    private TraceRecord trace(Path workdir) {
        Stub(TraceRecord) {
            get('native_id') >> '123456789.gadi-pbs'
            get('workdir') >> workdir.toString()
            get('queue') >> 'normal'
        }
    }

    private static Path scenarios() {
        Path.of(NfGadiObserverProcessTest.getResource('scenarios').toURI())
    }

    private static Path scenario(String name) {
        scenarios().resolve(name)
    }

    def "completed task with a job log is reported with its usage values"() {
        when:
        observer.onProcessComplete(handler('COUNT (sample_1)'), trace(scenario('tool_featurecounts')))
        observer.onFlowComplete()

        then:
        report.readLines() == [
            HEADER,
            'tool_featurecounts,COUNT (sample_1),COUNT,normal,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0',
        ]
    }

    def "work_dir is relative to the run's work directory, or absolute for a task somewhere else"() {
        when:
        observer.onProcessCached(handler('ALIGN (sample_1)'), trace(scenario('tool_star')))
        observer.onProcessCached(handler('ALIGN (sample_2)'), trace(tmp.resolve('elsewhere/ab/cdef12')))

        then: 'as the command line tool reports them when pointed at the run\'s work directory'
        report.readLines()*.split(',')*.getAt(0) == ['work_dir', 'tool_star', tmp.resolve('elsewhere/ab/cdef12').toString()]
    }

    def "onProcessComplete returns at once, so waiting for a job log does not hold up the pipeline"() {
        given: 'a reader that waits two seconds for a log that never appears'
        def observer = observer(new GadiUsageReader(100, 2_000))
        observer.onFlowBegin()

        when:
        long start = System.currentTimeMillis()
        observer.onProcessComplete(handler('ALIGN (sample_1)'), trace(tmp.resolve('no_log_yet')))
        long returned = System.currentTimeMillis() - start
        observer.onFlowComplete()
        long completed = System.currentTimeMillis() - start

        then: 'the wait happened between the two calls, and the run waited for it before finishing'
        returned < 1_000
        completed >= 2_000
        report.readLines()[1] == "${tmp.resolve('no_log_yet')},ALIGN (sample_1),ALIGN,normal,,,,,,,,,,,".toString()
    }

    def "a job log written after the task finished is still read, and the task reported with its usage values"() {
        given:
        def observer = observer(new GadiUsageReader(50, 5_000))
        observer.onFlowBegin()
        Path workDir = tmp.resolve('late')
        Files.createDirectories(workDir)

        when: 'PBS writes the log a little after Nextflow saw the task finish'
        observer.onProcessComplete(handler('COUNT (sample_1)'), trace(workDir))
        sleep(200)
        Files.copy(scenario('tool_featurecounts').resolve('.command.log'), workDir.resolve('.command.log'))
        observer.onFlowComplete()

        then:
        report.readLines()[1] ==
            "${workDir},COUNT (sample_1),COUNT,normal,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0".toString()
    }

    def "completed task whose job log never appears is reported without usage values"() {
        when:
        observer.onProcessComplete(handler('ALIGN (sample_1, lane2)'), trace(tmp.resolve('no_such_workdir')))
        observer.onFlowComplete()

        then:
        noExceptionThrown()   // was a NoSuchFileException
        report.readLines() == [
            HEADER,
            "${tmp.resolve('no_such_workdir')},\"ALIGN (sample_1, lane2)\",ALIGN,normal,,,,,,,,,,,".toString(),
        ]
    }

    def "a task that never got a job ID is reported with its name only"() {
        given: 'a task that failed before it was submitted, so Nextflow has no native_id for it'
        def unsubmitted = Stub(TraceRecord) {
            get('native_id') >> null
            get('workdir') >> tmp.resolve('never_run').toString()
            get('queue') >> 'normal'
        }

        when:
        observer.onProcessComplete(handler('ALIGN (sample_3)'), unsubmitted)

        then:
        noExceptionThrown()   // was a NullPointerException
        report.readLines()[1] == "${tmp.resolve('never_run')},ALIGN (sample_3),ALIGN,normal,,,,,,,,,,,".toString()
    }

    @IgnoreIf({ System.getProperty('user.name') == 'root' })   // root can read anything
    def "a task whose job log cannot be read is reported without usage values"() {
        given: 'a job log with no read permission, e.g. a file system problem on /scratch'
        Path workDir = tmp.resolve('unreadable')
        Files.createDirectories(workDir)
        Path log = Files.copy(scenario('tool_featurecounts').resolve('.command.log'), workDir.resolve('.command.log'))
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString('---------'))

        when:
        observer.onProcessCached(handler('COUNT (sample_1)'), trace(workDir))
        observer.onProcessComplete(handler('COUNT (sample_2)'), trace(workDir))
        observer.onFlowComplete()

        then:
        noExceptionThrown()   // was an AccessDeniedException
        report.readLines() == [
            HEADER,
            "${workDir},COUNT (sample_1),COUNT,normal,,,,,,,,,,,".toString(),
            "${workDir},COUNT (sample_2),COUNT,normal,,,,,,,,,,,".toString(),
        ]
    }

    def "cached task whose work directory was cleaned up is reported without usage values"() {
        when:
        observer.onProcessCached(handler('ALIGN (sample_2)'), trace(tmp.resolve('cleaned_workdir')))

        then:
        noExceptionThrown()   // was a NoSuchFileException
        report.readLines() == [
            HEADER,
            "${tmp.resolve('cleaned_workdir')},ALIGN (sample_2),ALIGN,normal,,,,,,,,,,,".toString(),
        ]
    }

    def "a run writes both reports, then publishes both to publishTo"() {
        when:
        observer.onProcessComplete(handler('COUNT (sample_1)'), trace(scenario('tool_featurecounts')))
        observer.onFlowComplete()

        then:
        report.readLines().size() == 2
        tmp.resolve('UsageReport.json').text.contains('"COUNT (sample_1)"')
        tmp.resolve('published/UsageReport.csv').text == report.text
        tmp.resolve('published/UsageReport.json').text == tmp.resolve('UsageReport.json').text
    }

    def "old gadi.format and gadi.output settings are ignored"() {
        given:
        Path folder = tmp.resolve('old_settings')
        Files.createDirectories(folder)
        def oldConfig = [gadi: [format: 'json', output: folder.resolve('custom.json').toString()]]
        def observer = new NfGadiObserver(Stub(Session) { getConfig() >> oldConfig }, folder, new GadiUsageReader(1, 3))

        when:
        observer.onFlowBegin()
        observer.onFlowComplete()

        then:
        folder.toFile().list() as Set == ['UsageReport.csv', 'UsageReport.json'] as Set
    }
}
