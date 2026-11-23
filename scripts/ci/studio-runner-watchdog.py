#!/usr/bin/env python3
"""Stop CI jobs from waiting for hours on the Studio self-hosted runner pools.

GitHub keeps a self-hosted job queued for up to 24 hours when no runner picks it
up. This watchdog runs from a public repository, whose GitHub-hosted runners
always start, and for every watched repository:

* cancels any run that has a job waiting on a Studio pool for longer than the
  allowed queue time, so the check fails within minutes instead of hours;
* reruns that run when its repository is public. The route job sends a rerun to
  GitHub-hosted runners, so the work still finishes;
* reports each Studio pool that has no online runner;
* keeps one issue open while anything is wrong and closes it once all is well.

With STUDIO_WATCHDOG_TOKEN (organization self-hosted runners: read, Actions:
read and write on every watched repository) it watches every repository and
reads the runners' status. Without it, GITHUB_TOKEN only reaches this
repository, so it watches this one and judges each pool by this repository's
Studio heartbeat. Issues are always written with GITHUB_TOKEN, so the issue
notifies the repository's watchers, including the token's owner.
"""

from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
POOLS = ("studio-macos", "studio-linux")
ISSUE_TITLE = "Studio runner pool needs attention"
HEARTBEAT_PATH = ".github/workflows/studio-heartbeat.yml"
WAITING = ("queued", "waiting", "pending")

_route_spec = importlib.util.spec_from_file_location(
    "studio_route", pathlib.Path(__file__).resolve().parent / "studio-route.py"
)
studio_route = importlib.util.module_from_spec(_route_spec)
_route_spec.loader.exec_module(studio_route)


def parse_time(value: str) -> dt.datetime:
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def pool_of(labels: list[str]) -> str | None:
    """Returns the Studio pool a job asks for, or None for a GitHub-hosted job."""
    for pool in POOLS:
        if pool in labels:
            return pool
    return None


def online_pools(runners: list[dict]) -> set[str]:
    """Returns the Studio pools that have at least one online runner."""
    online = set()
    for runner in runners:
        if runner.get("status") != "online":
            continue
        labels = [label["name"] for label in runner.get("labels", [])]
        pool = pool_of(labels)
        if pool:
            online.add(pool)
    return online


def beating_pools(beats: dict[str, dt.datetime], now: dt.datetime, max_age: dt.timedelta) -> set[str]:
    """Returns the Studio pools whose heartbeat finished within max_age."""
    return {pool for pool, finished in beats.items() if pool in POOLS and now - finished <= max_age}


def stalled(job: dict, now: dt.datetime, limit: dt.timedelta) -> bool:
    """Is this job waiting on a Studio pool for longer than the limit?"""
    if job.get("status") not in WAITING:
        return False
    if not pool_of(job.get("labels", [])):
        return False
    return now - parse_time(job["created_at"]) > limit


def is_heartbeat(run: dict) -> bool:
    return run.get("path", "").split("@")[0] == HEARTBEAT_PATH


def should_rerun(run: dict) -> bool:
    """A cancelled run is rerun once, in a public repository, unless it is a heartbeat."""
    public = not (run.get("repository") or {}).get("private", True)
    return public and run.get("run_attempt", 1) == 1 and not is_heartbeat(run)


def issue_body(offline: list[str], basis: str, now: dt.datetime) -> str:
    lines = [f"- No `{pool}` runner is online ({basis})." for pool in offline]
    lines.append("")
    lines.append(
        f"Checked {now:%Y-%m-%d %H:%M} UTC. While a pool is offline, public repositories run on "
        "GitHub-hosted runners and private repositories wait. Inspect the pool with the Studio "
        "`self-hosted-runner` skill's `doctor`."
    )
    return "\n".join(lines)


