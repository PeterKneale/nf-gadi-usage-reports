package io.github.peterkneale.gadi

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.time.Duration

/**
 * The values reported in the Resource Usage block that Gadi's PBS
 * appends to a job's output. A field is null when its line is missing.
 */
@Immutable
@CompileStatic
class GadiUsageRecord {
    Integer exitCode
    BigDecimal serviceUnits
    Integer cpus
    Duration cpuTime
    Duration walltimeUsed
    Duration walltimeRequested
    GadiMemorySize memoryUsed
    GadiMemorySize memoryRequested
    GadiMemorySize jobfsUsed
    GadiMemorySize jobfsRequested

    /**
     * True once the fields the observer waits for have been written.
     * PBS appends the usage block when the job ends, so a log read too
     * early can be missing some or all of it.
     */
    boolean isComplete() {
        return serviceUnits != null && walltimeUsed != null && cpus != null && memoryUsed != null
    }
}
