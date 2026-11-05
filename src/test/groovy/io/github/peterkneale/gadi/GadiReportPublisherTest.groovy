package io.github.peterkneale.gadi

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class GadiReportPublisherTest extends Specification {

    @TempDir
    Path tmp

    List<Path> reports

    def setup() {
        Path csv = tmp.resolve('UsageReport.csv')
        Path json = tmp.resolve('UsageReport.json')
        csv.text = 'csv report'
        json.text = 'json report'
        reports = [csv, json]
    }

    def "copies every report into the publishTo folder, keeping the file names"() {
        given:
        Path folder = tmp.resolve('published')

        when:
        new GadiReportPublisher(folder.toString()).publish(reports)

        then:
        folder.resolve('UsageReport.csv').text == 'csv report'
        folder.resolve('UsageReport.json').text == 'json report'
    }

    def "creates the publishTo folder, including missing parent folders"() {
        given:
        Path folder = tmp.resolve('results/2026-11-05')

        when:
        new GadiReportPublisher(folder.toString()).publish(reports)

        then:
        Files.isDirectory(folder)
        folder.resolve('UsageReport.csv').text == 'csv report'
    }

    def "publishTo can be a closure, evaluated when publishing"() {
        given:
        Path folder = tmp.resolve('from_closure')

        when:
        new GadiReportPublisher({ -> folder.toString() }).publish(reports)

        then:
        folder.resolve('UsageReport.json').text == 'json report'
    }

    @Unroll
    def "publishTo #description: nothing is published"() {
        expect:
        new GadiReportPublisher(publish_to).resolvePublishTo() == null

        where:
        description           | publish_to
        'not set'             | null
        'blank'               | '   '
        'closure giving null' | { -> null }
    }

    def "publishTo is trimmed"() {
        expect:
        new GadiReportPublisher(' /data/results/ ').resolvePublishTo() == '/data/results/'
    }

    def "a failed publish is logged, not thrown"() {
        given: 'a publishTo that is a file, so cannot be used as a folder'
        Path file = tmp.resolve('not_a_folder')
        file.text = 'x'

        when:
        new GadiReportPublisher(file.toString()).publish(reports)

        then:
        noExceptionThrown()
    }
}
