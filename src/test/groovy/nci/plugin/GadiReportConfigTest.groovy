package nci.plugin

import java.nio.file.Paths
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Covers each branch of {@link GadiReportConfig} resolution
 */
class GadiReportConfigTest extends Specification {

    @Unroll
    def "resolve: format='#configured_format' output='#configured_output' -> #expected_format, #expected_output"() {
        when:
        def config = new GadiReportConfig([format: configured_format, output: configured_output])

        then:
        config.format == expected_format
        config.output == expected_output
        config.path == Paths.get(expected_output)

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

    def "missing gadi scope falls back to defaults"() {
        when:
        def config = new GadiReportConfig(null)

        then:
        config.format == 'csv'
        config.output == 'UsageReport.csv'
        config.resolvePublishTo() == null
    }

    @Unroll
    def "resolvePublishTo: #description"() {
        expect:
        new GadiReportConfig([publishTo: publish_to]).resolvePublishTo() == expected

        where:
        description           | publish_to              || expected
        'not set'             | null                    || null
        'blank string'        | '   '                   || null
        'plain string'        | ' /data/report.csv '    || '/data/report.csv'
        'closure'             | { -> '/data/x.csv' }    || '/data/x.csv'
        'closure gives null'  | { -> null }             || null
    }
}
