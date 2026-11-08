# Test the plugin on Gadi

This folder holds a small pipeline that shows the plugin at work. It runs four kinds of task. Each kind gives a different row in the usage report.

| Process | Tasks | What it does                           | What to expect in the report                  |
| ------- | ----- | -------------------------------------- | --------------------------------------------- |
| `BUSY`  | 2     | Keeps all 4 requested CPUs busy        | `cpus` 4, `efficiency` close to 1.00          |
| `IDLE`  | 4     | Sleeps on 2 requested CPUs             | `cpus` 2, `efficiency` close to 0.00          |
| `FAIL`  | 1     | Exits with status 1                    | `exit_code` 1, `efficiency` empty             |
| `LOCAL` | 1     | Runs where Nextflow runs, not on Gadi  | Name and queue only, all usage values empty   |

The `IDLE` task names contain a comma, for example `IDLE (sample_1, lane1)`. The CSV report quotes them.

## Run it

1. Install the plugin. See the [main README](../README.md#install-the-plugin).
2. Log in to Gadi and go to this folder. Your home folder is fine.
3. Run the pipeline:

   ```
   module load nextflow
   nextflow run main.nf
   ```

The jobs run under your default Gadi project, from `$PROJECT`. Add `--project ab12` to use another project.

The config follows the [nf-core profile for Gadi](https://nf-co.re/configs/nci_gadi/). The Nextflow module on Gadi is modified to accept the `project` and `storage` process directives. The config does not use `clusterOptions`, because Nextflow ignores the `cpus` and `memory` directives when `clusterOptions` contains `-l`.

The pipeline submits 7 jobs to the `normal` queue. Each job runs for about 1 minute. The whole run takes a few minutes, depending on the queue.

To make the jobs shorter or longer, set `--seconds`. The default is 60.

## Check the reports

When the pipeline completes, this folder contains `UsageReport.csv` and `UsageReport.json`.

The CSV report has a header row and 8 task rows. The task rows look like this, with your own values:

```
work_dir,name,process,queue,service_units,cpus,cpu_time,used_walltime,requested_walltime,used_memory,requested_memory,used_jobfs,requested_jobfs,efficiency,exit_code
a1/2b3c4d...,BUSY (sample_1),BUSY,normal,0.14,4,00:04:01,00:01:02,00:10:00,12.5MB,4.0GB,0B,100.0MB,0.97,0
b2/3c4d5e...,"IDLE (sample_1, lane1)",IDLE,normal,0.07,2,00:00:01,00:01:02,00:10:00,8.2MB,2.0GB,0B,100.0MB,0.01,0
c3/4d5e6f...,FAIL,FAIL,normal,0.00,1,00:00:00,00:00:00,00:05:00,3.1MB,1.0GB,0B,100.0MB,,1
d4/5e6f7a...,LOCAL,LOCAL,normal,,,,,,,,,,,
```

Make sure that:

- `BUSY` rows have `efficiency` close to 1.00.
- `IDLE` rows have `efficiency` close to 0.00.
- The `FAIL` row has `exit_code` 1. Its `efficiency` is empty, because the job ran for less than a second.
- The `LOCAL` row has a name and a queue, and no usage values.

## Try a resumed run

Run the pipeline again with `-resume`:

```
nextflow run main.nf -resume
```

Nextflow does not run the completed tasks again. The plugin reads their usage from the job logs in their old work directories. The new reports have the same rows as before. The `FAIL` task runs again, because a failed task is not cached.

## Check the pipeline without Gadi

The `local` profile runs every task on the current machine:

```
nextflow run main.nf -profile local --seconds 2
```

The tasks are then not Gadi jobs. The report has a row for each task, with its name and queue and no usage values.

A Nextflow that is not the Gadi module does not know the `project` directive. It prints one warning, `Unrecognized config option 'process.project'`, and ignores the directive.

## Make the reports again from the job logs

The command line tool makes the same reports from the `work` folder, without Nextflow:

```
java -jar gadi-usage-report.jar work
```

The `work_dir` values are the same as in the plugin report. See the [main README](../README.md#make-reports-from-existing-job-logs).
