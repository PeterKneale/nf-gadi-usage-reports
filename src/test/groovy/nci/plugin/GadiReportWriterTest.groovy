package nci.plugin

import groovy.json.JsonSlurper
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

import static nci.plugin.GadiTestSupport.hms
import static nci.plugin.GadiTestSupport.size

class GadiReportWriterTest extends Specification {

    static final GadiUsageRecord FEATURECOUNTS = new GadiUsageRecord(
        exitCode: 0, serviceUnits: 0.40, cpus: 6, cpuTime: hms('00:02:22'),
        walltimeUsed: hms('00:01:09'), walltimeRequested: hms('05:00:00'),
        memoryUsed: size('10.89GB'), memoryRequested: size('42.0GB'),
        jobfsUsed: size('0B'), jobfsRequested: size('100.0MB'))

    @TempDir
    Path tmp

    def "JSON report: processes are listed by name, whatever order their tasks finished in"() {
        given:
        def writer = new GadiReportWriterJson(tmp)
        def finishOrder = ['TRIM', 'ALIGN', 'QC', 'COUNT', 'MULTIQC', 'BAM_SORT', 'FASTQC', 'ZIP', 'INDEX', 'DEDUP']

        when:
        writer.start()
        finishOrder.each { String process -> writer.recordTask(GadiTask.of("${process} (sample_1)", 'normal'), FEATURECOUNTS) }
        writer.recordTask(GadiTask.of('ALIGN (sample_2)', 'normal'), FEATURECOUNTS)
        writer.finish()
        def json = new JsonSlurper().parse(writer.path.toFile()) as Map

        then:
        json.keySet() as List == finishOrder.sort(false)
        json.ALIGN*.name == ['ALIGN (sample_1)', 'ALIGN (sample_2)']   // tasks keep the order they were recorded in
    }

    def "every row has exactly the report fields, in order, and they are the CSV header"() {
        expect:
        GadiReportWriter.row(GadiTask.of('COUNT (sample_1)', 'normal', 'work/ab'), FEATURECOUNTS).keySet() as List == GadiReportWriter.FIELDS
        GadiReportWriter.row(GadiTask.of('LOCAL_STEP (sample_1)'), new GadiUsageRecord()).keySet() as List == GadiReportWriter.FIELDS
        GadiReportWriterCsv.CSV_HEADER == GadiReportWriter.FIELDS
    }

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
        writer.recordTask(GadiTask.of('COUNT (sample_1)', 'normal', 'work/ab/cdef12'), FEATURECOUNTS)
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
            Thread.start { writer.recordTask(GadiTask.of("COUNT (sample_${i})", 'normal', "work/${i}"), FEATURECOUNTS) }
        }*.join()

        then:
        def rows = writer.path.readLines().drop(1)
        rows.size() == 50
        rows.every { it ==~ /work\/\d+,COUNT \(sample_\d+\),COUNT,normal,0\.40,6,00:02:22,00:01:09,05:00:00,10\.89GB,42\.0GB,0B,100\.0MB,0\.34,0/ }
    }

    def "CSV report: header on start, then one row per task"() {
        given:
        def writer = new GadiReportWriterCsv(tmp)

        when:
        writer.start()
        writer.recordTask(GadiTask.of('COUNT (sample_1)', 'normal', 'work/ab/cdef12'), FEATURECOUNTS)
        writer.recordTask(GadiTask.of('LOCAL_STEP (sample_1)', null, 'work/cd/ef3456'))
        writer.finish()

        then:
        writer.path.readLines() == [
            GadiReportWriterCsv.CSV_HEADER.join(','),
            'work/ab/cdef12,COUNT (sample_1),COUNT,normal,0.40,6,00:02:22,00:01:09,05:00:00,10.89GB,42.0GB,0B,100.0MB,0.34,0',
            'work/cd/ef3456,LOCAL_STEP (sample_1),LOCAL_STEP,,,,,,,,,,,,',   // not a Gadi job: no usage values
        ]
    }

    def "CSV report: CPU time over 99 hours is written as logged"() {
        given:
        def writer = new GadiReportWriterCsv(tmp)
        def usage = new GadiUsageRecord(cpus: 48, cpuTime: hms('144:12:30'), walltimeUsed: hms('03:00:16'))

        when:
        writer.start()
        writer.recordTask(GadiTask.of('ALIGN (sample_1)', 'normal'), usage)

        then: '144:12:30 CPU over 3:00:16 wall on 48 CPUs = 519150 / (10816 x 48) = 1.00'
        writer.path.readLines()[1] == ',ALIGN (sample_1),ALIGN,normal,,48,144:12:30,03:00:16,,,,,,1.00,'   // no work dir given
    }

    def "JSON report: written on finish, grouped by process, keeping the field order"() {
        given:
        def writer = new GadiReportWriterJson(tmp)

        when:
        writer.start()
        writer.recordTask(GadiTask.of('COUNT (sample_1)', 'normal', 'work/ab/cdef12'), FEATURECOUNTS)
        writer.recordTask(GadiTask.of('COUNT (sample_2)', 'normal', 'work/cd/ef3456'), new GadiUsageRecord())
        writer.finish()
        def json = new JsonSlurper().parse(writer.path.toFile()) as Map

        then:
        json.keySet() == ['COUNT'] as Set
        json.COUNT*.name == ['COUNT (sample_1)', 'COUNT (sample_2)']
        (json.COUNT[0] as Map).keySet() as List == GadiReportWriter.FIELDS   // the same fields as the CSV columns
        json.COUNT[0].efficiency == 0.34
        json.COUNT[1].efficiency == null   // log could not be read
        json.COUNT*.work_dir == ['work/ab/cdef12', 'work/cd/ef3456']
    }

    def "JSON report: counts and amounts are numbers, durations and sizes are text"() {
        given:
        def writer = new GadiReportWriterJson(tmp)

        when:
        writer.start()
        writer.recordTask(GadiTask.of('COUNT (sample_1)', 'normal', 'work/ab/cdef12'), FEATURECOUNTS)
        writer.finish()
        def task = (new JsonSlurper().parse(writer.path.toFile()) as Map).COUNT[0] as Map

        then: 'as written, so a number keeps its decimals and text keeps its quotes'
        writer.path.text.contains('"service_units": 0.40,')
        writer.path.text.contains('"cpus": 6,')
        writer.path.text.contains('"efficiency": 0.34,')
        writer.path.text.contains('"exit_code": 0')
        writer.path.text.contains('"cpu_time": "00:02:22",')
        writer.path.text.contains('"used_memory": "10.89GB",')

        and: 'as read back'
        task.service_units instanceof Number
        task.cpus instanceof Integer
        task.efficiency instanceof Number
        task.exit_code instanceof Integer
        task.cpu_time instanceof String
        task.used_memory instanceof String
    }

    def "a row's numbers come out the same in the CSV: #field"() {
        expect:
        GadiReportWriter.text(GadiReportWriter.row(GadiTask.of('COUNT (sample_1)'), FEATURECOUNTS)[field]) == csv

        where:
        field           || csv
        'service_units' || '0.40'
        'cpus'          || '6'
        'efficiency'    || '0.34'
        'exit_code'     || '0'
        'cpu_time'      || '00:02:22'
        'queue'         || null   // missing: written as an empty cell
    }
}
