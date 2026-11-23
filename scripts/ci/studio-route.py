#!/usr/bin/env python3
"""Choose, for one workflow run, whether its jobs use a Studio pool or GitHub-hosted runners.

The Studio CI standard (logdate-web docs/ci.md) runs every job on the Studio pools and never
lets one wait for hours. A job can only fall back to a hosted runner before it is queued, so
this runs first and prints one choice per pool, `studio` or `hosted`:

* A fork's pull request always gets `hosted`. Forks never reach the pools.
* A private repository always gets `studio`. Hosted runners don't start there while the
  organization has no Actions billing, so there is nothing to fall back to.
* A rerun (attempt 2 or later) gets `hosted`. The Studio runner watchdog cancels a run whose
  Studio job waited too long and reruns it, and the rerun must not wait again.
* Otherwise a pool gets `studio` when this repository's Studio heartbeat finished on it within
  the freshness window, and `hosted` when it didn't or the check fails.

It needs only the job's GITHUB_TOKEN with `actions: read`.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
POOLS = ("studio-linux", "studio-macos")
OUTPUTS = {"studio-linux": "linux", "studio-macos": "macos"}
HEARTBEAT_WORKFLOW = "studio-heartbeat.yml"


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def is_fork_pull_request(event_name: str, event: dict) -> bool:
    if not event_name.startswith("pull_request"):
        return False
    pull = event.get("pull_request") or {}
    head = ((pull.get("head") or {}).get("repo") or {}).get("full_name")
    base = ((pull.get("base") or {}).get("repo") or {}).get("full_name")
    return head is None or head != base


def latest_beats(runs_with_jobs: list[tuple[dict, list[dict]]]) -> dict[str, dt.datetime]:
    """Returns, for each pool, when its newest successful heartbeat job finished."""
    beats: dict[str, dt.datetime] = {}
    for _run, jobs in runs_with_jobs:
        for job in jobs:
            pool = job.get("name")
            if pool not in POOLS or job.get("conclusion") != "success" or not job.get("completed_at"):
                continue
            finished = parse_time(job["completed_at"])
            if pool not in beats or finished > beats[pool]:
                beats[pool] = finished
    return beats


def decide(
    *,
    fork: bool,
    private: bool,
    attempt: int,
    beats: dict[str, dt.datetime] | None,
    now: dt.datetime,
    max_age: dt.timedelta,
) -> dict[str, tuple[str, str]]:
    """Returns {pool: (choice, reason)}."""
    choices = {}
    for pool in POOLS:
        if fork:
            choices[pool] = ("hosted", "a fork's pull request never runs on the Studio pools")
        elif private:
            choices[pool] = ("studio", "private repositories have no hosted fallback")
        elif attempt > 1:
            choices[pool] = ("hosted", f"attempt {attempt} is a rerun, so it doesn't wait for {pool} again")
        elif beats is None:
            choices[pool] = ("hosted", "the Studio heartbeat couldn't be read")
        elif pool not in beats:
            choices[pool] = ("hosted", f"no recent heartbeat has finished on {pool}")
        elif now - beats[pool] > max_age:
            minutes = int((now - beats[pool]).total_seconds() // 60)
            choices[pool] = ("hosted", f"the last {pool} heartbeat finished {minutes} minutes ago")
        else:
            choices[pool] = ("studio", f"{pool} answered a heartbeat at {beats[pool]:%H:%M} UTC")
    return choices


def fetch(path: str, token: str):
    request = urllib.request.Request(
        f"{API}{path}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def read_beats(repository: str, token: str, runs: int) -> dict[str, dt.datetime] | None:
    try:
        listing = fetch(
            f"/repos/{repository}/actions/workflows/{HEARTBEAT_WORKFLOW}/runs?per_page={runs}", token
        )
        pairs = []
        for run in listing.get("workflow_runs", []):
            jobs = fetch(f"/repos/{repository}/actions/runs/{run['id']}/jobs?per_page=20", token)
            pairs.append((run, jobs.get("jobs", [])))
        return latest_beats(pairs)
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
        print(f"::warning::Couldn't read the Studio heartbeat: {error}")
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-age-minutes", type=int, default=30)
    parser.add_argument("--heartbeat-runs", type=int, default=6)
    args = parser.parse_args()

    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event = {}
    if os.environ.get("GITHUB_EVENT_PATH"):
        with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as handle:
            event = json.load(handle)
    repository = os.environ["GITHUB_REPOSITORY"]
    token = os.environ.get("GITHUB_TOKEN", "")
    if "repository" in event:
        private = bool(event["repository"].get("private"))
    else:
        # Some events, such as schedule, may carry no repository; ask instead, and treat failure as private.
        try:
            private = bool(fetch(f"/repos/{repository}", token).get("private"))
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            print(f"::warning::Couldn't read the repository's visibility: {error}")
            private = True
    fork = is_fork_pull_request(event_name, event)
    attempt = int(os.environ.get("GITHUB_RUN_ATTEMPT", "1"))

    beats = None
    if not fork and not private and attempt == 1:
        beats = read_beats(repository, token, args.heartbeat_runs)

    now = dt.datetime.now(dt.timezone.utc)
    choices = decide(
        fork=fork,
        private=private,
        attempt=attempt,
        beats=beats,
        now=now,
        max_age=dt.timedelta(minutes=args.max_age_minutes),
    )

    output = os.environ.get("GITHUB_OUTPUT")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    lines = []
    for pool, (choice, reason) in choices.items():
        lines.append(f"- `{pool}` jobs use **{choice}** runners: {reason}.")
        print(f"{pool}: {choice} ({reason})")
        if output:
            with open(output, "a", encoding="utf-8") as handle:
                handle.write(f"{OUTPUTS[pool]}={choice}\n")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("### Runner choice\n\n" + "\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
