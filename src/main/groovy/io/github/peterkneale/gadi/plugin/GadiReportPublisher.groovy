package io.github.peterkneale.gadi.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.file.FileHelper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Copies the finished reports into the folder set by {@code gadi.publishTo},
 * e.g. {@code s3://bucket/results/}. Does nothing when it is not set.
 */
@Slf4j
@CompileStatic
class GadiReportPublisher {

    private final Object publishTo

    /**
     * @param publishTo the {@code gadi.publishTo} setting: anything Nextflow
     *        can resolve as a path, or a closure returning one
     */
    GadiReportPublisher(Object publishTo) {
        this.publishTo = publishTo
    }

    /** Copies each report into the publish folder, keeping its file name. */
    void publish(List<Path> reports) {
        String destination = resolvePublishTo()
        if (destination == null) {
            return
        }

        Path folder
        try {
            folder = FileHelper.asPath(destination) as Path
            Files.createDirectories(folder)
        } catch (Exception e) {
            log.warn "Failed to publish usage reports to '$destination': ${e.message}"
            return
        }

        for (Path report : reports) {
            Path dest = folder.resolve(report.fileName.toString())
            try {
                Files.copy(report, dest, StandardCopyOption.REPLACE_EXISTING)
                log.info "Usage report published to $dest"
            } catch (Exception e) {
                log.warn "Failed to publish usage report to '$dest': ${e.message}"
            }
        }
    }

    /**
     * Resolves {@code publishTo}, which may be a closure. Evaluated when
     * publishing, so a closure sees state from the end of the run.
     *
     * @return the destination, or null when publishing is not configured
     */
    String resolvePublishTo() {
        if (publishTo == null) return null

        Object value = publishTo
        if (value instanceof Closure) {
            value = ((Closure) value).call()
        }

        if (value == null) return null

        String result = value.toString().trim()
        return result.isEmpty() ? null : result
    }
}
