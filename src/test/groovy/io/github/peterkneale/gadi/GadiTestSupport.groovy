package io.github.peterkneale.gadi

import java.time.Duration

/**
 * Helpers for writing expected values in tests as they appear in a Gadi log.
 */
class GadiTestSupport {

    /** "00:02:22" -> 2 minutes 22 seconds; null stays null. */
    static Duration hms(String text) {
        if (text == null) {
            return null
        }
        def (h, m, s) = text.split(':').collect { it as long }
        Duration.ofSeconds(h * 3600 + m * 60 + s)
    }

    /** "10.89GB" -> 10.89 GB; null stays null. */
    static GadiMemorySize size(String text) {
        text == null ? null : GadiMemorySize.parse(text)
    }
}
