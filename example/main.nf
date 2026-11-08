#!/usr/bin/env nextflow

/*
 * A small pipeline that shows nf-gadi-usage-reports at work on Gadi.
 * See README.md in this folder for how to run it and what to expect.
 *
 * Each process shows one kind of row in the usage report:
 *   BUSY               all requested CPUs busy, so the efficiency is close to 1.00
 *   IDLE               requested CPUs left idle, so the efficiency is close to 0.00
 *   FAIL               a job that fails at once, so the exit code is 1
 *   FAIL_AFTER_DELAY   a job that works and then fails, so the exit code is 1 and the usage is real
 *   OOM_AFTER_DELAY    a job that runs, then uses more memory than it requested, so PBS kills it
 *   LOCAL              a task that does not run on Gadi, so only its name is reported
 */

// How long each BUSY, IDLE, FAIL_AFTER_DELAY and OOM_AFTER_DELAY task runs, in seconds
params.seconds = 60

if (workflow.profile != 'local' && !params.project) {
    error "No Gadi project code. Set \$PROJECT, or run: nextflow run main.nf --project ab12"
}

process BUSY {
    tag "${sample}"
    label 'process_medium'

    input:
    val sample

    output:
    stdout

    script:
    """
    # Keep every requested CPU busy
    for i in \$(seq 1 ${task.cpus}); do
        timeout ${params.seconds} sh -c 'while :; do :; done' &
    done
    wait
    echo "${sample}: busy for ${params.seconds} seconds on ${task.cpus} CPUs"
    """
}

process IDLE {
    tag "${sample}, lane${lane}"   // the comma makes a task name that must be quoted in the CSV
    label 'process_low'

    input:
    tuple val(sample), val(lane)

    output:
    stdout

    script:
    """
    # Use none of the requested CPUs
    sleep ${params.seconds}
    echo "${sample} lane${lane}: idle for ${params.seconds} seconds"
    """
}

process FAIL {
    label 'process_single'
    errorStrategy 'ignore'   // the pipeline continues

    output:
    stdout

    script:
    """
    echo "this task fails on purpose"
    exit 1
    """
}

process FAIL_AFTER_DELAY {
    label 'process_low'
    errorStrategy 'ignore'   // the pipeline continues

    output:
    stdout

    script:
    """
    # Keep every requested CPU busy, then fail
    for i in \$(seq 1 ${task.cpus}); do
        timeout ${params.seconds} sh -c 'while :; do :; done' &
    done
    wait
    echo "this task fails on purpose after ${params.seconds} seconds"
    exit 1
    """
}

process OOM_AFTER_DELAY {
    label 'process_single'
    errorStrategy 'ignore'   // the pipeline continues

    output:
    stdout

    script:
    """
    # Run quietly, then take twice the requested memory, so PBS kills the job
    sleep ${params.seconds}
    perl -e '\$x = "x" x (${task.memory.toMega() * 2} * 1024 * 1024); sleep ${params.seconds}'
    echo "this task should have been killed for using too much memory"
    """
}

process LOCAL {
    executor 'local'   // runs where Nextflow runs, not as a Gadi job

    output:
    stdout

    script:
    """
    echo "this task did not run on Gadi"
    """
}

workflow {
    samples = channel.of('sample_1', 'sample_2')
    lanes = channel.of(1, 2)

    BUSY(samples)
    IDLE(samples.combine(lanes))
    FAIL()
    FAIL_AFTER_DELAY()
    OOM_AFTER_DELAY()
    LOCAL()
}
