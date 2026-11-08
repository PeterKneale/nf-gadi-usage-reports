package io.github.peterkneale.gadi

import groovy.transform.CompileStatic

import java.math.RoundingMode

/**
 * The totals of a usage report: for the whole run and for each process.
 * Built from the rows that the writers record, so it sums up exactly the
 * values the CSV and JSON reports hold.
 */
@CompileStatic
class GadiUsageSummary {

    /** The whole run. */
    final Totals run = new Totals('')

    /** Each process, by name. */
    private final Map<String, Totals> byProcess = new TreeMap<>()

    /** The tasks whose exit code is not 0, in the order recorded. */
    final List<Map<String, Object>> failedTasks = []

    static GadiUsageSummary of(List<Map<String, Object>> rows) {
        GadiUsageSummary summary = new GadiUsageSummary()
        rows.each { Map<String, Object> row -> summary.add(row) }
        return summary
    }

    private void add(Map<String, Object> row) {
        String process = row.process as String
        Totals totals = byProcess.computeIfAbsent(process) { String name -> new Totals(name) }
        run.add(row)
        totals.add(row)
        Integer exitCode = row.exit_code as Integer
        if (exitCode != null && exitCode != 0) {
            failedTasks << row
        }
    }

    /** Every process, the most service units first, then by name. */
    List<Totals> getProcesses() {
        return byProcess.values().sort(false) { Totals a, Totals b ->
            b.serviceUnits <=> a.serviceUnits ?: a.name <=> b.name
        }
    }

    /** The sums for one process, or for the run. */
    static class Totals {
        final String name
        int tasks = 0
        int failed = 0
        /** Tasks with no usage values at all: they did not run as Gadi jobs, or their log could not be read. */
        int withoutUsage = 0
        BigDecimal serviceUnits = 0.0
        /** Service units of CPU time left idle: each task's service units x (1 - efficiency). */
        BigDecimal wastedServiceUnits = 0.0
        long walltimeSeconds = 0
        private long cpuSeconds = 0
        private long availableSeconds = 0

        Totals(String name) {
            this.name = name
        }

        private void add(Map<String, Object> row) {
            tasks++
            Integer exitCode = row.exit_code as Integer
            if (exitCode != null && exitCode != 0) {
                failed++
            }
            BigDecimal su = row.service_units as BigDecimal
            Integer cpus = row.cpus as Integer
            if (su == null && cpus == null && exitCode == null) {
                withoutUsage++
            }
            if (su != null) {
                serviceUnits += su
                BigDecimal efficiency = row.efficiency as BigDecimal
                if (efficiency != null) {
                    wastedServiceUnits += su * (1.0 - efficiency).max(0.0)
                }
            }
            Long walltime = seconds(row.used_walltime as String)
            Long cpuTime = seconds(row.cpu_time as String)
            if (walltime != null) {
                walltimeSeconds += walltime
            }
            if (walltime != null && cpuTime != null && cpus != null) {
                cpuSeconds += cpuTime
                availableSeconds += walltime * cpus
            }
        }

        /** CPU time used over CPU time available, across the tasks; null when no task has both. */
        Double getEfficiency() {
            return availableSeconds == 0 ? null : cpuSeconds / (double) availableSeconds
        }

        /** The share of service units wasted, 0 to 1; null when no service units. */
        Double getWastedShare() {
            return serviceUnits == 0.0 ? null : (wastedServiceUnits / serviceUnits).doubleValue()
        }

        /** "13:55:28" -> seconds; null for null or text that is not H:MM:SS. */
        static Long seconds(String hms) {
            if (hms == null) {
                return null
            }
            String[] parts = hms.split(':')
            if (parts.length != 3 || !parts.every { String p -> p.isLong() }) {
                return null
            }
            return parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
        }

        /** Service units to 2 decimal places. */
        static String su(BigDecimal value) {
            return value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    }
}
