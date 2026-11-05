package nci.plugin

import spock.lang.Specification
import spock.lang.Unroll

import static nci.plugin.GadiTestSupport.hms
import static nci.plugin.GadiTestSupport.size

/**
 * Edge cases for {@link GadiUsageParser#parseText} that the real
 * scenario logs in GadiScenarioTest do not cover.
 */
class GadiUsageParserTest extends Specification {

    def "empty log gives a usage with every field null"() {
        when:
        def usage = GadiUsageParser.parseText('')

        then:
        usage == new GadiUsageRecord()
    }

    def "GPU memory line is not mistaken for memory used"() {
        given:
        def text = '''\
            |   Memory Requested:   42.0GB                Memory Used: 10.89GB
            |   GPU Memory Used:    3.50GB
            |'''.stripMargin()

        expect:
        GadiUsageParser.parseText(text).memoryUsed == size('10.89GB')
    }

    @Unroll
    def "CPU time #cpu_time is read as #expected"() {
        given: 'a usage line as Gadi writes it; CPU time is summed over every CPU, so can exceed 99 hours'
        def text = "   NCPUs Requested:    48                  CPU Time Used: ${cpu_time}        "

        expect:
        GadiUsageParser.parseText(text).cpuTime == hms(expected)

        where:
        cpu_time    || expected
        '00:00:00'  || '00:00:00'   // EXPECTED: no CPU time used
        '00:02:22'  || '00:02:22'   // EXPECTED: minutes and seconds
        '13:55:28'  || '13:55:28'   // EXPECTED: STAR scenario, over 10 hours
        '99:59:59'  || '99:59:59'   // EXPECTED: largest value with two-digit hours
        '144:12:30' || '144:12:30'  // EXPECTED: e.g. 48 CPUs busy for 3 hours; was not read when the pattern allowed only two-digit hours
    }

    @Unroll
    def "sizes are read with or without decimals: #logged"() {
        given: 'the four size lines as Gadi writes them, all with the same size'
        def text = """\
            |   Memory Requested:   ${logged}                Memory Used: ${logged}
            |   JobFS Requested:    ${logged}                JobFS Used: ${logged}
            |""".stripMargin()

        when:
        def usage = GadiUsageParser.parseText(text)

        then:
        usage.memoryUsed == size(logged)
        usage.memoryRequested == size(logged)
        usage.jobfsUsed == size(logged)
        usage.jobfsRequested == size(logged)

        where:
        logged << [
            '10.89GB',   // EXPECTED: as in every real log so far
            '100.0MB',
            '512MB',     // EXPECTED: whole number, no decimal point
            '14GB',
            '0B',        // EXPECTED: no unit prefix, as JobFS Used is often logged
        ]
    }

    def "walltime over 99 hours is read"() {
        given:
        def text = '   Walltime Requested: 120:00:00            Walltime Used: 100:30:00        '

        when:
        def usage = GadiUsageParser.parseText(text)

        then:
        usage.walltimeRequested == hms('120:00:00')
        usage.walltimeUsed == hms('100:30:00')
    }

    def "when a value appears on more than one line, the last one wins"() {
        given:
        def text = '''\
            |   Exit Status:        1
            |   Exit Status:        0
            |'''.stripMargin()

        expect:
        GadiUsageParser.parseText(text).exitCode == 0
    }
}
