package nci.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the {@code gadi} config scope into the report settings
 * used by {@link NfGadiObserver}.
 */
@Slf4j
@CompileStatic
class GadiReportConfig {
    static final String REPORT_FORMAT_CSV = 'csv'
    static final String REPORT_FORMAT_JSON = 'json'
    static final String DEFAULT_REPORT_FORMAT = REPORT_FORMAT_CSV
    static final String DEFAULT_REPORT_NAME = 'UsageReport'
    static final String DEFAULT_OUTPUT = DEFAULT_REPORT_NAME + '.' + DEFAULT_REPORT_FORMAT

    final String format
    final String output
    final Path path
    final Object publishTo

    /**
     * @param gadi the {@code gadi} config scope, e.g. {@code [format: 'csv', output: 'report.csv']}
     */
    GadiReportConfig(Map gadi) {
        String format = (gadi?.get('format') ?: '') as String
        String output = (gadi?.get('output') ?: '') as String

        if (format.isEmpty() && output.isEmpty()) {
            format = DEFAULT_REPORT_FORMAT
            output = DEFAULT_OUTPUT
            log.info "Using default report name $output"
        } else if (!format.isEmpty() && !output.isEmpty()) {
            if (output.endsWith(format)) {
                log.info "Save the report to $output"
            } else if (output.endsWith(REPORT_FORMAT_JSON)) {
                format = REPORT_FORMAT_JSON
                log.info "Incorrect format setting, change it to json"
            } else if (output.endsWith(REPORT_FORMAT_CSV)) {
                format = REPORT_FORMAT_CSV
                log.info "Incorrect format setting, change it to csv"
            } else {
                format = DEFAULT_REPORT_FORMAT
                output = DEFAULT_OUTPUT
                log.info "Incorrect format and output settings, reset report name to $output"
            }
        } else if (!format.isEmpty()) {
            if (format == REPORT_FORMAT_JSON) {
                output = DEFAULT_REPORT_NAME + '.' + REPORT_FORMAT_JSON
                log.info "Using default json report name $output"
            } else {
                format = DEFAULT_REPORT_FORMAT
                output = DEFAULT_OUTPUT
                log.info "Using default csv report name $output"
            }
        } else {
            if (output.endsWith(REPORT_FORMAT_JSON)) {
                format = REPORT_FORMAT_JSON
                log.info "Report format is json"
            } else if (output.endsWith(REPORT_FORMAT_CSV)) {
                format = REPORT_FORMAT_CSV
                log.info "Report format is csv"
            } else {
                format = DEFAULT_REPORT_FORMAT
                output = DEFAULT_OUTPUT
                log.info "Incorrect output setting, reset report name to $output"
            }
        }

        this.format = format
        this.output = output
        this.path = Paths.get(output)
        this.publishTo = gadi?.get('publishTo') ?: ''
    }

    boolean isCsv() {
        return format == REPORT_FORMAT_CSV
    }

    boolean isJson() {
        return format == REPORT_FORMAT_JSON
    }

    /**
     * Resolves {@code publishTo}, which may be a closure, to a destination string.
     * Evaluated on demand so a closure sees state from the end of the run.
     *
     * @return the destination, or null when publishing is not configured
     */
    String resolvePublishTo() {
        if (publishTo == null) return null

        Object value = publishTo
        if (value instanceof Closure) {
            value = ((Closure) value).call()
        }

        if (value == null) return null

        String result = value.toString().trim()
        return result.isEmpty() ? null : result
    }
}
