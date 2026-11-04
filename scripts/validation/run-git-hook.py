#!/usr/bin/env python3
"""Compose repository checks with the installed Hypertext Git-gate interface."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    tool, hook, *arguments = sys.argv[1:]
    repo = Path(__file__).resolve().parents[2]
    if Path.cwd().resolve() != repo:
        raise ValueError("Git hook must run from the checkout root")
    modes = {
        "pre-commit": (0, ["--git-precommit"]),
        "commit-msg": (1, ["--git-message", *arguments]),
        "pre-push": (
            2,
            [
                "--git-prepush",
                "--git-remote",
                *arguments[:1],
                "--git-remote-url",
                *arguments[1:],
            ],
        ),
    }
    if hook not in modes or len(arguments) != modes[hook][0]:
        raise ValueError("Invalid native Git hook arguments")
    metadata = json.loads((repo / ".hypertext/git-hooks.json").read_text())
    if metadata["schema_version"] != 1:
        raise ValueError("Unsupported Git hook composition schema")
    stages = metadata["hooks"][hook]
    commands = {}
    for when in ("before", "after"):
        commands[when] = []
        for stage in stages[when]:
            path = repo / stage["path"]
            if not path.resolve().is_relative_to(repo) or path.is_symlink():
                raise ValueError("Product Git hook must remain inside the checkout")
            if hashlib.sha256(path.read_bytes()).hexdigest() != stage["sha256"]:
                raise ValueError("Product Git hook digest mismatch: " + stage["path"])
            commands[when].append([str(path), *arguments])
    transaction = None
    if hook == "pre-push":
        transaction = sys.stdin.buffer.read(1024 * 1024 + 1)
        if len(transaction) > 1024 * 1024:
            raise ValueError("Push transaction exceeds 1 MiB")
    shared = [sys.executable, tool, "verify", "--repo", str(repo), *modes[hook][1]]
    for command in [*commands["before"], shared, *commands["after"]]:
        subprocess.run(command, cwd=repo, input=transaction, check=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(
            error.returncode if error.returncode > 0 else 128 - error.returncode
        )
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("Git hook blocked: " + str(error), file=sys.stderr)
        raise SystemExit(1)
