package io.github.peterkneale.gadi

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.util.regex.Matcher

/**
 * An amount of memory or disk as Gadi reports it, e.g. "10.89GB" or "0B".
 * The amount and unit are kept exactly as logged, so {@link #toString()}
 * gives back the original text.
 *
 * Units are binary, as in PBS: 1KB = 1024 bytes.
 */
@Immutable
@CompileStatic
class GadiMemorySize {

    private static final List<String> UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']

    BigDecimal amount
    String unit

    /** @return the size, or null if the text is not a size such as "10.89GB" */
    static GadiMemorySize parse(String text) {
        Matcher matcher = text =~ /^(\d+(?:\.\d+)?)([KMGTP]?B)$/
        if (!matcher.matches()) {
            return null
        }
        return new GadiMemorySize(new BigDecimal(matcher.group(1)), matcher.group(2))
    }

    /** The size in bytes, rounded down to a whole byte. */
    long toBytes() {
        BigDecimal multiplier = 1024G ** UNITS.indexOf(unit)
        return (amount * multiplier).toBigInteger().longValueExact()
    }

    @Override
    String toString() {
        return amount.toPlainString() + unit
    }
}
