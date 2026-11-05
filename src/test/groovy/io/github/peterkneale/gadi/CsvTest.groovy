package io.github.peterkneale.gadi

import spock.lang.Specification
import spock.lang.Unroll

class CsvTest extends Specification {

    @Unroll
    def "escape #description"() {
        expect:
        Csv.escape(value) == expected

        where:
        description           | value                     || expected
        'plain value'         | '0.34'                    || '0.34'                         // EXPECTED: left as is
        'empty value'         | ''                        || ''                             // EXPECTED: left as is
        'spaces and brackets' | 'ALIGN (sample_1)'        || 'ALIGN (sample_1)'             // EXPECTED: spaces and brackets need no quoting
        'comma'               | 'ALIGN (sample_1, lane2)' || '"ALIGN (sample_1, lane2)"'    // EXPECTED: Nextflow task names often contain commas
        'double quote'        | 'say "hi"'                || '"say ""hi"""'                 // EXPECTED: quotes doubled inside a quoted value
        'line feed'           | 'a\nb'                    || '"a\nb"'                       // EXPECTED: quoted, not split across lines
        'carriage return'     | 'a\rb'                    || '"a\rb"'                       // EXPECTED: quoted, not split across lines
        'missing value'       | null                      || ''                             // EXPECTED: empty field, was the text null
    }

    def "line joins escaped values with commas and ends with a newline"() {
        expect:
        Csv.line(['ALIGN (sample_1, lane2)', 'ALIGN', 'normal', '0.34']) ==
            '"ALIGN (sample_1, lane2)",ALIGN,normal,0.34\n'
    }

    def "missing values become empty fields, e.g. a non-Gadi job with only a name"() {
        expect:
        Csv.line(['ALIGN (sample_1)', 'ALIGN', null, null, '0.34']) == 'ALIGN (sample_1),ALIGN,,,0.34\n'
    }

    def "a line with a comma in a value still has one field per value"() {
        given:
        def values = ['ALIGN (sample_1, lane2)', 'ALIGN', 'normal']

        when:
        def fields = Csv.line(values).trim().split(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/)

        then:
        fields.size() == values.size()
    }
}
