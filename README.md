# nf-gadi-usage-reports

A [Nextflow](https://www.nextflow.io/) plugin for [NCI Gadi](https://nci.org.au/our-systems/hpc-systems). It reports the resources that each task used.

For each task, the plugin records the service units, the CPU time, the walltime, the memory and the JobFS. It records the used amount and the requested amount. It also calculates the CPU efficiency. The plugin writes a CSV report and a JSON report. No configuration is necessary.

A command line tool makes the same reports from the job logs of old runs. The tool does not need Nextflow. See [Make reports from existing job logs](#make-reports-from-existing-job-logs).

## Quick Start

### Requirements

- Nextflow 24.04.1 or later
- Tasks that run on Gadi with the PBS Pro executor (`process.executor = 'pbspro'`)

### Install the plugin

Install from the [Nextflow plugin registry](https://registry.nextflow.io/plugins/nf-gadi-usage-reports). This needs Nextflow 25.10 or later:

```
module load nextflow/25.10.4
nextflow plugin install nf-gadi-usage-reports@1.0.0
```

Gadi compute nodes have no internet access. Install the plugin on a login node.

If you use an older Nextflow, install from source. Do this on a machine with internet access:

```
git clone https://github.com/peterkneale/nf-gadi-usage-reports.git
cd nf-gadi-usage-reports
module load nextflow
make install
```

`make install` copies the plugin to your Nextflow plugins folder. The default folder is `~/.nextflow/plugins`.

### Enable the plugin

Add the plugin to `nextflow.config`:

```
plugins {
    id 'nf-gadi-usage-reports@1.0.0'
}
```

Or enable it for one run:

```
nextflow run main.nf -plugins nf-gadi-usage-reports@1.0.0
```

### Run the pipeline

Run the pipeline as usual. When it completes, find the two reports in the launch directory:

| File               | Written                    | Contents                       |
| ------------------ | -------------------------- | ------------------------------ |
| `UsageReport.csv`  | As each task completes     | One row for each task          |
| `UsageReport.json` | When the pipeline completes | All tasks, grouped by process |

The next run replaces both files.

## Usage

### The CSV report

The plugin writes the CSV report while the pipeline runs. If the run stops early, the rows written so far remain. An example is a Nextflow head job that reaches its walltime.

| Column               | Description                                              |
| -------------------- | -------------------------------------------------------- |
| `work_dir`           | The work directory of the task, see below                |
| `name`               | The task name, for example `ALIGN (sample_1)`            |
| `process`            | The process name, for example `ALIGN`                    |
| `queue`              | The Gadi queue that ran the job                          |
| `service_units`      | The service units charged for the job                    |
| `cpus`               | The number of CPUs requested                             |
| `cpu_time`           | The CPU time used, as `HH:MM:SS`                         |
| `used_walltime`      | The walltime used, as `HH:MM:SS`                         |
| `requested_walltime` | The walltime requested, as `HH:MM:SS`                    |
| `used_memory`        | The memory used, for example `10.89GB`                   |
| `requested_memory`   | The memory requested, for example `42.0GB`               |
| `used_jobfs`         | The local disk (JobFS) used                              |
| `requested_jobfs`    | The local disk (JobFS) requested                         |
| `efficiency`         | The CPU efficiency, see below                            |
| `exit_code`          | The exit status of the job                               |

A value that contains a comma is quoted. An example is a task name with two tags. Spreadsheet tools open the file correctly.

`work_dir` is relative to the work directory of the run. The default work directory is `work`. You can change it with the `-work-dir` option. An example value is `94/348cfc33f75979f408e9e9d20d4d6e`. If the task ran somewhere else, `work_dir` is an absolute path. Use `work_dir` to tell apart tasks with the same name, and to find the logs of a task.

### The JSON report

The JSON report has the same fields as the CSV report. The tasks are grouped by process. The processes are in name order, so the same tasks always give the same file.

Service units, CPUs, efficiency and the exit code are numbers. Durations and sizes are text, as Gadi logs them. A missing value is `null`.

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

Efficiency is the CPU time used, divided by the CPU time available:

```
efficiency = CPU time / (walltime used x CPUs requested)
```

Example: a job used 2m 22s of CPU time. It ran for 1m 9s on 6 CPUs. Its efficiency is 142 / (69 x 6) = 0.34.

A value of 1.00 means that all requested CPUs were busy for the full job. A low value means that the job requested more CPUs than it used.

Efficiency is empty when the CPU time, the walltime or the CPU count is missing. It is also empty when the walltime is zero.

### Where the values come from

When a job completes, PBS adds a Resource Usage block to the output of the job. Nextflow keeps this output as `.command.log` in the work directory of the task. The plugin reads the values from this block.

- **Wait for the log.** PBS writes the block a short time after the job ends. The plugin waits up to 2 minutes for the block. It waits in the background, so the pipeline continues. When the pipeline completes, the plugin waits for the last logs before it writes the JSON report.
- **Missing or unreadable logs.** If the log does not appear, or the plugin cannot read it, the plugin reports the task with empty values. It also writes a warning to the Nextflow log.
- **Cached tasks.** When you resume a pipeline with `-resume`, the plugin reads the log of each cached task from its old work directory. If that directory is gone, the plugin reports the task with empty values.
- **Tasks that did not run on Gadi.** A task that ran with a different executor, for example `local`, is reported with its name only.

### Publish the reports

All settings go in a `gadi` block in `nextflow.config`. All settings are optional.

Set `publishTo` to a folder. When the pipeline completes, the plugin copies both reports to this folder. The folder can be local, or any location that Nextflow can open as a path, for example `s3://`, `gs://` or `az://`. The related credentials and plugin must be configured. The plugin creates the folder if it does not exist.

```
gadi {
    publishTo = "s3://bucket/results/"
}
```

To keep the reports of all runs, give each run its own folder. This example uses a timestamp:

```
params {
    timestamp = new java.util.Date().format('yyyy-MM-dd_HH-mm-ss')
}

gadi {
    publishTo = "s3://bucket/results/${params.timestamp}/"
}
```

`publishTo` can also be a closure. The plugin evaluates it when the pipeline completes. If the copy fails, the plugin writes a warning. The run is not affected.

### Make reports from existing job logs

The `gadi-usage-report` command line tool writes the same two reports from job logs that already exist. An example is a run that did not use the plugin. The tool needs Java 17 or later. It does not need Nextflow.

The tool searches a folder and all its subfolders for job logs (`.command.log`). It writes one `UsageReport.csv` and one `UsageReport.json` to the current directory. The reports include all logs found. The folder can hold many pipeline runs, so one report can cover all tasks of all runs.

#### Get the tool

Download `gadi-usage-report.jar` from the [releases page](https://github.com/peterkneale/nf-gadi-usage-reports/releases). It is one file. Copy it to Gadi, or to any other machine with Java.

Or build it from source:

```
git clone https://github.com/peterkneale/nf-gadi-usage-reports.git
cd nf-gadi-usage-reports
make cli
```

This builds `build/libs/gadi-usage-report.jar`.

#### Run the tool

Give the tool a Nextflow work directory, a folder of work directories or any folder that contains job logs:

```
java -jar gadi-usage-report.jar /scratch/ab12/my_pipeline/work
java -jar gadi-usage-report.jar /scratch/ab12/my_pipeline/runs
```

The tool writes the reports to the current directory. It replaces reports from an earlier run.

When it completes, the tool prints a summary:

```
Found 13 job logs in /scratch/ab12/my_pipeline/work
  13 complete
  0 incomplete (some usage values missing)
  0 unreadable
Wrote /home/user/UsageReport.csv
Wrote /home/user/UsageReport.json
```

The summary lists incomplete and unreadable logs by path, up to 10 of each. An incomplete log is still reported. Its missing values are empty.

Run the tool with `--help` to show the usage message.

The exit code is `0` on success. It is `1` if no job logs were found, or if a log could not be read. It is `2` if the arguments are wrong. You can use the exit code in scripts.

#### Task names

The tool reports each task with the name that Nextflow gave it, for example `MAIN:ALIGN (sample_1)`. It reads the name from the job script (`.command.run`) in the work directory. It reads the `process` and `queue` columns from the same file. The tool names and groups tasks in the same way as the plugin.

Nextflow 24.04 and later write the task name in a header at the top of the job script:

```
### ---
### name: 'MAIN:ALIGN (sample_1)'
```

If a work directory has a job log but no job script, the tool names the task after its work directory. It does the same if an older Nextflow wrote the job script without the header.

The `work_dir` column is relative to the folder that you gave the tool, for example `run1/94/348cfc33f75979f408e9e9d20d4d6e`. Use it to tell apart tasks with the same name from different runs. If you give the tool the work directory of a run, the `work_dir` values are the same as in the plugin report.

### Move from nf-gadi

`nf-gadi-usage-reports` is a fork of the [nf-gadi](https://github.com/eileen-xue/nf-gadi) plugin, version 1.4.0. It is a separate plugin. Use it in place of `nf-gadi`, not with it.

- Replace `nf-gadi` with `nf-gadi-usage-reports@1.0.0` in your `plugins` block or `-plugins` option.
- The plugin always writes both reports, as `UsageReport.csv` and `UsageReport.json`. The `format` and `output` settings are not used. Remove them.
- `publishTo` is now a folder, not a file. A value such as `"s3://bucket/results/UsageReport.csv"` makes a folder with the name `UsageReport.csv`. Change it to `"s3://bucket/results/"`.

## Development

### Build and test

```
make test       # run the unit tests, then check the built command line tool
make assemble   # build the plugin
make install    # install the plugin in your Nextflow plugins folder
make cli        # build the command line tool
```

### Code layout

The code has two entry points. They share the rest of the code. All code is under `src/main/groovy/io/github/peterkneale/gadi/`.

| Package  | Contents                                                                                       |
| -------- | ---------------------------------------------------------------------------------------------- |
| `plugin` | The Nextflow plugin: the entry point, the observer factory, the observer and the publisher     |
| `cli`    | The command line tool, and the reader of the task details that Nextflow writes to the job script |
| (root)   | Shared code: the job log parser, the usage record, the efficiency calculator and the report writers |

Only the `plugin` package uses Nextflow. The command line jar does not include it.

### Tests

The parser is tested against real Gadi job logs in `src/test/resources/io/github/peterkneale/gadi/scenarios/`. Each scenario has its own folder. The logs are anonymised. Batch names, sample names, job IDs, project codes, usernames and flowcell IDs are replaced with placeholders.

The command line tool is tested against the same scenarios. Its reports are compared with the golden copies in `src/test/resources/io/github/peterkneale/gadi/cli/golden/`. The unit tests run the tool in the test process. `make test` then runs the built jar with `java -jar` in the same way. It also checks that the jar does not include Nextflow classes.

GitHub Actions runs the same checks on each push and pull request. To make a release, push a tag such as `v1.0.0`. The tag must match the version in `build.gradle`. GitHub Actions then creates a release with the command line tool attached.

### Add a scenario

1. Make a folder that contains the anonymised `.command.log`.
2. Add the folder name to `SCENARIOS` in `GadiScenarioTest`.
3. Add a row to the table in the test. Take the values from the Resource Usage block in the log. Check them by hand.
4. Make the golden reports again. Make sure that the only change is the new row:

   ```
   make cli
   JAR=$PWD/build/libs/gadi-usage-report.jar
   (cd src/test/resources/io/github/peterkneale/gadi/cli/golden && java -jar "$JAR" ../../scenarios)
   git diff src/test/resources/io/github/peterkneale/gadi/cli/golden
   ```

You can keep raw logs that are not anonymised in `src/test/data/`. Git ignores this folder. Never commit these logs.

## Credits

`nf-gadi-usage-reports` is based on [nf-gadi](https://github.com/eileen-xue/nf-gadi), which was built from the [Nextflow plugin template](https://github.com/nextflow-io/nf-plugin-template).

Licensed under the [Apache License 2.0](COPYING).
