# nf-gadi-usage-reports

A [Nextflow](https://www.nextflow.io/) plugin that reports the resources each task used when a pipeline runs on [NCI Gadi](https://nci.org.au/our-systems/hpc-systems) with the PBS Pro executor.

For every task it records the service units charged, CPU time, walltime, memory and JobFS used against what was requested, and the CPU efficiency. The results are written to a CSV report and a JSON report in the launch directory, with no configuration needed.

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

| Column             | Description                        |
| ------------------ | ---------------------------------- |
| Name               | Task name, e.g. `ALIGN (sample_1)` |
| Process            | Process name, e.g. `ALIGN`         |
| Queue              | Gadi queue the job ran in          |
| Service Units      | Service units charged for the job  |
| CPUs               | CPUs requested                     |
| CPU time           | CPU time used, as `HH:MM:SS`       |
| Used Walltime      | Walltime used, as `HH:MM:SS`       |
| Requested Walltime | Walltime requested, as `HH:MM:SS`  |
| Used Memory        | Memory used, e.g. `10.89GB`        |
| Requested Memory   | Memory requested, e.g. `42.0GB`    |
| Used JobFS         | Local disk (JobFS) used            |
| Requested JobFS    | Local disk (JobFS) requested       |
| Efficiency         | CPU efficiency, see below          |
| Exit Code          | The job's exit status              |

Values containing commas, such as task names with several tags, are quoted, so the file opens correctly in spreadsheet tools.

### JSON report

The same values, grouped by process:

```json
{
    "COUNT": [
        {
            "sus": "0.40",
            "walltime": "00:01:09",
            "cputime": "00:02:22",
            "cpus": "6",
            "memory": "10.89GB",
            "efficiency": "0.34",
            "exitCode": "0",
            "requestedMemory": "42.0GB",
            "requestedWalltime": "05:00:00",
            "requestedJobFS": "100.0MB",
            "usedJobFS": "0B",
            "queue": "normal",
            "name": "COUNT (sample_1)"
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

- **Waiting for the log:** PBS writes the block shortly after the job ends, so the plugin waits up to 2 minutes for it to appear and be complete.
- **Missing logs:** if the log never appears, the task is still reported, with its usage values left empty, and a warning is logged.
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

## Switching from nf-gadi

- Replace `nf-gadi` with `nf-gadi-usage-reports@1.0.0` in your `plugins` block or `-plugins` option.
- Both reports are now always written, as `UsageReport.csv` and `UsageReport.json`. The `format` and `output` settings are no longer used and can be removed.
- `publishTo` is now a folder rather than a file. A setting such as `"s3://bucket/results/UsageReport.csv"` would now create a folder called `UsageReport.csv`, so change it to `"s3://bucket/results/"`.

## Development

Build and test the plugin with:

```
make test       # run the unit tests
make assemble   # build the plugin
make install    # install it into your local Nextflow plugins folder
```

The parser is tested against real Gadi job logs in `src/test/resources/nci/plugin/scenarios/`, one folder per scenario. The logs have been anonymised: batch and sample names, job IDs, project codes, usernames and flowcell IDs are replaced with placeholders. To add a scenario:

1. Create a folder holding the anonymised `.command.log`.
2. Add the folder name to `SCENARIOS` in `GadiScenarioTest`.
3. Add a row to the test's table with the values from the log's Resource Usage block, checked by hand.

Raw, unanonymised logs can be kept in `src/test/data/`, which is ignored by git. Never commit them.

## Credits

Based on [nf-gadi](https://github.com/eileen-xue/nf-gadi), built from the [Nextflow plugin template](https://github.com/nextflow-io/nf-plugin-template).

## License

[Apache License 2.0](COPYING)
