package nci.plugin

import java.nio.file.Paths
import nextflow.Session
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Covers each branch of {@link NfGadiObserver#configCheck()}
 */
class NfGadiObserverConfigTest extends Specification {

    private NfGadiObserver observerWith(String configured_format, String configured_output) {
        def session = Stub(Session) {
            getConfig() >> [gadi: [format: configured_format, output: configured_output]]
        }
        new NfGadiObserver(session)
    }

    @Unroll
    def "configCheck: format='#configured_format' output='#configured_output' -> #expected_format, #expected_output"() {
        given:
        def observer = observerWith(configured_format, configured_output)

        when:
        observer.configCheck()

        then:
        observer.format == expected_format
        observer.output == expected_output
        observer.path == Paths.get(expected_output)

        where:
        configured_format | configured_output || expected_format | expected_output
        ''                | ''                || 'csv'           | 'UsageReport.csv'   // EXPECTED: nothing configured, sensible defaults
        'csv'             | 'out.csv'         || 'csv'           | 'out.csv'           // EXPECTED: settings honoured as given
        'json'            | 'out.json'        || 'json'          | 'out.json'          // EXPECTED: settings honoured as given
        'csv'             | 'out.json'        || 'json'          | 'out.json'          // SURPRISING: explicit format silently overridden by extension, only an info log
        'json'            | 'out.csv'         || 'csv'           | 'out.csv'           // SURPRISING: explicit format silently overridden by extension, only an info log
        'csv'             | 'out.txt'         || 'csv'           | 'UsageReport.csv'   // SURPRISING: valid settings discarded, report written to a file the user never named
        'json'            | ''                || 'json'          | 'UsageReport.json'  // EXPECTED: format honoured, default name gets matching extension
        'xml'             | ''                || 'csv'           | 'UsageReport.csv'   // SURPRISING: invalid format silently replaced, log does not mention xml was rejected
        ''                | 'out.json'        || 'json'          | 'out.json'          // EXPECTED: format inferred from output extension
        ''                | 'out.csv'         || 'csv'           | 'out.csv'           // EXPECTED: format inferred from output extension
        ''                | 'out.txt'         || 'csv'           | 'UsageReport.csv'   // SURPRISING: user's file name discarded, a CSV in out.txt might be expected
    }
}
