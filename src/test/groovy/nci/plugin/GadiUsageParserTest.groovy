package nci.plugin

import spock.lang.Specification

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
        usage == new GadiUsage()
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
