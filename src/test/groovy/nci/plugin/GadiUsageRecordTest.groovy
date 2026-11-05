package nci.plugin

import spock.lang.Specification

import spock.lang.Unroll

import static nci.plugin.GadiTestSupport.hms
import static nci.plugin.GadiTestSupport.size

class GadiUsageRecordTest extends Specification {

    private static final Map<String, Object> COMPLETE = [
        serviceUnits: 0.40, walltimeUsed: hms('00:01:09'), cpus: 6, memoryUsed: size('10.89GB'),
    ]

    def "complete when service units, walltime, CPUs and memory are present"() {
        expect:
        new GadiUsageRecord(COMPLETE).isComplete()
    }

    @Unroll
    def "incomplete when #missing is missing"() {
        given:
        def fields = new HashMap<String, Object>(COMPLETE)
        fields.remove(missing)

        expect:
        !new GadiUsageRecord(fields).isComplete()

        where:
        missing << ['serviceUnits', 'walltimeUsed', 'cpus', 'memoryUsed']
    }

    def "other fields are not needed to be complete"() {
        expect:
        new GadiUsageRecord(COMPLETE).with { exitCode == null && cpuTime == null && isComplete() }
    }
}
