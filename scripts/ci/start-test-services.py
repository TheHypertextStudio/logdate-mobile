#!/usr/bin/env python3
"""Start isolated CI databases with bounded, digest-preserving registry failover."""

import argparse
import os
import re
import subprocess
import sys
import time

IMAGES = {
    "unit": (
        "postgres:16",
        "ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d",
    ),
    "e2e": (
        "postgres:16-alpine",
        "721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea",
    ),
    "redis": (
        "redis:7",
        "4fa24486b8bcca8eec45ee0eb166edc674795e53a2b53d1a9ef263eecebaac85",
    ),
}
LABEL = "studio.hypertext.logdate.ci"


def run(*args, check=True, capture=False, timeout=30):
    return subprocess.run(
        ["docker", *args],
        check=check,
        capture_output=capture,
        text=True,
        timeout=timeout,
    )


def identity():
    values = [
        os.environ.get(key, "")
        for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB")
    ]
    if (
        os.environ.get("GITHUB_ACTIONS") != "true"
        or not all(re.fullmatch(r"[A-Za-z0-9_-]+", value) for value in values)
        or not all(value.isdigit() for value in values[:2])
    ):
        raise RuntimeError("Test services require a GitHub Actions run identity")
    return ":".join(values), "-".join(values)


def containers(mode, suffix):
    services = [("postgres", f"logdate-ci-postgres-{suffix}")]
    if mode == "unit":
        services.append(("redis", f"logdate-ci-redis-{suffix}"))
    return services


def pull(image, digest):
    for registry in ("public.ecr.aws/docker/library", "docker.io/library"):
        reference = f"{registry}/{image}@sha256:{digest}"
        try:
            if run("pull", reference, check=False, timeout=90).returncode == 0:
                return reference
        except subprocess.TimeoutExpired:
            pass
        print(
            f"Registry unavailable for {image}; trying the next official source",
            flush=True,
        )
    raise RuntimeError(f"Neither official registry supplied the pinned {image} image")


def stop(mode, owner, suffix):
    for _, name in containers(mode, suffix):
        result = run(
            "inspect",
            "--format",
            '{{index .Config.Labels "' + LABEL + '"}}',
            name,
            check=False,
            capture=True,
        )
        if result.returncode != 0:
            continue
        if result.stdout.strip() != owner:
            raise RuntimeError(f"Refusing to remove {name}: it belongs to another run")
        run("rm", "--force", "--volumes", name)


def wait_healthy(name):
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        status = run(
            "inspect", "--format", "{{.State.Health.Status}}", name, capture=True
        ).stdout.strip()
        if status == "healthy":
            return
        if status == "unhealthy":
            break
        time.sleep(2)
    run("logs", "--tail", "40", name, check=False)
    raise RuntimeError(f"Test service {name} did not become healthy")


def start(mode, owner, suffix):
    try:
        for service, name in containers(mode, suffix):
            image_mode = "e2e" if mode == "publication" else mode
            image = pull(*IMAGES[image_mode if service == "postgres" else "redis"])
            args = ["run", "--detach", "--name", name, "--label", f"{LABEL}={owner}"]
            if service == "postgres":
                database, user, password = (
                    ("logdate_test", "test", "test")
                    if mode == "unit"
                    else ("logdate",) * 3
                )
                bind = "5432:5432"
                if mode == "publication":
                    database = "logdate_plc_rehearsal_ci"
                    bind = "127.0.0.1:5432:5432"
                args += [
                    "--publish",
                    bind,
                    "--env",
                    f"POSTGRES_DB={database}",
                    "--env",
                    f"POSTGRES_USER={user}",
                    "--env",
                    f"POSTGRES_PASSWORD={password}",
                    "--health-cmd",
                    "pg_isready",
                ]
            else:
                args += ["--publish", "6379:6379", "--health-cmd", "redis-cli ping"]
            args += [
                "--health-interval",
                "10s",
                "--health-timeout",
                "5s",
                "--health-retries",
                "5",
                image,
            ]
            run(*args)
            wait_healthy(name)
    except Exception:
        stop(mode, owner, suffix)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop"))
    parser.add_argument("mode", choices=("unit", "e2e", "publication"))
    args = parser.parse_args()
    try:
        owner, suffix = identity()
        (start if args.action == "start" else stop)(args.mode, owner, suffix)
    except (RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
