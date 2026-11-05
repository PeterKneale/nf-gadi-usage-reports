package io.github.peterkneale.gadi

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

import static io.github.peterkneale.gadi.GadiTestSupport.hms

class GadiUsageReaderTest extends Specification {

    @TempDir
    Path tmp

    private static Path scenario(String name) {
        Path.of(GadiUsageReaderTest.getResource("scenarios/${name}").toURI())
    }

    def "the job log is .command.log in the work directory"() {
        expect:
        GadiUsageReader.jobLog(tmp) == tmp.resolve('.command.log')
    }

    def "read: a finished task's usage comes from its job log"() {
        when:
        def usage = new GadiUsageReader().read(scenario('tool_featurecounts'))

        then:
        usage.isComplete()
        usage.cpuTime == hms('00:02:22')
    }

    def "read: a missing job log gives empty usage, without waiting"() {
        when:
        long start = System.currentTimeMillis()
        def usage = new GadiUsageReader(60_000, 60_000).read(tmp.resolve('cleaned_up'))

        then:
        usage == new GadiUsageRecord()
        System.currentTimeMillis() - start < 5_000   // would be a minute if it waited
    }

    def "waitAndRead: reads a log that PBS writes while it waits"() {
        given:
        Path workDir = tmp.resolve('work')
        Files.createDirectories(workDir)
        Path finished = scenario('tool_featurecounts').resolve('.command.log')

        when: 'the log appears after the reader has started waiting'
        def writer = Thread.start {
            sleep(200)
            Files.copy(finished, GadiUsageReader.jobLog(workDir))
        }
        def usage = new GadiUsageReader(50, 5_000).waitAndRead(workDir)
        writer.join()

        then:
        usage.isComplete()
        usage.cpuTime == hms('00:02:22')
    }

    def "waitAndRead: a job log that never appears gives empty usage once the wait is over"() {
        expect:
        new GadiUsageReader(1, 3).waitAndRead(tmp.resolve('never_written')) == new GadiUsageRecord()
    }
}
