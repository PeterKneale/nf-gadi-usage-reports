package io.github.peterkneale.gadi

import groovy.transform.CompileStatic

import java.time.Duration

/**
 * Calculates CPU efficiency for a Gadi job: the CPU time used as a
 * fraction of the CPU time available (walltime used x CPUs requested).
 * 1.0 means every requested CPU was busy for the whole run.
 */
@CompileStatic
class GadiEfficiencyCalculator {

    /**
     * @return the efficiency, or null when CPU time, walltime or CPUs
     *         are missing from the log, or walltime or CPUs are zero.
     */
    static Double calculate(GadiUsageRecord usage) {
        Duration cpuTime = usage.cpuTime
        Duration walltime = usage.walltimeUsed
        Integer cpus = usage.cpus

        if (cpuTime == null || walltime == null || cpus == null) {
            return null
        }

        // No CPU time was available, so there is no efficiency to report
        if (walltime.isZero() || cpus == 0) {
            return null
        }

        double available = (double) walltime.seconds * cpus
        return cpuTime.seconds / available
    }
}
