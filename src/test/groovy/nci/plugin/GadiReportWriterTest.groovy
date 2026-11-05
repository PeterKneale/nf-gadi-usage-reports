package nci.plugin

import groovy.json.JsonSlurper
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

import static nci.plugin.GadiTestSupport.hms
import static nci.plugin.GadiTestSupport.size

class GadiReportWriterTest extends Specification {

    static final GadiUsage FEATURECOUNTS = new GadiUsage(
        exitCode: 0, serviceUnits: 0.40, cpus: 6, cpuTime: hms('00:02:22'),
        walltimeUsed: hms('00:01:09'), walltimeRequested: hms('05:00:00'),
        memoryUsed: size('10.89GB'), memoryRequested: size('42.0GB'),
        jobfsUsed: size('0B'), jobfsRequested: size('100.0MB'))

    @TempDir
    Path tmp

    def "each writer writes its report under the default name, in the folder it is given"() {
        expect:
        new GadiReportWriterCsv(tmp).path == tmp.resolve('UsageReport.csv')
        new GadiReportWriterJson(tmp).path == tmp.resolve('UsageReport.json')
    }

    def "CSV report: rows written so far are kept if the run never finishes"() {
        given:
        def writer = new GadiReportWriterCsv(tmp)

        when:
        writer.start()
        writer.recordTask('COUNT (sample_1)', FEATURECOUNTS, 'normal')
        // no finish(): e.g. the Nextflow head job was killed

        then:
        writer.path.readLines().size() == 2   // header and the one row
    }

    // Smoke test only: it also passes without the lock in GadiReportWriterCsv.append,
    // because the OS keeps small appends like these whole on Linux. The lock is there
    // for rows or filesystems where that does not hold.
    def "CSV report: rows from tasks finishing at the same time are all written intact"() {
        given:
        def writer = new GadiReportWriterCsv(tmp)
        writer.start()

        when:
        (1..50).collect { int i ->
            Thread.start { writer.recordTask("COUNT (sample_${i})", FEATURECOUNTS, 'normal') }
        }*.join()

        then:
        def rows = writer.path.readLines().drop(1)
        rows.size() == 50
        rows.every { it ==~ /COUNT \(sample_\d+\),COUNT,normal,0\.40,6,00:02:22,00:01:09,05:00:00,10\.89GB,42\.0GB,0B,100\.0MB,0\.34,0/ }
    }

    def "CSV report: header on start, then one row per task"() {
        given:
        def writer = new GadiReportWriterCsv(tmp)

        when:
        writer.start()
        writer.recordTask('COUNT (sample_1)', FEATURECOUNTS, 'normal')
        writer.recordTask('LOCAL_STEP (sample_1)')
        writer.finish()

        then:
        writer.path.readLines() == [
            GadiReportWriterCsv.CSV_HEADER.join(','),
            'COUNT (sample_1),COUNT,normal,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0',
            'LOCAL_STEP (sample_1),LOCAL_STEP,,,,,,,,,,,,',   // not a Gadi job: no usage values
        ]
    }

    def "CSV report: CPU time over 99 hours is written as logged"() {
        given:
        def writer = new GadiReportWriterCsv(tmp)
        def usage = new GadiUsage(cpus: 48, cpuTime: hms('144:12:30'), walltimeUsed: hms('03:00:16'))

        when:
        writer.start()
        writer.recordTask('ALIGN (sample_1)', usage, 'normal')

        then: '144:12:30 CPU over 3:00:16 wall on 48 CPUs = 519150 / (10816 x 48) = 1.00'
        writer.path.readLines()[1] == 'ALIGN (sample_1),ALIGN,normal,,48,144:12:30,03:00:16,,,,,,1.00,'
    }

    def "JSON report: written on finish, grouped by process, keeping the field order"() {
        given:
        def writer = new GadiReportWriterJson(tmp)

        when:
        writer.start()
        writer.recordTask('COUNT (sample_1)', FEATURECOUNTS, 'normal')
        writer.recordTask('COUNT (sample_2)', new GadiUsage(), 'normal')
        writer.finish()
        def json = new JsonSlurper().parse(writer.path.toFile()) as Map

        then:
        json.keySet() == ['COUNT'] as Set
        json.COUNT*.name == ['COUNT (sample_1)', 'COUNT (sample_2)']
        (json.COUNT[0] as Map).keySet() as List == [
            'sus', 'walltime', 'cputime', 'cpus', 'memory', 'efficiency', 'exitCode',
            'requestedMemory', 'requestedWalltime', 'requestedJobFS', 'usedJobFS', 'queue', 'name']
        json.COUNT[0].efficiency == '0.34'
        json.COUNT[1].efficiency == null   // log could not be read
    }
}
