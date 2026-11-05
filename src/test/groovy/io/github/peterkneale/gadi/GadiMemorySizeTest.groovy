package io.github.peterkneale.gadi

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
    def "'#text' is not a size"() {
        expect:
        GadiMemorySize.parse(text) == null

        where:
        text << ['', '-', 'abc', '10', '10 GB', '10.GB', '10gb', '1.5XB']
    }
}
