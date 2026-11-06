#!/usr/bin/env python3
"""Run the identity publishing command as a one-shot Cloud Run job.

The job uses the same image, environment, secret versions and service account as the deployed
server, taken from the rendered deployment contract, so it reads the same database and directory
the server does. It never starts the HTTP server.

  status    reads every identity and compares it with the PLC directory
  dry-run   lists what publishing would do, without sending anything

After the execution finishes, the job's SERVER_IDENTITY_* and SERVER_PLC_* log lines are printed.
See docs/runbook/identity-publishing.md.
"""

import argparse
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def _cleanup_job_module():
    spec = importlib.util.spec_from_file_location(
        "account_blob_cleanup_job", ROOT / "scripts/account-blob-cleanup-job.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def command_args(args):
    """The server arguments for the chosen mode."""
    if args.mode == "status":
        command = ["status"]
    elif args.mode == "dry-run":
        command = ["publish", "--dry-run"]
    else:
        raise ValueError("Cloud Run identity publication requires per-account consent")
    if args.reconcile:
        command.append("--reconcile")
    for flag, value in (
        ("--handle", args.handle),
        ("--limit", args.limit),
        ("--batch-size", args.batch_size),
        ("--operations-per-minute", args.operations_per_minute),
        ("--max-failures", args.max_failures),
    ):
        if value is not None:
            command.extend([flag, str(value)])
    return ["--identity-publishing", *command]


def render_job(contract, args):
    job = _cleanup_job_module().render_job(contract)
    job["metadata"]["name"] = contract["service_name"] + "-identity-publishing"
    task = job["spec"]["template"]["spec"]["template"]["spec"]
    task["timeoutSeconds"] = str(args.timeout_seconds)
    task["containers"][0]["args"] = command_args(args)
    return job


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "mode", choices=("status", "dry-run", "publish"), metavar="{status,dry-run}"
    )
    parser.add_argument("--contract-file", type=pathlib.Path, required=True)
    parser.add_argument("--confirm-directory", help=argparse.SUPPRESS)
    parser.add_argument(
        "--reconcile",
        action="store_true",
        help="include identities whose directory copy differs in the dry-run report",
    )
    parser.add_argument(
        "--handle",
        help="only the account with this handle, such as your own for a first check",
    )
    parser.add_argument("--limit", type=int)
    parser.add_argument("--batch-size", type=int)
    parser.add_argument("--operations-per-minute", type=int)
    parser.add_argument("--max-failures", type=int)
    parser.add_argument("--timeout-seconds", type=int, default=3600)
    args = parser.parse_args(argv)
    if args.mode == "publish":
        raise SystemExit(
            "Cloud Run identity publication requires per-account consent. Use status or dry-run."
        )
    if args.confirm_directory:
        parser.error("status and dry-run do not take --confirm-directory")
    if args.mode == "status" and (
        args.reconcile
        or args.batch_size
        or args.operations_per_minute
        or args.max_failures
    ):
        parser.error("status takes only --limit")
    if args.mode == "dry-run" and (args.operations_per_minute or args.max_failures):
        parser.error(
            "a dry run sends nothing, so pacing and failure limits don't apply"
        )
    return args


def latest_execution(contract, job_name):
    """A failed execution doesn't print its name, so look up the newest one."""
    listed = subprocess.run(
        [
            "gcloud",
            "run",
            "jobs",
            "executions",
            "list",
            "--job",
            job_name,
            "--region",
            contract["region"],
            "--project",
            contract["project_id"],
            "--limit",
            "1",
            "--sort-by",
            "~metadata.creationTimestamp",
            "--format",
            "value(metadata.name)",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    return listed.stdout.strip()


def print_job_logs(contract, execution):
    query = (
        'resource.type="cloud_run_job" '
        f'AND labels."run.googleapis.com/execution_name"="{execution}" '
        'AND (textPayload:"SERVER_IDENTITY" OR textPayload:"SERVER_PLC")'
    )
    subprocess.run(
        [
            "gcloud",
            "logging",
            "read",
            query,
            "--project",
            contract["project_id"],
            "--order",
            "asc",
            "--freshness",
            "1d",
            "--format",
            "value(textPayload)",
        ],
        check=False,
    )


def main(argv=None):
    args = parse_args(argv)
    contract = json.loads(args.contract_file.read_text())
    job = render_job(contract, args)
    # This is configuration and exact secret references; no secret contents are fetched.
    with tempfile.TemporaryDirectory(
        prefix="logdate-identity-publishing-job-"
    ) as directory:
        path = pathlib.Path(directory) / "job.json"
        path.write_text(json.dumps(job))
        path.chmod(0o600)
        subprocess.run(
            [
                "gcloud",
                "run",
                "jobs",
                "replace",
                str(path),
                "--region",
                contract["region"],
                "--project",
                contract["project_id"],
                "--quiet",
            ],
            check=True,
        )
    execution = subprocess.run(
        [
            "gcloud",
            "run",
            "jobs",
            "execute",
            job["metadata"]["name"],
            "--wait",
            "--region",
            contract["region"],
            "--project",
            contract["project_id"],
            "--quiet",
            "--format",
            "value(metadata.name)",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    name = execution.stdout.strip() or latest_execution(
        contract, job["metadata"]["name"]
    )
    if name:
        print_job_logs(contract, name)
    if execution.returncode != 0:
        sys.stderr.write(execution.stderr)
        print(
            "The job exited non-zero: 1 means a failure or an identity that needs an operator, "
            "2 means it refused to run, 3 means identities still need publishing.",
            file=sys.stderr,
        )
    return execution.returncode


if __name__ == "__main__":
    sys.exit(main())
