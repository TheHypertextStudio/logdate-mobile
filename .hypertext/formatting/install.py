#!/usr/bin/env python3
"""Install pinned tools and compose repository hooks without replacing checks."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys

repo = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--skip-tools", action="store_true")
args = parser.parse_args()


def git(*argv, optional=False):
    result = subprocess.run(
        ["git", "-C", str(repo), *argv],
        capture_output=True,
        text=True,
        check=not optional,
    )
    return result.stdout.strip()


def absolute(value):
    path = Path(value)
    return path if path.is_absolute() else repo / path


def prepared_path_header():
    """Retain only validated executable directories for noninteractive Git hooks."""
    executables = [sys.executable]
    for name in (
        "node",
        "python3",
        "java",
        "rustup",
        "dotnet",
        "ruby",
        "pnpm",
        "cargo",
        "swift",
    ):
        found = shutil.which(name)
        if found:
            executables.append(found)
    directories = []
    for executable in executables:
        path = Path(executable).absolute()
        if not path.is_file() or not os.access(path, os.X_OK):
            raise ValueError("Prepared tool executable is unavailable: " + path.name)
        # Keep the discovered directory: a symlink target can have a different
        # basename (pnpm -> pnpm.cjs), so its resolved parent may not expose pnpm.
        directory = str(path.parent)
        if directory not in directories:
            directories.append(directory)
    prefix = os.pathsep.join(directories)
    value = shlex.quote(prefix)
    if os.name == "nt":
        # Git for Windows runs these hooks in its POSIX shell.
        value = "$(cygpath -p -u " + value + ")"
    return "#!/bin/sh\nPATH=" + value + ':"${PATH:-/usr/bin:/bin}"\nexport PATH\n'


def checkouts():
    """Every worktree root of this repository, primary checkout first."""
    return [
        Path(line[len("worktree ") :]).resolve()
        for line in git("worktree", "list", "--porcelain").splitlines()
        if line.startswith("worktree ")
    ]


def scoped(scope):
    for line in git("config", "--list", "--show-scope").splitlines():
        found, _, entry = line.partition("\t")
        key, _, value = entry.partition("=")
        if found == scope and key == "core.hookspath":
            return value
    return None


try:
    gitdir = absolute(git("rev-parse", "--git-dir")).resolve()
    common = absolute(git("rev-parse", "--git-common-dir")).resolve()
    roots = checkouts()
    # One composed directory serves every worktree. Its wrappers name hooks inside
    # the checkout relative to the worktree Git runs them in, so each worktree runs
    # its own hooks and formatter even when another tool copies the absolute path.
    target = common / "hypertext-formatting-hooks"
    # Earlier installations composed hooks in each worktree's own Git directory.
    linked = common / "worktrees"
    legacy_targets = {gitdir / "hypertext-formatting-hooks"} | (
        {path / "hypertext-formatting-hooks" for path in linked.iterdir()}
        if linked.is_dir()
        else set()
    )
    current = git("config", "--get", "core.hooksPath", optional=True)
    effective = (
        absolute(current)
        if current
        else absolute(git("rev-parse", "--git-path", "hooks"))
    )

    def relative_source(path):
        """Name a hook inside any checkout of this repository relative to it."""
        candidate = Path(path).resolve()
        # A worktree nested in another checkout belongs to the deepest root.
        for root in sorted(roots, key=lambda root: len(root.parts), reverse=True):
            if candidate.is_relative_to(root) and not candidate.is_relative_to(common):
                return candidate.relative_to(root).as_posix()
        return str(candidate)

    def same_repository_hooks(value):
        if not value:
            return False
        path = absolute(value).resolve()
        return (
            path == target
            or path in legacy_targets
            or any(path == root / ".githooks" for root in roots)
        )

    metadata = common / "hypertext-formatting-hooks.json"
    if metadata.is_symlink() or target.is_symlink():
        raise ValueError("Managed hook metadata and directory must not be symlinked")
    receipt = json.loads(metadata.read_text()) if metadata.is_file() else {}
    if not isinstance(receipt, dict):
        raise ValueError("Invalid composed-hook ownership receipt")
    if receipt.get("schema_version") == 1:
        if set(receipt) != {"schema_version", "sources", "wrappers"}:
            raise ValueError("Invalid composed-hook ownership receipt")
        previous = receipt["sources"]
        previous_wrappers = receipt["wrappers"]
    else:
        # Existing installations recorded only their native source paths.
        previous, previous_wrappers = receipt, {}
    for values in (previous, previous_wrappers):
        if not isinstance(values, dict) or any(
            not isinstance(name, str)
            or name in {"", ".", ".."}
            or Path(name).name != name
            or not isinstance(value, str)
            for name, value in values.items()
        ):
            raise ValueError("Invalid composed-hook ownership receipt")
    if any(
        len(value) != 64 or any(c not in "0123456789abcdef" for c in value)
        for value in previous_wrappers.values()
    ):
        raise ValueError("Invalid composed-hook digest")
    sources = {}
    for directory in (repo / ".githooks", effective):
        if directory.resolve() == target or directory.resolve() in legacy_targets:
            sources.update(
                {
                    name: relative_source(absolute(path))
                    for name, path in previous.items()
                    if absolute(path).is_file()
                }
            )
            continue
        if not directory.is_dir():
            continue
        for path in directory.iterdir():
            if path.name.endswith(".sample") or not path.is_file():
                continue
            sources[path.name] = relative_source(path)
    precommit = sources.get("pre-commit")
    owns_formatting = bool(
        precommit
        and ".hypertext/formatting/commit.py" in absolute(precommit).read_text()
    )
    if precommit and not owns_formatting:
        body = absolute(precommit).read_text()
        if any(
            token in body
            for token in ("lint-staged", "ktlintFormat", "git add ", "--fix")
        ):
            raise ValueError(
                "Reconcile the mutating legacy pre-commit hook before installation: "
                + str(precommit)
            )
    header = prepared_path_header()

    def forward(source):
        if Path(source).is_absolute():
            return "exec " + shlex.quote(source) + ' "$@"\n'
        # A branch without this hook has none to run in that worktree.
        quoted = shlex.quote(source)
        return "[ -e " + quoted + " ] || exit 0\nexec " + quoted + ' "$@"\n'

    commit = ".hypertext/formatting/commit.py"
    desired = {}
    for name, source in sources.items():
        if name != "pre-commit":
            desired[name] = header + forward(source)
    body = header
    if not owns_formatting:
        body += (
            "[ -f "
            + commit
            + " ] || { echo 'Commit formatting blocked: this checkout has no "
            + commit
            + "; run: hypertext install --repo . --formatters-only --apply' >&2; exit 1; }\n"
            + shlex.quote(sys.executable)
            + " "
            + commit
            + " || exit $?\n"
        )
    if precommit:
        body += forward(precommit)
    desired["pre-commit"] = body

    def recognized_legacy(name, content):
        if content.startswith(header):
            content = "#!/bin/sh\n" + content[len(header) :]
        if name in desired and content == "#!/bin/sh\n" + desired[name][len(header) :]:
            return True
        original = previous.get(name)
        forwarding = (
            "#!/bin/sh\nexec " + shlex.quote(original) + ' "$@"\n' if original else None
        )
        if content == forwarding or (
            original and content == "#!/bin/sh\n" + forward(original)
        ):
            return True
        if name != "pre-commit":
            return False
        lines = content.splitlines()
        if not lines or lines[0] != "#!/bin/sh":
            return False
        expected_length = 3 if original else 2
        if len(lines) != expected_length:
            return False
        try:
            command = shlex.split(lines[1])
        except ValueError:
            return False
        if (
            len(command) != 5
            or not Path(command[0]).name.lower().startswith("python")
            or command[1]
            not in {
                str(root / ".hypertext/formatting/commit.py") for root in [repo, *roots]
            }
            or command[2:] != ["||", "exit", "$?"]
        ):
            return False
        return not original or lines[2] == "exec " + shlex.quote(original) + ' "$@"'

    # Preflight every managed/affected hook before setup, deletions or rewrites.
    # Legacy source-only receipts migrate only when the wrapper has a known body.
    for name in set(previous) | set(previous_wrappers) | set(desired):
        path = target / name
        if path.is_symlink():
            raise ValueError(
                "Locally modified composed hook needs reconciliation: " + name
            )
        if not path.exists():
            continue
        if not path.is_file():
            raise ValueError(
                "Locally modified composed hook needs reconciliation: " + name
            )
        actual = path.read_bytes()
        owned_digest = previous_wrappers.get(name)
        if owned_digest:
            recognized = hashlib.sha256(actual).hexdigest() == owned_digest
        else:
            content = actual.decode("utf-8")
            recognized = recognized_legacy(name, content) or (
                not previous and content == desired.get(name)
            )
        if not recognized:
            raise ValueError(
                "Locally modified composed hook needs reconciliation: " + name
            )

    if not args.skip_tools:
        setup = repo / ".hypertext/formatter-tools/setup.py"
        if setup.is_file():
            subprocess.run([sys.executable, str(setup)], cwd=repo, check=True)
    target.mkdir(exist_ok=True)
    for name in (set(previous) | set(previous_wrappers)) - set(desired):
        (target / name).unlink(missing_ok=True)
    for name, content in desired.items():
        path = target / name
        path.write_text(content)
        path.chmod(0o755)
    metadata.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "sources": {name: str(path) for name, path in sources.items()},
                "wrappers": {
                    name: hashlib.sha256(content.encode()).hexdigest()
                    for name, content in desired.items()
                },
            },
            indent=2,
        )
        + "\n"
    )
    git("config", "--local", "core.hooksPath", str(target))
    # A worktree-specific path to this repository's hooks would bypass the shared
    # composition. Anything else is the owner's configuration and stays.
    override = scoped("worktree")
    if override is not None:
        if not same_repository_hooks(override):
            raise ValueError(
                "Worktree core.hooksPath needs explicit reconciliation: " + override
            )
        git("config", "--worktree", "--unset", "core.hooksPath")
    print("Shared commit formatting installed; existing hook checks retained.")
except (OSError, ValueError, subprocess.SubprocessError) as error:
    print("Hook installation blocked: " + str(error), file=sys.stderr)
    raise SystemExit(1)
