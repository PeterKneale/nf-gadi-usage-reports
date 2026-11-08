package io.github.peterkneale.gadi

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

import static io.github.peterkneale.gadi.GadiUsageSummary.Totals

/**
 * Writes an HTML report in one go when the run finishes: the service
 * units and CPU efficiency of the run, charted and tabled by process.
 * The page is built from {@link #TEMPLATE}, which loads Tabler and
 * ApexCharts from a CDN, so the charts need an internet connection.
 */
@CompileStatic
class GadiReportWriterHtml extends GadiReportWriter {

    static final String FILE_NAME = REPORT_NAME + '.html'

    /** The page, with {@code @@NAME@@} placeholders for the values. */
    static final String TEMPLATE = '/io/github/peterkneale/gadi/UsageReport.html'

    /** Processes shown by name in the service units pie; the rest are one "Other" slice. */
    static final int PIE_SLICES = 6

    /** Processes shown in the wasted service units bar chart. */
    static final int BAR_ROWS = 10

    /** Failed tasks listed by name. */
    static final int MAX_FAILED_LISTED = 50

    private final List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<Map<String, Object>>())

    /** @param folder where to write {@link #FILE_NAME} */
    GadiReportWriterHtml(Path folder) {
        super(folder.resolve(FILE_NAME))
    }

    @Override
    protected void record(Map<String, Object> row) {
        rows.add(row)
    }

    @Override
    void finish() {
        List<Map<String, Object>> snapshot
        synchronized (rows) {
            snapshot = new ArrayList<Map<String, Object>>(rows)
        }
        Files.write(path, render(snapshot).getBytes('UTF-8'))
        super.finish()
    }

    /** The page for the given rows. */
    static String render(List<Map<String, Object>> rows) {
        GadiUsageSummary summary = GadiUsageSummary.of(rows)
        Totals run = summary.run
        List<Totals> processes = summary.processes

        Map<String, String> values = [
            KPI_SERVICE_UNITS: Totals.su(run.serviceUnits),
            KPI_WASTED:        Totals.su(run.wastedServiceUnits),
            KPI_WASTED_NOTE:   run.wastedShare == null ? 'No Gadi jobs' : percent(run.wastedShare) + ' of the service units',
            KPI_EFFICIENCY:    run.efficiency == null ? '-' : percent(run.efficiency),
            KPI_TASKS:         run.tasks.toString(),
            KPI_TASKS_NOTE:    tasksNote(run),
            PROCESS_ROWS:      processes.collect { Totals p -> processRow(p) }.join('\n'),
            FAILED_SECTION:    failedSection(summary.failedTasks),
            CHART_DATA:        chartData(processes),
        ]

        String html = template()
        values.each { String name, String value ->
            html = html.replace('@@' + name + '@@', value)
        }
        return html
    }

    private static String template() {
        InputStream stream = GadiReportWriterHtml.getResourceAsStream(TEMPLATE)
        if (stream == null) {
            throw new IllegalStateException("Report template ${TEMPLATE} is not on the classpath")
        }
        return stream.withCloseable { InputStream it -> it.getText('UTF-8') }
    }

    private static String tasksNote(Totals run) {
        List<String> notes = []
        if (run.failed > 0) {
            notes << "${run.failed} failed".toString()
        }
        if (run.withoutUsage > 0) {
            notes << "${run.withoutUsage} without usage values".toString()
        }
        return notes ? notes.join(', ') : 'All completed with usage values'
    }

    /** One table row; the sort-* classes are the columns the page sorts by. */
    private static String processRow(Totals p) {
        return '<tr>' +
            "<td class=\"sort-process text-break\">${escape(p.name)}</td>" +
            "<td class=\"sort-tasks text-end\">${p.tasks}</td>" +
            "<td class=\"sort-failed text-end\">${p.failed > 0 ? "<span class=\"text-danger\">${p.failed}</span>" : '0'}</td>" +
            "<td class=\"sort-service-units text-end\">${Totals.su(p.serviceUnits)}</td>" +
            "<td class=\"sort-wasted text-end\">${Totals.su(p.wastedServiceUnits)}</td>" +
            "<td class=\"sort-efficiency text-end\">${p.efficiency == null ? '-' : percent(p.efficiency)}</td>" +
            "<td class=\"sort-walltime text-end\">${format(Duration.ofSeconds(p.walltimeSeconds))}</td>" +
            '</tr>'
    }

    private static String failedSection(List<Map<String, Object>> failed) {
        if (failed.isEmpty()) {
            return ''
        }
        StringBuilder html = new StringBuilder()
        html << '<div class="card">\n'
        html << "  <div class=\"card-header\"><h3 class=\"card-title\">Failed tasks <span class=\"card-subtitle\">${failed.size()} of the tasks</span></h3></div>\n"
        html << '  <div class="table-responsive">\n'
        html << '    <table class="table table-vcenter card-table">\n'
        html << '      <thead><tr><th>Task</th><th>Work directory</th><th class="text-end">Exit code</th></tr></thead>\n'
        html << '      <tbody>\n'
        failed.take(MAX_FAILED_LISTED).each { Map<String, Object> row ->
            html << "        <tr><td class=\"text-break\">${escape(row.name as String)}</td>" +
                "<td class=\"text-secondary font-monospace text-break\">${escape(row.work_dir as String)}</td>" +
                "<td class=\"text-end\">${row.exit_code}</td></tr>\n"
        }
        if (failed.size() > MAX_FAILED_LISTED) {
            html << "        <tr><td colspan=\"3\" class=\"text-secondary\">and ${failed.size() - MAX_FAILED_LISTED} more, see UsageReport.csv</td></tr>\n"
        }
        html << '      </tbody>\n    </table>\n  </div>\n</div>'
        return html.toString()
    }

    /**
     * The chart series as JSON: the service units of the top processes
     * with the rest folded into "Other", and the wasted service units of
     * the processes that waste the most. Processes with no service units
     * are left out of both.
     */
    static String chartData(List<Totals> processes) {
        List<Totals> withUnits = processes.findAll { Totals p -> p.serviceUnits > 0.0 }

        List<Totals> top = withUnits.take(PIE_SLICES)
        List<Totals> rest = withUnits.drop(PIE_SLICES)
        List<String> pieLabels = top*.name
        List<BigDecimal> pieValues = top*.serviceUnits
        if (rest) {
            pieLabels << 'Other'
            pieValues << (rest*.serviceUnits.sum() as BigDecimal)
        }

        List<Totals> wasted = withUnits
            .findAll { Totals p -> p.wastedServiceUnits > 0.0 }
            .sort(false) { Totals a, Totals b -> b.wastedServiceUnits <=> a.wastedServiceUnits ?: a.name <=> b.name }
            .take(BAR_ROWS)

        Map<String, Object> data = [
            serviceUnits: [labels: pieLabels, values: pieValues.collect { BigDecimal v -> Totals.su(v) as BigDecimal }, other: rest.size()],
            wasted:       [labels: wasted*.name, values: wasted.collect { Totals p -> Totals.su(p.wastedServiceUnits) as BigDecimal }],
        ] as Map<String, Object>
        // "<" as a JSON escape, so no name can close the page's script block
        return JsonOutput.toJson(data).replace('<', '\\u003c')
    }

    /** A ratio as a whole percentage, e.g. 0.345 -> "35%". */
    static String percent(double ratio) {
        return Math.round(ratio * 100) + '%'
    }

    /** Text made safe to put in HTML; null becomes empty. */
    static String escape(String text) {
        if (text == null) {
            return ''
        }
        return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }
}
