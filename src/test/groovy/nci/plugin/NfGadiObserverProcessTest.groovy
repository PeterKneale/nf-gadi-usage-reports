package nci.plugin

import java.nio.file.Files
import java.nio.file.Path
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Drives the observer end to end, writing real reports to a temporary folder.
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
        def config = [gadi: [publishTo: tmp.resolve('published').toString()]]
        def fastReader = new GadiUsageReader(1, 3)   // keep the wait loops fast
        observer = new NfGadiObserver(Stub(Session) { getConfig() >> config }, tmp, fastReader)
        observer.onFlowBegin()            // writes the CSV header
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

    private static Path scenario(String name) {
        Path.of(NfGadiObserverProcessTest.getResource("scenarios/${name}").toURI())
    }

    def "completed task with a job log is reported with its usage values"() {
        when:
        observer.onProcessComplete(handler('COUNT (sample_1)'), trace(scenario('tool_featurecounts')))

        then:
        report.readLines() == [
            HEADER,
            "${scenario('tool_featurecounts')},COUNT (sample_1),COUNT,normal,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0".toString(),
        ]
    }

    def "completed task whose job log never appears is reported without usage values"() {
        when:
        observer.onProcessComplete(handler('ALIGN (sample_1, lane2)'), trace(tmp.resolve('no_such_workdir')))

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
