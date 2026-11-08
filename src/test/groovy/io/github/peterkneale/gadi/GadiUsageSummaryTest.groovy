package io.github.peterkneale.gadi

import spock.lang.Specification

import static io.github.peterkneale.gadi.GadiTestSupport.hms
import static io.github.peterkneale.gadi.GadiTestSupport.size

class GadiUsageSummaryTest extends Specification {

    /** 2 CPUs for 1 hour, 1 CPU hour used: efficiency 0.50, 1.00 SU wasted of 2.00 */
    static final GadiUsageRecord HALF_IDLE = new GadiUsageRecord(
        exitCode: 0, serviceUnits: 2.00, cpus: 2, cpuTime: hms('01:00:00'),
        walltimeUsed: hms('01:00:00'), walltimeRequested: hms('02:00:00'),
        memoryUsed: size('1.0GB'), memoryRequested: size('4.0GB'))

    /** 4 CPUs for 30 minutes, all busy: efficiency 1.00, nothing wasted */
    static final GadiUsageRecord BUSY = new GadiUsageRecord(
        exitCode: 0, serviceUnits: 4.00, cpus: 4, cpuTime: hms('02:00:00'),
        walltimeUsed: hms('00:30:00'), walltimeRequested: hms('01:00:00'))

    static final GadiUsageRecord FAILED = new GadiUsageRecord(
        exitCode: 1, serviceUnits: 0.10, cpus: 1, cpuTime: hms('00:00:01'),
        walltimeUsed: hms('00:01:00'), walltimeRequested: hms('00:05:00'))

    static Map<String, Object> row(String name, GadiUsageRecord usage = new GadiUsageRecord(), String workDir = null) {
        GadiReportWriter.row(GadiTask.of(name, 'normal', workDir), usage)
    }

    def "sums service units, wasted service units, walltime and efficiency over the run"() {
        when:
        def summary = GadiUsageSummary.of([row('ALIGN (s1)', HALF_IDLE), row('ALIGN (s2)', HALF_IDLE), row('COUNT (s1)', BUSY)])

        then:
        summary.run.tasks == 3
        summary.run.serviceUnits == 8.00
        summary.run.wastedServiceUnits == 2.00
        summary.run.walltimeSeconds == 2 * 3600 + 1800
        summary.run.efficiency == (3600 + 3600 + 7200) / (double) (2 * 3600 + 2 * 3600 + 4 * 1800)   // 0.67
        summary.run.wastedShare == 0.25
    }

    def "lists processes with the most service units first, then by name"() {
        when:
        def summary = GadiUsageSummary.of([row('B (s1)', HALF_IDLE), row('A (s1)', HALF_IDLE), row('COUNT (s1)', BUSY)])

        then:
        summary.processes*.name == ['COUNT', 'A', 'B']
        summary.processes*.serviceUnits == [4.00, 2.00, 2.00]
    }

    def "counts failed tasks and lists them in the order recorded"() {
        when:
        def summary = GadiUsageSummary.of([row('A (s1)', FAILED, 'work/1'), row('A (s2)', BUSY), row('B (s1)', FAILED, 'work/2')])

        then:
        summary.run.failed == 2
        summary.processes.find { it.name == 'A' }.failed == 1
        summary.failedTasks*.work_dir == ['work/1', 'work/2']
        summary.failedTasks*.exit_code == [1, 1]
    }

    def "a task without usage values counts as a task and nothing else"() {
        when:
        def summary = GadiUsageSummary.of([row('LOCAL (s1)'), row('A (s1)', BUSY)])
        def local = summary.processes.find { it.name == 'LOCAL' }

        then:
        summary.run.tasks == 2
        summary.run.withoutUsage == 1
        summary.run.serviceUnits == 4.00
        local.serviceUnits == 0.00
        local.efficiency == null
        local.wastedShare == null
    }

    def "a task whose efficiency is unknown wastes nothing, so the waste is never overstated"() {
        given: 'service units but no CPU time, e.g. a log cut short'
        def usage = new GadiUsageRecord(exitCode: 0, serviceUnits: 3.00, cpus: 2, walltimeUsed: hms('00:10:00'))

        when:
        def summary = GadiUsageSummary.of([row('A (s1)', usage)])

        then:
        summary.run.serviceUnits == 3.00
        summary.run.wastedServiceUnits == 0.00
        summary.run.efficiency == null
        summary.run.walltimeSeconds == 600
    }

    def "a task that used more CPU time than it requested wastes nothing"() {
        given: 'efficiency above 1, as happens with hyperthreading'
        def usage = new GadiUsageRecord(exitCode: 0, serviceUnits: 1.00, cpus: 1, cpuTime: hms('00:12:00'), walltimeUsed: hms('00:10:00'))

        expect:
        GadiUsageSummary.of([row('A (s1)', usage)]).run.wastedServiceUnits == 0.00
    }

    def "seconds: #text"() {
        expect:
        GadiUsageSummary.Totals.seconds(text) == seconds

        where:
        text        || seconds
        '00:00:00'  || 0
        '13:55:28'  || 13 * 3600 + 55 * 60 + 28
        '144:12:30' || 144 * 3600 + 12 * 60 + 30
        null        || null
        'soon'      || null
    }
}
