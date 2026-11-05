package io.github.peterkneale.gadi

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes a JSON report in one go when the run finishes, with each
 * task's fields grouped by process name.
 */
@CompileStatic
class GadiReportWriterJson extends GadiReportWriter {

    /** Each task's row, grouped by process name. */
    final Map<String, List<Map<String, Object>>> results = new ConcurrentHashMap<>()

    static final String FILE_NAME = REPORT_NAME + '.json'

    /** @param folder where to write {@link #FILE_NAME} */
    GadiReportWriterJson(Path folder) {
        super(folder.resolve(FILE_NAME))
    }

    @Override
    protected void record(Map<String, Object> row) {
        results.compute(row.process as String) { String key, List<Map<String, Object>> existingList ->
            if (existingList == null) {
                existingList = new ArrayList<Map<String, Object>>()
            }

            synchronized (existingList) {
                existingList.add(row)
            }
            return existingList
        }
    }

    /** Writes the report, with processes listed by name so the order is the same every run. */
    @Override
    void finish() {
        Map<String, List<Map<String, Object>>> byProcessName = new TreeMap<>(results)
        Files.write(path, JsonOutput.prettyPrint(JsonOutput.toJson(byProcessName)).bytes)
        super.finish()
    }
}
