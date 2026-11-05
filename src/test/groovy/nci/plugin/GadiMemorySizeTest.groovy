package nci.plugin

import spock.lang.Specification
import spock.lang.Unroll

class GadiMemorySizeTest extends Specification {

    @Unroll
    def "'#text' is read as #amount #unit and written back unchanged"() {
        when:
        def size = GadiMemorySize.parse(text)

        then:
        size.amount == amount
        size.unit == unit
        size.toString() == text

        where:
        text      || amount | unit
        '10.89GB' || 10.89  | 'GB'   // EXPECTED: two decimal places kept
        '14.0GB'  || 14.0   | 'GB'   // EXPECTED: trailing zero kept, not written back as 14GB
        '100.0MB' || 100.0  | 'MB'
        '0B'      || 0      | 'B'    // EXPECTED: JobFS used is often logged like this
        '512KB'   || 512    | 'KB'
    }

    @Unroll
    def "#text is #bytes bytes"() {
        expect:
        GadiMemorySize.parse(text).toBytes() == bytes

        where:
        text     || bytes
        '0B'     || 0
        '1KB'    || 1024                 // EXPECTED: binary units, as in PBS
        '1.5MB'  || 1_572_864
        '14.0GB' || 15_032_385_536
        '1TB'    || 1_099_511_627_776
    }

    @Unroll
    def "'#text' is not a size"() {
        expect:
        GadiMemorySize.parse(text) == null

        where:
        text << ['', '-', 'abc', '10', '10 GB', '10.GB', '10gb', '1.5XB']
    }
}
