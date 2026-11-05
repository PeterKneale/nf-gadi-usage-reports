package nci.plugin

import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Writes a CSV report as the run progresses: the header on start, then
 * one row as each task is recorded. If the run is cut short, e.g. the
 * Nextflow head job is killed, the rows written so far are kept.
 */
@CompileStatic
class GadiReportWriterCsv extends GadiReportWriter {

    /** The CSV header: one column per field. */
    static final List<String> CSV_HEADER = FIELDS

    static final String FILE_NAME = REPORT_NAME + '.csv'

    /** @param folder where to write {@link #FILE_NAME} */
    GadiReportWriterCsv(Path folder) {
        super(folder.resolve(FILE_NAME))
    }

    @Override
    void start() {
        Files.write(path, Csv.line(CSV_HEADER).getBytes())
    }

    @Override
    protected void record(Map<String, Object> row) {
        List<String> values = FIELDS.collect { String field -> text(row[field]) }
        append(Csv.line(values))
    }

    /** Synchronised so rows from tasks finishing at the same time are not interleaved. */
    private synchronized void append(String line) {
        Files.write(path, line.getBytes(), StandardOpenOption.APPEND)
    }
}
