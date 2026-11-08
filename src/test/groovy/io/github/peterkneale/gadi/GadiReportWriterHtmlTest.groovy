package io.github.peterkneale.gadi

import groovy.json.JsonSlurper
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

import static io.github.peterkneale.gadi.GadiTestSupport.hms

class GadiReportWriterHtmlTest extends Specification {

    @TempDir
    Path tmp

    static GadiUsageRecord usage(BigDecimal serviceUnits, double efficiency, int exitCode = 0) {
        // 1 CPU for 100 seconds, busy for efficiency x 100 of them
        int busy = (int) Math.round(efficiency * 100)
        new GadiUsageRecord(exitCode: exitCode, serviceUnits: serviceUnits, cpus: 1,
            cpuTime: hms(String.format('00:%02d:%02d', busy.intdiv(60), busy % 60)),
            walltimeUsed: hms('00:01:40'), walltimeRequested: hms('01:00:00'))
    }

    static Map<String, Object> row(String name, GadiUsageRecord usage = new GadiUsageRecord(), String workDir = null) {
        GadiReportWriter.row(GadiTask.of(name, 'normal', workDir), usage)
    }

    /** The chart data the page embeds, as the page's script reads it. */
    static Map chartData(String html) {
        def m = html =~ /(?s)<script id="report-data" type="application\/json">(.*?)<\/script>/
        assert m.find()
        new JsonSlurper().parseText(m.group(1)) as Map
    }

    def "written on finish, with the totals, a row per process and the chart data"() {
        given:
        def writer = new GadiReportWriterHtml(tmp)

        when:
        writer.start()
        writer.recordTask(GadiTask.of('ALIGN (sample_1)', 'normal', 'work/ab'), usage(2.00, 0.50))
        writer.recordTask(GadiTask.of('COUNT (sample_1)', 'normal', 'work/cd'), usage(1.00, 1.00))
        writer.recordTask(GadiTask.of('LOCAL (sample_1)'))
        writer.finish()
        def html = writer.path.text

        then: 'the totals'
        html.contains('<div class="h1 mb-1">3.00</div>')    // service units
        html.contains('<div class="h1 mb-1">1.00</div>')    // wasted
        html.contains('33% of the service units')
        html.contains('<div class="h1 mb-1">75%</div>')     // efficiency: 50 + 100 of 200 CPU seconds
        html.contains('<div class="h1 mb-1">3</div>')       // tasks
        html.contains('1 without usage values')

        and: 'one row per process, most service units first'
        html.indexOf('<td class="sort-process text-break">ALIGN</td>') < html.indexOf('<td class="sort-process text-break">COUNT</td>')
        html.indexOf('<td class="sort-process text-break">COUNT</td>') < html.indexOf('<td class="sort-process text-break">LOCAL</td>')
        html.contains('<td class="sort-process text-break">ALIGN</td><td class="sort-tasks text-end">1</td><td class="sort-failed text-end">0</td><td class="sort-service-units text-end">2.00</td><td class="sort-wasted text-end">1.00</td><td class="sort-efficiency text-end">50%</td><td class="sort-walltime text-end">00:01:40</td>')
        html.contains('<td class="sort-process text-break">LOCAL</td><td class="sort-tasks text-end">1</td><td class="sort-failed text-end">0</td><td class="sort-service-units text-end">0.00</td><td class="sort-wasted text-end">0.00</td><td class="sort-efficiency text-end">-</td><td class="sort-walltime text-end">00:00:00</td>')

        and: 'the chart data, without the process that has no service units'
        def data = chartData(html)
        data.serviceUnits.labels == ['ALIGN', 'COUNT']
        data.serviceUnits.values == [2.00, 1.00]
        data.serviceUnits.other == 0
        data.wasted.labels == ['ALIGN']
        data.wasted.values == [1.00]

        and: 'no failed tasks section'
        !html.contains('Failed tasks')

        and: 'no placeholder is left'
        !(html =~ /@@[A-Z_]+@@/)
    }

    def "the pie shows the top processes by name and folds the rest into Other"() {
        given:
        def rows = (1..9).collect { int i -> row("P${i} (s)", usage(i as BigDecimal, 1.0)) }

        when:
        def data = new JsonSlurper().parseText(GadiReportWriterHtml.chartData(GadiUsageSummary.of(rows).processes)) as Map

        then:
        data.serviceUnits.labels == ['P9', 'P8', 'P7', 'P6', 'P5', 'P4', 'Other']
        data.serviceUnits.values == [9, 8, 7, 6, 5, 4, 3 + 2 + 1]
        data.serviceUnits.other == 3
    }

