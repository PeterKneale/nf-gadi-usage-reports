package io.github.peterkneale.gadi

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Extracts a {@link GadiUsageRecord} from a Gadi job log (.command.log).
 * When a value appears on more than one line, the last one wins.
 */
@Slf4j
@CompileStatic
class GadiUsageParser {

    /** Keyed by {@link GadiUsageRecord} property name. */
    private static final Map<String, Pattern> FIELDS = [
        exitCode:          ~/Exit Status:\s+(-?\d+)/,
        serviceUnits:      ~/Service Units:\s+(\d+(?:\.\d+)?)/,
        cpus:              ~/NCPUs Requested:\s+(\d+)/,
        cpuTime:           ~/CPU Time Used:\s+(\d+:\d{2}:\d{2})/,
        walltimeUsed:      ~/Walltime Used:\s+(\d+:\d{2}:\d{2})/,
        walltimeRequested: ~/Walltime Requested:\s+(\d+:\d{2}:\d{2})/,
        memoryUsed:        ~/(?<!GPU )Memory Used:\s+(\d+(?:\.\d+)?[KMGTP]?B)/,
        memoryRequested:   ~/Memory Requested:\s+(\d+(?:\.\d+)?[KMGTP]?B)/,
        jobfsUsed:         ~/JobFS Used:\s+(\d+(?:\.\d+)?[KMGTP]?B)/,
        jobfsRequested:    ~/JobFS Requested:\s+(\d+(?:\.\d+)?[KMGTP]?B)/,
    ] as LinkedHashMap<String, Pattern>

    private final Path logFile

    GadiUsageParser(Path logFile) {
        this.logFile = logFile
    }

    /**
     * @param warnIfMissing false to skip the missing-field warnings, e.g.
     *        while polling a log that is still being written
     */
    GadiUsageRecord parse(boolean warnIfMissing = true) {
        byte[] bytes = Files.readAllBytes(logFile)
        // Decode leniently: tool output in the log may not be valid UTF-8
        String text = new String(bytes, StandardCharsets.UTF_8)
        return parseText(text, logFile.toString(), warnIfMissing)
    }

    /**
     * @param source where the text came from, used in log messages
     * @param warnIfMissing false to skip the missing-field warnings
     */
    static GadiUsageRecord parseText(String text, String source = 'text', boolean warnIfMissing = true) {
        Map<String, String> found = [:]
        text.eachLine { String line ->
            FIELDS.each { String field, Pattern pattern ->
                Matcher matcher = pattern.matcher(line)
                if (matcher.find()) {
                    String value = matcher.group(1)
                    log.debug "Found ${field} = ${value} in ${source}"
                    found[field] = value
                }
            }
        }

        if (warnIfMissing) {
            FIELDS.keySet().findAll { !found.containsKey(it) }.each { String field ->
                log.warn "No ${field} found in ${source}"
            }
        }

        return new GadiUsageRecord(
            exitCode:          toInteger(found.exitCode),
            serviceUnits:      toDecimal(found.serviceUnits),
            cpus:              toInteger(found.cpus),
            cpuTime:           toDuration(found.cpuTime),
            walltimeUsed:      toDuration(found.walltimeUsed),
            walltimeRequested: toDuration(found.walltimeRequested),
            memoryUsed:        toMemorySize(found, 'memoryUsed', source),
            memoryRequested:   toMemorySize(found, 'memoryRequested', source),
            jobfsUsed:         toMemorySize(found, 'jobfsUsed', source),
            jobfsRequested:    toMemorySize(found, 'jobfsRequested', source),
        )
    }

    private static Integer toInteger(String text) {
        return text == null ? null : Integer.valueOf(text)
    }

    private static BigDecimal toDecimal(String text) {
        return text == null ? null : new BigDecimal(text)
    }

    /** Converts "HH:MM:SS", as matched by the patterns above. */
    private static Duration toDuration(String text) {
        if (text == null) {
            return null
        }
        List<Long> parts = text.split(':').collect { Long.valueOf(it) }
        return Duration.ofHours(parts[0]).plusMinutes(parts[1]).plusSeconds(parts[2])
    }

    private static GadiMemorySize toMemorySize(Map<String, String> found, String field, String source) {
        String text = found[field]
        if (text == null) {
            return null
        }
        GadiMemorySize size = GadiMemorySize.parse(text)
        if (size == null) {
            log.warn "Cannot read ${field} '${text}' in ${source}"
        }
        return size
    }
}
