package nci.plugin

import spock.lang.Specification

import spock.lang.Unroll

import static nci.plugin.GadiTestSupport.hms

/**
 * Efficiency = CPU time / (walltime x CPUs). Expected values are written
 * in seconds so they can be checked by eye against the inputs.
 */
class GadiEfficiencyCalculatorTest extends Specification {

    private static GadiUsageRecord usage(String cpu_time, String walltime_used, String cpus) {
        new GadiUsageRecord(cpuTime: hms(cpu_time), walltimeUsed: hms(walltime_used), cpus: cpus as Integer)
    }

    @Unroll
    def "cpu #cpu_time, wall #walltime_used, #cpus CPUs -> #expected"() {
        when:
        Double result = GadiEfficiencyCalculator.calculate(usage(cpu_time, walltime_used, cpus))

        then:
        Math.abs(result - expected) < 1e-9

        where:
        cpu_time   | walltime_used | cpus || expected
        '00:02:22' | '00:01:09'    | '6'  || 142 / 69 / 6          // EXPECTED: featurecounts scenario, 0.34
        '00:00:19' | '00:02:15'    | '1'  || 19 / 135 / 1          // EXPECTED: exit_code_255 scenario, 0.14
        '13:55:28' | '00:37:40'    | '24' || 50128 / 2260 / 24     // EXPECTED: star scenario, hours > 10
        '01:00:00' | '01:00:00'    | '1'  || 1                     // EXPECTED: one CPU busy the whole time
        '04:00:00' | '01:00:00'    | '4'  || 1                     // EXPECTED: four CPUs busy the whole time
        '00:00:00' | '00:00:36'    | '2'  || 0                     // EXPECTED: bedtools scenario, no CPU time
        '02:00:00' | '01:00:00'    | '1'  || 2                     // EXPECTED: more CPU time than available gives > 1
    }

    @Unroll
    def "missing #field_name gives null"() {
        expect:
        GadiEfficiencyCalculator.calculate(usage(cpu_time, walltime_used, cpus)) == null

        where:
        field_name   | cpu_time   | walltime_used | cpus
        'CPU time'   | null       | '00:01:00'    | '1'     // EXPECTED: nothing to calculate from
        'walltime'   | '00:01:00' | null          | '1'     // EXPECTED: was a NullPointerException
        'CPUs'       | '00:01:00' | '00:01:00'    | null    // EXPECTED: was a NullPointerException
        'all fields' | null       | null          | null    // EXPECTED: e.g. a log with no usage block yet
    }

    @Unroll
    def "zero #field_name gives null"() {
        expect:
        GadiEfficiencyCalculator.calculate(usage(cpu_time, walltime_used, cpus)) == null

        where:
        field_name   | cpu_time   | walltime_used | cpus
        'walltime'   | '00:00:01' | '00:00:00'    | '1'     // EXPECTED: was Infinity in the report
        'all fields' | '00:00:00' | '00:00:00'    | '0'     // EXPECTED: was NaN in the report
        'CPUs'       | '00:01:00' | '00:01:00'    | '0'     // EXPECTED: was Infinity in the report
    }
}
