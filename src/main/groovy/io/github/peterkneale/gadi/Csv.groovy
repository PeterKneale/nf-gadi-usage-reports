package io.github.peterkneale.gadi

import groovy.transform.CompileStatic

/**
 * Formats CSV lines following RFC 4180: a value containing a comma,
 * double quote or line break is wrapped in double quotes, with any
 * double quotes inside it doubled.
 */
@CompileStatic
class Csv {

    /** Joins the values into one CSV line, ending with a newline. */
    static String line(List<String> values) {
        return values.collect { escape(it) }.join(',') + '\n'
    }

    /** A missing (null) value becomes an empty field. */
    static String escape(String value) {
        if (value == null) {
            return ''
        }
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            return '"' + value.replace('"', '""') + '"'
        }
        return value
    }
}
