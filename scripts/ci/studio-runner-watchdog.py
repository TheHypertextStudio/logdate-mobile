#!/usr/bin/env python3
"""Stop CI jobs from waiting for hours on the Studio self-hosted runner pools.

GitHub keeps a self-hosted job queued for up to 24 hours when no runner picks it
up. This watchdog runs from a public repository, whose GitHub-hosted runners
always start, and for every watched repository:

* cancels any run that has a job waiting on a Studio pool for longer than the
  allowed queue time, so the check fails within minutes instead of hours;
* reports each Studio pool that has no online runner;
* opens one issue while anything is wrong and closes it once all is well.

It needs STUDIO_WATCHDOG_TOKEN: a token that can read the organization's
self-hosted runners, cancel runs in the watched repositories and write issues in
this one. GITHUB_TOKEN can see none of the other repositories.
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
POOLS = ("studio-macos", "studio-linux")
ISSUE_TITLE = "Studio runner pool needs attention"


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


def stalled(job: dict, now: dt.datetime, limit: dt.timedelta) -> bool:
    """Is this job waiting on a Studio pool for longer than the limit?"""
    if job.get("status") not in ("queued", "waiting", "pending"):
        return False
    if not pool_of(job.get("labels", [])):
        return False
    return now - parse_time(job["created_at"]) > limit


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

    def paged(self, path: str, key: str) -> list[dict]:
        items, page = [], 1
        while True:
            sep = "&" if "?" in path else "?"
            chunk = self.request("GET", f"{path}{sep}per_page=100&page={page}")[key]
            items += chunk
            if len(chunk) < 100:
                return items
            page += 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--org", default="TheHypertextStudio")
    parser.add_argument("--repos", required=True, help="space-separated repository names")
    parser.add_argument("--issue-repo", required=True)
    parser.add_argument("--max-queue-minutes", type=int, default=10)
    args = parser.parse_args()

    token = os.environ.get("STUDIO_WATCHDOG_TOKEN")
    if not token:
        print("::error::STUDIO_WATCHDOG_TOKEN is not set, so stalled Studio jobs can't be found")
        return 1
    gh = GitHub(token)
    now = dt.datetime.now(dt.timezone.utc)
    limit = dt.timedelta(minutes=args.max_queue_minutes)
    problems: list[str] = []

    try:
        runners = gh.paged(f"/orgs/{args.org}/actions/runners", "runners")
    except urllib.error.HTTPError as error:
        print(f"::error::Couldn't list the organization's runners ({error.code})")
        return 1
    online = online_pools(runners)
    for pool in POOLS:
        if pool not in online:
            problems.append(f"No `{pool}` runner is online.")

    for repo in args.repos.split():
        for status in ("queued", "waiting", "pending"):
            runs = gh.paged(f"/repos/{args.org}/{repo}/actions/runs?status={status}", "workflow_runs")
            for run in runs:
                jobs = gh.paged(f"/repos/{args.org}/{repo}/actions/runs/{run['id']}/jobs", "jobs")
                waiting = [job for job in jobs if stalled(job, now, limit)]
                if not waiting:
                    continue
                pool = pool_of(waiting[0]["labels"])
                gh.request("POST", f"/repos/{args.org}/{repo}/actions/runs/{run['id']}/cancel")
                problems.append(
                    f"Cancelled [{repo} {run['name']}]({run['html_url']}): it waited more than "
                    f"{args.max_queue_minutes} minutes for `{pool}`."
                )
                print(f"::error::{problems[-1]}")

    issues = gh.request(
        "GET", f"/repos/{args.org}/{args.issue_repo}/issues?state=open&labels=studio-runner-watchdog"
    )
    issue = next((i for i in issues if i["title"] == ISSUE_TITLE), None)
    if problems:
        body = "\n".join(f"- {p}" for p in problems)
        body += f"\n\nChecked {now:%Y-%m-%d %H:%M} UTC. Inspect the pool with the Studio `self-hosted-runner` skill's `doctor`."
        if issue:
            gh.request("POST", f"/repos/{args.org}/{args.issue_repo}/issues/{issue['number']}/comments", {"body": body})
        else:
            gh.request(
                "POST",
                f"/repos/{args.org}/{args.issue_repo}/issues",
                {"title": ISSUE_TITLE, "body": body, "labels": ["studio-runner-watchdog"]},
            )
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    if issue:
        gh.request(
            "PATCH",
            f"/repos/{args.org}/{args.issue_repo}/issues/{issue['number']}",
            {"state": "closed", "state_reason": "completed"},
        )
    print("Every Studio pool has an online runner and no job is stalled.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
