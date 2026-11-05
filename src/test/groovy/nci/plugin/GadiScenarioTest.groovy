package nci.plugin

import spock.lang.Shared
import spock.lang.Specification

import spock.lang.Unroll

import static nci.plugin.GadiTestSupport.hms
import static nci.plugin.GadiTestSupport.size

/**
 * Parses the anonymised Gadi job log in each scenario folder under
 * src/test/resources/nci/plugin/scenarios/<scenario>/.command.log
 * and checks every extracted value against the table below.
 *
 * Scenario folders are named tool_<tool>.
 *
 * To add a scenario, create the folder with its .command.log, add its
 * name to SCENARIOS, then add a row to the where: table with values
 * checked by hand against the log's Resource Usage block.
 */
class GadiScenarioTest extends Specification {

    static final Set<String> SCENARIOS = [
        'exit_code_255',
        'exit_code_negative',
        'tool_bcftools',
        'tool_bedtools',
        'tool_fastqc',
        'tool_featurecounts',
        'tool_multiqc',
        'tool_picard',
        'tool_reformat',
        'tool_regtools',
        'tool_samtools',
        'tool_star',
        'tool_trimmomatic',
    ] as Set

    @Shared
    File scenariosDir = new File(GadiScenarioTest.getResource('scenarios').toURI())

    private GadiUsageRecord parse(String scenario) {
        def log = new File(scenariosDir, "${scenario}/.command.log")
        assert log.exists() : "missing ${log}"
        new GadiUsageParser(log.toPath()).parse()
    }

    @Unroll
    def "scenario #scenario"() {
        when:
        GadiUsageRecord usage = parse(scenario)
        Double calculated = GadiEfficiencyCalculator.calculate(usage)

        then: 'the table holds values as written in the log; each is checked as its parsed type'
        usage.exitCode          == exit_code as Integer
        usage.serviceUnits      == sus as BigDecimal
        usage.cpus              == cpus as Integer
        usage.cpuTime           == hms(cpu_time)
        usage.walltimeUsed      == hms(walltime_used)
        usage.walltimeRequested == hms(walltime_requested)
        usage.memoryUsed        == size(memory_used)
        usage.memoryRequested   == size(memory_requested)
        usage.jobfsUsed         == size(jobfs_used)
        usage.jobfsRequested    == size(jobfs_requested)
        usage.isComplete()
        String.format('%.2f', calculated) == efficiency   // as written in the report

        where:
        scenario             || exit_code | sus      | cpus | cpu_time   | walltime_used | walltime_requested | memory_used | memory_requested | jobfs_used | jobfs_requested | efficiency
        'exit_code_255'      || '255'     | '0.11'   | '1'  | '00:00:19' | '00:02:15'    | '05:00:00'         | '14.75MB'   | '6.0GB'          | '0B'       | '100.0MB'       | '0.14'      // real failure: Singularity container creation failed; 19s / 135s / 1
        'exit_code_negative' || '-29'     | '0.11'   | '1'  | '00:00:19' | '00:02:15'    | '05:00:00'         | '14.75MB'   | '6.0GB'          | '0B'       | '100.0MB'       | '0.14'      // hand-edited copy of exit_code_255, only Exit Status changed; guards the -? in the regex (commit c4902fb)
        'tool_bcftools'      || '0'       | '3.00'   | '6'  | '00:08:49' | '00:08:35'    | '05:00:00'         | '3.77GB'    | '42.0GB'         | '0B'       | '100.0MB'       | '0.17'      // 529s CPU / 515s wall / 6 CPUs
        'tool_bedtools'      || '0'       | '0.07'   | '2'  | '00:00:00' | '00:00:36'    | '05:00:00'         | '69.85MB'   | '14.0GB'         | '0B'       | '100.0MB'       | '0.00'      // zero CPU time recorded
        'tool_fastqc'        || '0'       | '5.31'   | '2'  | '01:20:16' | '00:45:31'    | '05:00:00'         | '14.0GB'    | '14.0GB'         | '0B'       | '100.0MB'       | '0.88'      // 4816s / 2731s / 2; memory used = requested
        'tool_featurecounts' || '0'       | '0.40'   | '6'  | '00:02:22' | '00:01:09'    | '05:00:00'         | '10.89GB'   | '42.0GB'         | '0B'       | '100.0MB'       | '0.34'      // 142s / 69s / 6
        'tool_multiqc'       || '0'       | '0.16'   | '1'  | '00:01:07' | '00:03:12'    | '05:00:00'         | '2.27GB'    | '6.0GB'          | '9.79MB'   | '100.0MB'       | '0.35'      // 67s / 192s / 1; only non-zero JobFS used
        'tool_picard'        || '0'       | '115.15' | '24' | '02:46:15' | '02:23:56'    | '05:00:00'         | '68.83GB'   | '84.0GB'         | '0B'       | '100.0MB'       | '0.05'      // 9975s / 8636s / 24; largest SUs
        'tool_reformat'      || '0'       | '1.54'   | '2'  | '00:09:08' | '00:13:13'    | '05:00:00'         | '14.0GB'    | '14.0GB'         | '0B'       | '100.0MB'       | '0.35'      // 548s / 793s / 2
        'tool_regtools'      || '0'       | '10.31'  | '6'  | '00:22:06' | '00:29:28'    | '05:00:00'         | '26.94GB'   | '42.0GB'         | '0B'       | '100.0MB'       | '0.13'      // 1326s / 1768s / 6 = exactly 0.125, rounds up (was 0.12 from rounding error in the old minutes-based maths)
        'tool_samtools'      || '0'       | '17.24'  | '6'  | '02:26:57' | '00:49:16'    | '05:00:00'         | '42.0GB'    | '42.0GB'         | '0B'       | '100.0MB'       | '0.50'      // 8817s / 2956s / 6
        'tool_star'          || '0'       | '30.13'  | '24' | '13:55:28' | '00:37:40'    | '05:00:00'         | '84.0GB'    | '84.0GB'         | '0B'       | '100.0MB'       | '0.92'      // 50128s / 2260s / 24; CPU time over 10 hours
        'tool_trimmomatic'   || '0'       | '35.07'  | '12' | '06:56:02' | '00:50:06'    | '05:00:00'         | '44.37GB'   | '84.0GB'         | '0B'       | '100.0MB'       | '0.69'      // 24962s / 3006s / 12
    }

    def "every scenario folder is listed in SCENARIOS, and every listed scenario has a folder"() {
        given:
        def folders = scenariosDir.listFiles().findAll { it.isDirectory() }*.name as Set

        expect:
        folders == SCENARIOS
    }
}
