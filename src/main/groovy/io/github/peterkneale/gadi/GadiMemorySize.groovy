package io.github.peterkneale.gadi

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.util.regex.Matcher

/**
 * An amount of memory or disk as Gadi reports it, e.g. "10.89GB" or "0B".
 * The amount and unit are kept exactly as logged, so {@link #toString()}
 * gives back the original text. Sizes are reported as logged and never
 * converted, since PBS does not say whether its units are binary or decimal.
 */
@Immutable
@CompileStatic
class GadiMemorySize {

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

    @Override
    String toString() {
        return amount.toPlainString() + unit
    }
}
