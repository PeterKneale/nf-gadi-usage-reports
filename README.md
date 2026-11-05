# nf-gadi-usage-reports

A [Nextflow](https://www.nextflow.io/) plugin that reports the resources each task used when a pipeline runs on [NCI Gadi](https://nci.org.au/our-systems/hpc-systems) with the PBS Pro executor.

For every task it records the service units charged, CPU time, walltime, memory and JobFS used against what was requested, and the CPU efficiency. The results are written to a CSV report and a JSON report in the launch directory, with no configuration needed.

The same reports can also be produced afterwards, without Nextflow, from the job logs a run left behind. See [Reporting on existing job logs](#reporting-on-existing-job-logs).

`nf-gadi-usage-reports` is a fork of the [nf-gadi](https://github.com/eileen-xue/nf-gadi) plugin (v1.4.0). It is a separate plugin, so use it in place of `nf-gadi` rather than alongside it. See [Switching from nf-gadi](#switching-from-nf-gadi).

## Requirements

- Nextflow 24.04.1 or later
- Tasks run on Gadi with the PBS Pro executor (`process.executor = 'pbspro'`)

## Installation

### From the Nextflow plugin registry

Once published, the plugin is available from the [Nextflow plugin registry](https://registry.nextflow.io/plugins/nf-gadi-usage-reports). Installing from the registry needs Nextflow 25.10 or later:

```
module load nextflow/25.10.4
nextflow plugin install nf-gadi-usage-reports@1.0.0
```

Gadi compute nodes have no internet access, so install the plugin from a login node before running the pipeline.

### From source

To use an older Nextflow, or to install on a system without internet access, build the plugin on a machine that has internet access:

```
git clone https://github.com/peterkneale/nf-gadi-usage-reports.git
cd nf-gadi-usage-reports
module load nextflow
make install
```

`make install` installs the plugin into your local Nextflow plugins folder (`~/.nextflow/plugins` by default).

## Usage

Enable the plugin in `nextflow.config`:

```
plugins {
    id 'nf-gadi-usage-reports@1.0.0'
}
```

then run the pipeline as usual with `nextflow run main.nf`.

Or enable it for a single run on the command line:

```
nextflow run main.nf -plugins nf-gadi-usage-reports@1.0.0
```

## Reports

Each run writes two reports to the directory the pipeline is launched from. Both are overwritten by the next run.

| File               | Written                    | Contents                       |
| ------------------ | -------------------------- | ------------------------------ |
| `UsageReport.csv`  | As each task finishes      | One row per task               |
| `UsageReport.json` | When the pipeline finishes | Every task, grouped by process |

Because the CSV report is written as the pipeline runs, it keeps the rows written so far if the run is cut short, for example when the Nextflow head job reaches its walltime.

### CSV columns

| Column               | Description                                                          |
| -------------------- | -------------------------------------------------------------------- |
| `work_dir`           | The task's work directory, relative to the run's work directory      |
| `name`               | Task name, e.g. `ALIGN (sample_1)` |
| `process`            | Process name, e.g. `ALIGN`         |
| `queue`              | Gadi queue the job ran in          |
| `service_units`      | Service units charged for the job  |
| `cpus`               | CPUs requested                     |
| `cpu_time`           | CPU time used, as `HH:MM:SS`       |
| `used_walltime`      | Walltime used, as `HH:MM:SS`       |
| `requested_walltime` | Walltime requested, as `HH:MM:SS`  |
| `used_memory`        | Memory used, e.g. `10.89GB`        |
| `requested_memory`   | Memory requested, e.g. `42.0GB`    |
| `used_jobfs`         | Local disk (JobFS) used            |
| `requested_jobfs`    | Local disk (JobFS) requested       |
| `efficiency`         | CPU efficiency, see below          |
| `exit_code`          | The job's exit status              |

Values containing commas, such as task names with several tags, are quoted, so the file opens correctly in spreadsheet tools.

`work_dir` is relative to the run's work directory (`work` by default, or the `-work-dir` option), such as `94/348cfc33f75979f408e9e9d20d4d6e`, and absolute for a task whose work directory is somewhere else. It tells apart tasks with the same name, and shows where to find each task's logs.

### JSON report

The same fields, grouped by process. Processes are listed by name, so reports from the same tasks always come out in the same order. Service units, CPUs, efficiency and the exit code are numbers; durations and sizes are text as Gadi logs them; a missing value is `null`:

```json
{
    "COUNT": [
        {
            "work_dir": "94/348cfc33f75979f408e9e9d20d4d6e",
            "name": "COUNT (sample_1)",
            "process": "COUNT",
            "queue": "normal",
            "service_units": 0.40,
            "cpus": 6,
            "cpu_time": "00:02:22",
            "used_walltime": "00:01:09",
            "requested_walltime": "05:00:00",
            "used_memory": "10.89GB",
            "requested_memory": "42.0GB",
            "used_jobfs": "0B",
            "requested_jobfs": "100.0MB",
            "efficiency": 0.34,
            "exit_code": 0
        }
    ]
}
```

### Efficiency

Efficiency is the CPU time used as a fraction of the CPU time available:

```
efficiency = CPU time / (walltime used x CPUs requested)
```

For example, a job that used 2m 22s of CPU time over 1m 9s of walltime on 6 CPUs has an efficiency of 142 / (69 x 6) = 0.34. A value of 1.00 means every requested CPU was busy for the whole job, so a low value suggests the job requested more CPUs than it used.

Efficiency is left empty when the CPU time, walltime or CPU count is missing, or when the walltime is zero.

### Where the values come from

When a job finishes, Gadi's PBS appends a Resource Usage block to the job's output, which Nextflow keeps as `.command.log` in the task's work directory. The plugin reads the values from that block.

- **Waiting for the log:** PBS writes the block shortly after the job ends, so the plugin waits up to 2 minutes for it to appear and be complete. It waits in the background, so the pipeline is not held up. When the pipeline finishes, the plugin waits for any logs still outstanding before writing the JSON report.
- **Missing or unreadable logs:** if the log never appears, or cannot be read, the task is still reported, with its usage values left empty, and a warning is logged.
- **Cached tasks:** when a pipeline is resumed with `-resume`, cached tasks are reported from the log in their existing work directory. If that directory has been cleaned up, the task is reported without usage values.
- **Tasks not run on Gadi:** tasks run by another executor, such as `local`, are reported with their name only.

## Settings

All settings go in a `gadi` scope in `nextflow.config`, and all are optional.

### Publishing the reports

Set `publishTo` to a folder to have both reports copied into it when the pipeline finishes. This can be a local folder, or any location Nextflow can resolve as a path, such as `s3://`, `gs://` or `az://`, given the relevant credentials and plugin are configured. The folder is created if it does not exist.

```
gadi {
    publishTo = "s3://bucket/results/"
}
```

To keep the reports from every run, publish each run to its own folder, for example with a timestamp:

```
params {
    timestamp = new java.util.Date().format('yyyy-MM-dd_HH-mm-ss')
}

gadi {
    publishTo = "s3://bucket/results/${params.timestamp}/"
}
```

`publishTo` can also be a closure, which is evaluated when the pipeline finishes. If publishing fails, a warning is logged and the run is not affected.

## Reporting on existing job logs

The `gadi-usage-report` command line tool writes the same two reports for job logs that already exist, for example from a run that did not use the plugin. It needs only Java 17 or later; Nextflow does not need to be installed.

It searches a folder and all its subfolders for Gadi job logs (`.command.log`), and writes one `UsageReport.csv` and one `UsageReport.json` to the current directory, covering every log it finds. The folder can hold any number of pipeline runs, so one report can cover every task of every run.

### Getting the tool

Download `gadi-usage-report-1.0.0.jar` from the [releases page](https://github.com/peterkneale/nf-gadi-usage-reports/releases). It is a single self-contained file that can be copied to Gadi or anywhere else with Java.

Or build it from source:

```
git clone https://github.com/peterkneale/nf-gadi-usage-reports.git
cd nf-gadi-usage-reports
make cli
```

This builds `build/libs/gadi-usage-report-1.0.0.jar`.

### Running it

Point it at a Nextflow work directory, a folder holding several runs' work directories, or any folder containing job logs:

```
java -jar gadi-usage-report-1.0.0.jar /scratch/ab12/my_pipeline/work
java -jar gadi-usage-report-1.0.0.jar /scratch/ab12/my_pipeline/runs
```

The reports are written to the current directory, replacing any from an earlier run there.

When it finishes it prints a summary:

```
Found 13 job logs in /scratch/ab12/my_pipeline/work
  13 complete
  0 incomplete (some usage values missing)
  0 unreadable
Wrote /home/user/UsageReport.csv
Wrote /home/user/UsageReport.json
```

Run it with `--help` to show the usage message.

Incomplete and unreadable logs are listed by path, up to 10 of each. Incomplete logs are still reported, with their missing values left empty.

The exit code is `0` on success, `1` if no job logs were found or any could not be read, and `2` if the arguments are wrong, so the tool can be used in scripts.

### Task names

Each task is reported as Nextflow named it, such as `MAIN:ALIGN (sample_1)`, read from the job script (`.command.run`) Nextflow writes in every work directory. The `process` and `queue` columns come from the same file, so tasks are named and grouped exactly as the plugin reports them.

The task name is read from the header Nextflow 24.04 and later write at the top of the job script:

```
### ---
### name: 'MAIN:ALIGN (sample_1)'
```

If a work directory has a job log but no job script, or the job script has no such header because an older Nextflow wrote it, the task is named by its work directory instead.

The `work_dir` column holds each task's work directory relative to the folder searched, such as `run1/94/348cfc33f75979f408e9e9d20d4d6e`. It tells apart tasks with the same name from different runs, and shows where to find each task's logs. Pointed at a run's work directory, the tool reports the same `work_dir` values as the plugin.

## Switching from nf-gadi

- Replace `nf-gadi` with `nf-gadi-usage-reports@1.0.0` in your `plugins` block or `-plugins` option.
- Both reports are now always written, as `UsageReport.csv` and `UsageReport.json`. The `format` and `output` settings are no longer used and can be removed.
- `publishTo` is now a folder rather than a file. A setting such as `"s3://bucket/results/UsageReport.csv"` would now create a folder called `UsageReport.csv`, so change it to `"s3://bucket/results/"`.

## Development

Build and test the plugin with:

```
make test       # run the unit tests, then check the built command line tool
make assemble   # build the plugin
make install    # install it into your local Nextflow plugins folder
make cli        # build the command line tool
```

The parser is tested against real Gadi job logs in `src/test/resources/nci/plugin/scenarios/`, one folder per scenario. The logs have been anonymised: batch and sample names, job IDs, project codes, usernames and flowcell IDs are replaced with placeholders.

The command line tool is tested by running it against the same scenarios and comparing its reports with golden copies in `src/test/resources/nci/plugin/cli/golden/`. The unit tests do this in-process; `make test` then runs the built jar the same way with `java -jar`, and checks that no Nextflow classes were bundled into it.

GitHub Actions runs the same checks on every push and pull request. Pushing a tag such as `v1.0.0`, matching the version in `build.gradle`, creates a GitHub release with the command line tool attached.

To add a scenario:

1. Create a folder holding the anonymised `.command.log`.
2. Add the folder name to `SCENARIOS` in `GadiScenarioTest`.
3. Add a row to the test's table with the values from the log's Resource Usage block, checked by hand.
4. Regenerate the golden reports, and check that the only change is the new scenario's row:

   ```
   make cli
   JAR=$PWD/build/libs/gadi-usage-report-1.0.0.jar
   (cd src/test/resources/nci/plugin/cli/golden && java -jar "$JAR" ../../scenarios)
   git diff src/test/resources/nci/plugin/cli/golden
   ```

Raw, unanonymised logs can be kept in `src/test/data/`, which is ignored by git. Never commit them.

## Credits

Based on [nf-gadi](https://github.com/eileen-xue/nf-gadi), built from the [Nextflow plugin template](https://github.com/nextflow-io/nf-plugin-template).

## License

[Apache License 2.0](COPYING)