    def "the bar chart shows the processes that waste the most, at most #{GadiReportWriterHtml.BAR_ROWS}"() {
        given: 'process Pi wastes i x 0.5 service units; P0 wastes nothing'
        def rows = (0..12).collect { int i -> row("P${i} (s)", usage(i as BigDecimal, 0.5)) }

        when:
        def data = new JsonSlurper().parseText(GadiReportWriterHtml.chartData(GadiUsageSummary.of(rows).processes)) as Map

        then:
        data.wasted.labels == (12..3).collect { "P${it}" }
        data.wasted.values == (12..3).collect { it * 0.5 }
    }

    def "failed tasks are listed with their work directory and exit code"() {
        given:
        def writer = new GadiReportWriterHtml(tmp)

        when:
        writer.start()
        writer.recordTask(GadiTask.of('ALIGN (sample_1)', 'normal', 'work/ab'), usage(1.00, 0.5, 137))
        writer.recordTask(GadiTask.of('ALIGN (sample_2)', 'normal', 'work/cd'), usage(1.00, 0.5))
        writer.finish()
        def html = writer.path.text

        then:
        html.contains('Failed tasks')
        html.contains('<tr><td class="text-break">ALIGN (sample_1)</td><td class="text-secondary font-monospace text-break">work/ab</td><td class="text-end">137</td></tr>')
        !html.contains('ALIGN (sample_2)</td>')
        html.contains('1 failed')
        html.contains('<td class="sort-failed text-end"><span class="text-danger">1</span></td>')
    }

    def "task names are escaped, so a name cannot change the page"() {
        when:
        def html = GadiReportWriterHtml.render([row('<b>ALIGN</b> (sample "1")', usage(1.00, 0.5, 1), 'work/<x>')])

        then:
        html.contains('&lt;b&gt;ALIGN&lt;/b&gt; (sample &quot;1&quot;)')
        html.contains('work/&lt;x&gt;')
        !html.contains('<b>ALIGN</b>')
        and: 'in the chart data, a closing tag cannot end the script block'
        !html.contains('</b>')
        chartData(html).serviceUnits.labels == ['<b>ALIGN</b>']
    }

    def "a run with no Gadi jobs still writes a page"() {
        when:
        def html = GadiReportWriterHtml.render([row('LOCAL (s1)'), row('LOCAL (s2)')])
        def data = chartData(html)

        then:
        html.contains('<div class="h1 mb-1">0.00</div>')
        html.contains('No Gadi jobs')
        html.contains('<div class="h1 mb-1">-</div>')   // no efficiency
        data.serviceUnits.values == []
        data.wasted.values == []
    }

    def "the process table sorts by every column: the headings and the cells name the same columns"() {
        when:
        def html = GadiReportWriterHtml.render([row('ALIGN (s1)', usage(1.00, 0.5))])
        def headings = (html =~ /data-sort="(sort-[a-z-]+)"/).collect { it[1] }
        def cells = (html =~ /<td class="(sort-[a-z-]+) /).collect { it[1] }
        def listed = (html =~ /valueNames: \[([^\]]*)\]/)[0][1].findAll(/sort-[a-z-]+/)

        then:
        headings == ['sort-process', 'sort-tasks', 'sort-failed', 'sort-service-units', 'sort-wasted', 'sort-efficiency', 'sort-walltime']
        cells == headings
        listed == headings
    }

    def "the page loads Tabler and its ApexCharts from the CDN, with pinned versions"() {
        when:
        def html = GadiReportWriterHtml.render([])

        then:
        html.contains('https://cdn.jsdelivr.net/npm/@tabler/core@1.7.1/dist/css/tabler.min.css')
        html.contains('https://cdn.jsdelivr.net/npm/@tabler/core@1.7.1/dist/js/tabler.min.js')
        html.contains('https://cdn.jsdelivr.net/npm/@tabler/core@1.7.1/dist/libs/apexcharts/dist/apexcharts.min.js')
        html.contains('https://cdn.jsdelivr.net/npm/@tabler/core@1.7.1/dist/libs/list.js/dist/list.min.js')
    }

    def "percent: #ratio"() {
        expect:
        GadiReportWriterHtml.percent(ratio) == text

        where:
        ratio  || text
        0.0    || '0%'
        0.345  || '35%'
        0.995  || '100%'
        1.0    || '100%'
    }
}