class GitHub:
    def __init__(self, token: str) -> None:
        self.token = token

    def request(self, method: str, path: str, body: dict | None = None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            f"{API}{path}",
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
        return json.loads(raw) if raw else None

    def paged(self, path: str, key: str | None) -> list[dict]:
        items, page = [], 1
        while True:
            sep = "&" if "?" in path else "?"
            response = self.request("GET", f"{path}{sep}per_page=100&page={page}")
            chunk = response[key] if key else response
            items += chunk
            if len(chunk) < 100:
                return items
            page += 1


def cancel_and_rerun(gh: GitHub, org: str, repo: str, run: dict) -> bool:
    """Cancels the run, then reruns it if it should be. Returns True if it was rerun."""
    gh.request("POST", f"/repos/{org}/{repo}/actions/runs/{run['id']}/cancel")
    if not should_rerun(run):
        return False
    for _ in range(18):
        time.sleep(5)
        if gh.request("GET", f"/repos/{org}/{repo}/actions/runs/{run['id']}")["status"] == "completed":
            gh.request("POST", f"/repos/{org}/{repo}/actions/runs/{run['id']}/rerun")
            return True
    print(f"::warning::{repo} run {run['id']} didn't finish cancelling, so it wasn't rerun")
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--org", default="TheHypertextStudio")
    parser.add_argument("--repos", required=True, help="space-separated repository names")
    parser.add_argument("--issue-repo", required=True)
    parser.add_argument("--max-queue-minutes", type=int, default=10)
    parser.add_argument("--heartbeat-max-age-minutes", type=int, default=30)
    args = parser.parse_args()

    github_token = os.environ.get("GITHUB_TOKEN")
    if not github_token:
        print("::error::GITHUB_TOKEN is not set")
        return 1
    studio_token = os.environ.get("STUDIO_WATCHDOG_TOKEN")
    issues = GitHub(github_token)
    reader = GitHub(studio_token or github_token)
    repos = args.repos.split() if studio_token else [args.issue_repo]
    if not studio_token:
        print(
            "::warning::STUDIO_WATCHDOG_TOKEN is not set, so only this repository is watched and "
            "pools are judged by its Studio heartbeat"
        )

    now = dt.datetime.now(dt.timezone.utc)
    limit = dt.timedelta(minutes=args.max_queue_minutes)

    online: set[str] | None = None
    basis = "organization runner list"
    if studio_token:
        try:
            online = online_pools(reader.paged(f"/orgs/{args.org}/actions/runners", "runners"))
        except urllib.error.HTTPError as error:
            print(f"::warning::Couldn't list the organization's runners ({error.code}); using the heartbeat")
    if online is None:
        basis = f"no heartbeat in {args.heartbeat_max_age_minutes} minutes"
        beats = studio_route.read_beats(f"{args.org}/{args.issue_repo}", github_token, 6) or {}
        online = beating_pools(beats, now, dt.timedelta(minutes=args.heartbeat_max_age_minutes))
    offline = [pool for pool in POOLS if pool not in online]

    events: list[str] = []
    for repo in repos:
        seen: set[int] = set()
        for status in WAITING:
            runs = reader.paged(f"/repos/{args.org}/{repo}/actions/runs?status={status}", "workflow_runs")
            for run in runs:
                if run["id"] in seen:
                    continue
                seen.add(run["id"])
                jobs = reader.paged(f"/repos/{args.org}/{repo}/actions/runs/{run['id']}/jobs", "jobs")
                waiting = [job for job in jobs if stalled(job, now, limit)]
                if not waiting:
                    continue
                pool = pool_of(waiting[0]["labels"])
                rerun = cancel_and_rerun(reader, args.org, repo, run)
                if is_heartbeat(run):
                    continue
                event = (
                    f"Cancelled [{repo} {run['name']}]({run['html_url']}): it waited more than "
                    f"{args.max_queue_minutes} minutes for `{pool}`."
                )
                event += " Rerunning it on GitHub-hosted runners." if rerun else ""
                events.append(event)
                print(f"::warning::{event}")

    open_issues = issues.paged(f"/repos/{args.org}/{args.issue_repo}/issues?state=open", None)
    issue = next((i for i in open_issues if i["title"] == ISSUE_TITLE and "pull_request" not in i), None)
    base = f"/repos/{args.org}/{args.issue_repo}/issues"

    if offline or events:
        body = issue_body(offline, basis, now)
        if not issue:
            issue = issues.request("POST", base, {"title": ISSUE_TITLE, "body": body + "\n\n" + "\n".join(events)})
        else:
            # Editing the body keeps the current state without notifying anyone each tick.
            issues.request("PATCH", f"{base}/{issue['number']}", {"body": body})
            if events:
                issues.request("POST", f"{base}/{issue['number']}/comments", {"body": "\n".join(f"- {e}" for e in events)})
        for pool in offline:
            print(f"::warning::No {pool} runner is online ({basis}).")
        return 0

    if issue:
        issues.request("POST", f"{base}/{issue['number']}/comments", {"body": "Every Studio pool is online again."})
        issues.request("PATCH", f"{base}/{issue['number']}", {"state": "closed", "state_reason": "completed"})
    print("Every Studio pool has an online runner and no job is stalled.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
