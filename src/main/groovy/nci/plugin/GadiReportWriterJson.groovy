package nci.plugin

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
    final Map<String, List<Map<String, String>>> results = new ConcurrentHashMap<>()

    static final String FILE_NAME = REPORT_NAME + '.json'

    /** @param folder where to write {@link #FILE_NAME} */
    GadiReportWriterJson(Path folder) {
        super(folder.resolve(FILE_NAME))
    }

    @Override
    protected void record(Map<String, String> row) {
        results.compute(row.process) { String key, List<Map<String, String>> existingList ->
            if (existingList == null) {
                existingList = new ArrayList<Map<String, String>>()
            }

            synchronized (existingList) {
                existingList.add(row)
            }
            return existingList
        }
    }

    @Override
    void finish() {
        Files.write(path, JsonOutput.prettyPrint(JsonOutput.toJson(results)).bytes)
        super.finish()
    }
}
