package io.github.peterkneale.gadi

import groovy.json.JsonSlurper
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

import static io.github.peterkneale.gadi.GadiTestSupport.hms

class GadiUsageReportTest extends Specification {

    @TempDir
    Path tmp

    def "writes the CSV and JSON reports side by side in one folder"() {
        expect:
        new GadiUsageReport(tmp).paths == [tmp.resolve('UsageReport.csv'), tmp.resolve('UsageReport.json')]
    }

    def "every task is recorded in both reports"() {
        given:
        def report = new GadiUsageReport(tmp)
        def usage = new GadiUsageRecord(cpus: 1, cpuTime: hms('00:00:30'), walltimeUsed: hms('00:01:00'))

        when:
        report.start()
        report.recordTask(GadiTask.of('ALIGN (sample_1)', 'normal', 'work/ab/cdef12'), usage)
        report.recordTask(GadiTask.of('LOCAL_STEP (sample_1)'))
        report.finish()
        def csv = tmp.resolve('UsageReport.csv').readLines()
        def json = new JsonSlurper().parse(tmp.resolve('UsageReport.json').toFile()) as Map

        then:
        csv.size() == 3   // header and both tasks
        csv[1].startsWith('work/ab/cdef12,ALIGN (sample_1),ALIGN,normal,')
        csv[2].startsWith(',LOCAL_STEP (sample_1),LOCAL_STEP,')   // no work dir given
        json.ALIGN*.name == ['ALIGN (sample_1)']
        json.ALIGN[0].efficiency == 0.50
        json.LOCAL_STEP*.name == ['LOCAL_STEP (sample_1)']
    }
}
