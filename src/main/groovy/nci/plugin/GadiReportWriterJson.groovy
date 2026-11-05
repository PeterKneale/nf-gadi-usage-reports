package nci.plugin

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes a JSON report in one go when the run finishes, with each
 * task's values grouped by process name.
 */
@CompileStatic
class GadiReportWriterJson extends GadiReportWriter {

    /** Report values for each task, grouped by process name. */
    final Map<String, List<Map<String, String>>> results = new ConcurrentHashMap<>()

    static final String FILE_NAME = REPORT_NAME + '.json'

    /** @param folder where to write {@link #FILE_NAME} */
    GadiReportWriterJson(Path folder) {
        super(folder.resolve(FILE_NAME))
    }

    @Override
    protected void record(String taskName, Map<String, String> values) {
        values['name'] = taskName
        results.compute(processName(taskName)) { String key, List<Map<String, String>> existingList ->
            if (existingList == null) {
                existingList = new ArrayList<Map<String, String>>()
            }

            synchronized (existingList) {
                existingList.add(values)
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
