"""Format staged blobs without stashing or staging working-tree edits."""

import fnmatch
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import time

from . import runner, setup_files

POLICY = ".hypertext/formatters.json"
MAX_FILE = 16 * 1024 * 1024
MAX_FILES = 1000
CONFIG_NAMES = {
    ".editorconfig",
    "pyproject.toml",
    "ruff.toml",
    ".ruff.toml",
    "rustfmt.toml",
    ".rustfmt.toml",
    ".swiftformat",
    ".swift-format",
    ".clang-format",
    ".prettierignore",
    "package.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "libs.versions.toml",
    "gradle.properties",
    "Cargo.toml",
    "rust-toolchain.toml",
    "global.json",
}


def configuration_file(name):
    base = Path(name).name
    return (
        base in CONFIG_NAMES
        or Path(name).parent.name == "prettier"
        and Path(name).suffix in {".js", ".mjs", ".cjs"}
        or base.startswith((".prettierrc", "prettier.config."))
        or str(name).replace("\\", "/").startswith(".hypertext/formatter-tools/")
        and base
        in {
            "tools.json",
            "preset.mjs",
            "native-rules.json",
            "native-addons-rules.json",
            "document-rules.json",
            "dsl-rules.json",
            "kotlin.editorconfig",
            "npm-lock.json",
            "requirements.txt",
        }
    )


def read_file(path):
    if path.is_symlink():
        raise ValueError("Formatting refuses symlinked files: " + str(path))
    if not path.exists():
        return None
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > MAX_FILE:
        raise ValueError(
            "Formatting requires a regular, unshared file under 16 MiB: " + str(path)
        )
    return path.read_bytes()


def validate_policy(value):
    if (
        not isinstance(value, dict)
        or set(value) != {"schema_version", "formatters", "exclude"}
        or type(value["schema_version"]) is not int
        or value["schema_version"] != 1
    ):
        raise ValueError(
            "Formatter policy needs schema_version 1, formatters and exclude"
        )
    if (
        not isinstance(value["formatters"], list)
        or not value["formatters"]
        or not isinstance(value["exclude"], list)
    ):
        raise ValueError("Configure actual formatters and explicit exclusions")
    for rule in value["formatters"] + value["exclude"]:
        if not isinstance(rule, dict):
            raise ValueError("Invalid formatter rule")
        patterns = rule.get("include")
        if (
            not isinstance(patterns, list)
            or not patterns
            or any(
                not isinstance(p, str)
                or not p
                or "\0" in p
                or p.startswith("/")
                or ".." in p.split("/")
                for p in patterns
            )
        ):
            raise ValueError(
                "Formatter include patterns must be nonempty repository-relative globs"
            )
        if "argv" in rule:
            argv = rule["argv"]
            if (
                set(rule) - {"include", "argv", "timeout_seconds"}
                or not isinstance(argv, list)
                or not argv
                or any(not isinstance(a, str) or not a or "\0" in a for a in argv)
            ):
                raise ValueError(
                    "A formatter requires an argv array, not a shell command"
                )
            timeout = rule.get("timeout_seconds", 30)
            if type(timeout) is not int or not 1 <= timeout <= 120:
                raise ValueError("Formatter timeout must be between 1 and 120 seconds")
        elif (
            set(rule) != {"include", "kind", "reason"}
            or rule["kind"]
            not in {"binary", "generated", "vendored", "non-formattable"}
            or not isinstance(rule["reason"], str)
            or not rule["reason"].strip()
        ):
            raise ValueError(
                "Every formatter exclusion needs a kind and a concrete reason"
            )
    if any("argv" not in r for r in value["formatters"]) or any(
        "argv" in r for r in value["exclude"]
    ):
        raise ValueError(
            "Keep formatter commands and justified exclusions in their respective lists"
        )
    return value


def format_staged(repo):
    repo = Path(repo).resolve()
    git_executable = setup_files.trusted_git(repo)
    environment = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    if os.environ.get("GIT_INDEX_FILE"):
        environment["GIT_INDEX_FILE"] = os.environ["GIT_INDEX_FILE"]
    environment.update(
        GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never", SSH_ASKPASS_REQUIRE="never"
    )
    deadline = time.monotonic() + 120

    def execute(argv, data=None, env=None, allowed=(0,), timeout=30):
        remaining = min(timeout, deadline - time.monotonic())
        if remaining <= 0:
            raise ValueError("Staged formatting exceeded its 120-second deadline")
        output = io.BytesIO()
        with tempfile.TemporaryFile() as source, tempfile.TemporaryFile() as errors:
            if data is not None:
                source.write(data)
                source.seek(0)
            try:
                code = runner.execute(
                    argv,
                    repo,
                    output,
                    remaining,
                    stdin=source,
                    environment=environment if env is None else env,
                    stderr=errors,
                )
            except (OSError, subprocess.SubprocessError) as error:
                raise ValueError(
                    "Formatting tool unavailable, failed or timed out: "
                    + Path(argv[0]).name
                ) from None
        if code not in allowed:
            raise ValueError(
                "Formatting command failed: "
                + Path(argv[0]).name
                + " (exit "
                + str(code)
                + ")"
            )
        return code, output.getvalue()

    def git(*args, data=None, env=None, allowed=(0,)):
        return execute(
            [
                git_executable,
                "--no-replace-objects",
                "-c",
                "core.fsmonitor=false",
                "-C",
                str(repo),
                *args,
            ],
            data,
            env,
            allowed,
            15,
        )

    index = Path(os.fsdecode(git("rev-parse", "--git-path", "index")[1]).strip())
    if not index.is_absolute():
        index = repo / index
    index = index.absolute()
    original_index = read_file(index)
    if original_index is None:
        raise ValueError("The selected Git index is missing")
    lock = index.with_name(index.name + ".lock")
    # Respect the lock Git uses for the real index or --only's temporary index.
    fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    owned_lock = os.fstat(fd)
    temporary = None

    def owns_lock():
        try:
            current = lock.lstat()
            return (current.st_dev, current.st_ino) == (
                owned_lock.st_dev,
                owned_lock.st_ino,
            )
        except FileNotFoundError:
            return False

    published = False
    retain_recovery = False
    writes = []
    pending_index = None
    pending_written = False
    try:
        temporary = Path(tempfile.mkdtemp(prefix="studio-format-", dir=index.parent))
        if read_file(index) != original_index:
            raise ValueError("Git index changed before formatting; retry the commit")
        candidate = temporary / "index"
        candidate.write_bytes(original_index)
        candidate_env = dict(environment, GIT_INDEX_FILE=str(candidate))
        paths = git(
            "diff",
            "--cached",
            "--name-only",
            "--no-renames",
            "--no-ext-diff",
            "--diff-filter=ACMT",
            "-z",
            env=candidate_env,
        )[1].split(b"\0")[:-1]
        if not paths:
            return []
        if len(paths) > MAX_FILES:
            raise ValueError("Staged formatting exceeds 1000 files; split the commit")
        paths = [os.fsdecode(p) for p in paths]
        normal_env = {k: v for k, v in environment.items() if k != "GIT_INDEX_FILE"}
        normal_index = Path(
            os.fsdecode(
                git("rev-parse", "--git-path", "index", env=normal_env)[1]
            ).strip()
        )
        if not normal_index.is_absolute():
            normal_index = repo / normal_index
        if (
            normal_index.absolute() != index
            and index.parent == normal_index.absolute().parent
            and index.name.startswith("next-index-")
        ):
            # --only gives the hook a temporary index, while Git separately holds
            # the real index's pending lock. Keep its selected entries in sync.
            pending = normal_index.with_name(normal_index.name + ".lock")
            pending_bytes = read_file(pending)
            if pending_bytes is None:
                raise ValueError(
                    "Alternate index requires Git partial-commit pending index"
                )
            pending_info = pending.stat()
            pending_candidate = temporary / "pending-index"
            pending_candidate.write_bytes(pending_bytes)
            pending_env = dict(environment, GIT_INDEX_FILE=str(pending_candidate))
            pending_index = (pending, pending_bytes, pending_info, pending_candidate)

        entries = {}
        for row in git("ls-files", "--stage", "-z", env=candidate_env)[1].split(b"\0")[
            :-1
        ]:
            meta, name = row.split(b"\t", 1)
            mode, oid, stage = meta.decode("ascii").split()
            if stage != "0":
                raise ValueError("Resolve merge conflicts before formatting a commit")
            entries[os.fsdecode(name)] = (mode, oid)

        def blob(name):
            if name not in entries:
                raise ValueError(
                    "Stage the repository formatter policy before committing: " + POLICY
                )
            return git("cat-file", "blob", entries[name][1], env=candidate_env)[1]

        from .git_policy import unique

        policy_path = setup_files.confined(repo / POLICY, repo)
        policy_bytes = blob(POLICY)
        if read_file(policy_path) != policy_bytes:
            raise ValueError("Working and staged formatter policies differ: " + POLICY)
        policy = validate_policy(json.loads(policy_bytes, object_pairs_hook=unique))
        # Interpreter entrypoints are part of the selected formatter configuration.
        # Never execute unstaged edits to an authored repository adapter.
        adapter_inputs = set()
        for rule in policy["formatters"]:
            for argument in rule["argv"]:
                expanded = argument.replace("{repo}", str(repo))
                adapter_path = Path(expanded)
                if not adapter_path.is_absolute():
                    adapter_path = repo / adapter_path
                if adapter_path.is_relative_to(repo) and adapter_path.suffix in {
                    ".py",
                    ".mjs",
                    ".js",
                    ".sh",
                }:
                    relative = adapter_path.relative_to(repo).as_posix()
                    if relative in entries or adapter_path.is_file():
                        adapter_inputs.add(relative)
        for name in adapter_inputs:
            if name not in entries or read_file(
                setup_files.confined(repo / name, repo)
            ) != blob(name):
                raise ValueError(
                    "Stage or restore formatter configuration before committing: "
                    + name
                )
        # Native formatter configuration remains authoritative, but unstaged
        # configuration must not silently determine the committed style.
        for name in entries:
            base = Path(name).name
            if configuration_file(name):
                if read_file(setup_files.confined(repo / name, repo)) != blob(name):
                    raise ValueError(
                        "Stage or restore formatter configuration before committing: "
                        + name
                    )

        directories = {repo}
        for name in paths:
            directories.update((repo / name).parent.parents)
            directories.add((repo / name).parent)
        for directory in directories:
            if not directory.exists():
                continue
            for config in directory.iterdir():
                base = config.name
                relative = (
                    config.relative_to(repo).as_posix()
                    if config.is_relative_to(repo)
                    else base
                )
                if configuration_file(relative):
                    if not config.is_file() and not config.is_symlink():
                        continue
                    if not config.is_relative_to(repo):
                        raise ValueError(
                            "External formatter configuration must be made repository-local: "
                            + str(config)
                        )
                    name = config.relative_to(repo).as_posix()
                    if name not in entries or read_file(config) != blob(name):
                        raise ValueError(
                            "Stage or restore formatter configuration before committing: "
                            + name
                        )

        changes = []
        for number, name in enumerate(paths):
            mode, oid = entries[name]
            excluded = [
                r
                for r in policy["exclude"]
                if any(fnmatch.fnmatchcase(name, p) for p in r["include"])
            ]
            if excluded:
                if any(r["kind"] == "binary" for r in excluded) and mode in {
                    "100644",
                    "100755",
                }:
                    content = blob(name)
                    try:
                        content.decode("utf-8")
                        text = b"\0" not in content
                    except UnicodeDecodeError:
                        text = False
                    if text:
                        raise ValueError(
                            "Text cannot use a binary formatter exclusion: " + name
                        )
                continue
            if mode not in {"100644", "100755"}:
                raise ValueError(
                    "Explicitly classify nonregular staged entries before formatting: "
                    + name
                )
            rules = [
                r
                for r in policy["formatters"]
                if any(fnmatch.fnmatchcase(name, p) for p in r["include"])
            ]
            if len(rules) != 1:
                raise ValueError(
                    "Expected exactly one formatter for staged file: " + name
                )
            target = setup_files.confined(repo / name, repo)
            working = read_file(target)
            original = blob(name)
            if len(original) > MAX_FILE or b"\0" in original:
                raise ValueError(
                    "Explicitly classify binary/oversized staged files: " + name
                )
            argv = [
                a.replace("{path}", name).replace("{repo}", str(repo))
                for a in rules[0]["argv"]
            ]
            timeout = rules[0].get("timeout_seconds", 30)
            try:
                formatted = execute(argv, original, timeout=timeout)[1]
            except ValueError as error:
                raise ValueError(str(error) + ": " + name) from None
            if b"\0" in formatted:
                raise ValueError("Formatter returned binary output: " + name)
            if original.strip() and not formatted.strip():
                raise ValueError(
                    "Formatter returned empty content for a nonempty file: " + name
                )
            if execute(argv, formatted, timeout=timeout)[1] != formatted:
                raise ValueError("Formatter is not idempotent: " + name)
            if original == formatted:
                continue
            merged = None
            if working is not None:
                if working == original:
                    merged = formatted
                else:
                    base_bytes, new_bytes = original, formatted
                    if b"\r\n" in working and b"\n" not in working.replace(
                        b"\r\n", b""
                    ):
                        base_bytes = original.replace(b"\r\n", b"\n").replace(
                            b"\n", b"\r\n"
                        )
                        new_bytes = formatted.replace(b"\r\n", b"\n").replace(
                            b"\n", b"\r\n"
                        )
                    files = [
                        temporary / (str(number) + suffix)
                        for suffix in (".working", ".base", ".formatted")
                    ]
                    for path, data in zip(files, (working, base_bytes, new_bytes)):
                        path.write_bytes(data)
                    code, merged = git(
                        "merge-file",
                        "--stdout",
                        *map(str, files),
                        allowed=tuple(range(128)),
                    )
                    if code:
                        raise ValueError(
                            "Formatting conflicts with unstaged edits; index and files preserved: "
                            + name
                        )
            new_oid = (
                git("hash-object", "-w", "--stdin", data=formatted)[1]
                .decode("ascii")
                .strip()
            )
            git(
                "update-index",
                "--cacheinfo",
                mode + "," + new_oid + "," + name,
                env=candidate_env,
            )
            if pending_index is not None:
                git(
                    "update-index",
                    "--cacheinfo",
                    mode + "," + new_oid + "," + name,
                    env=pending_env,
                )
            changes.append((target, working, merged, name))

        if pending_index is not None:
            pending, pending_bytes, pending_info, pending_candidate = pending_index
            current = pending.lstat()
            if (current.st_dev, current.st_ino) != (
                pending_info.st_dev,
                pending_info.st_ino,
            ) or read_file(pending) != pending_bytes:
                raise ValueError("Git partial-commit index changed during formatting")
        if read_file(index) != original_index or any(
            read_file(p) != old for p, old, new, name in changes
        ):
            raise ValueError(
                "Index or working files changed during formatting; retry without discarding edits"
            )
        for target, old, new, name in changes:
            if old is not None and old != new:
                # Retain original bytes until the index transaction is published.
                (temporary / ("backup-" + str(len(writes)))).write_bytes(old)
                writes.append((target, old, new))
                target.write_bytes(new)
        if read_file(index) != original_index:
            raise ValueError(
                "Git index changed during formatting; preserving the concurrent index"
            )
        if not owns_lock():
            raise ValueError(
                "Git index lock changed during formatting; retry the commit"
            )
        with os.fdopen(fd, "wb") as output:
            fd = None
            output.write(candidate.read_bytes())
            output.flush()
            os.fsync(output.fileno())
        if not owns_lock():
            raise ValueError(
                "Git index lock changed during formatting; retry the commit"
            )
        if pending_index is not None:
            pending_fd = os.open(pending, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(pending_fd, "r+b") as output:
                current = os.fstat(output.fileno())
                if (current.st_dev, current.st_ino) != (
                    pending_info.st_dev,
                    pending_info.st_ino,
                ) or output.read() != pending_bytes:
                    raise ValueError(
                        "Git partial-commit index changed before publication"
                    )
                pending_written = True
                output.seek(0)
                output.write(pending_candidate.read_bytes())
                output.truncate()
                output.flush()
                os.fsync(output.fileno())
        os.replace(lock, index)
        published = True
        return [name for target, old, new, name in changes]
    except BaseException:
        if pending_written:
            try:
                current = pending.lstat()
                if (current.st_dev, current.st_ino) != (
                    pending_info.st_dev,
                    pending_info.st_ino,
                ):
                    raise ValueError("Pending index replaced")
                pending.write_bytes(pending_bytes)
            except (OSError, ValueError):
                (temporary / "pending-index-original").write_bytes(pending_bytes)
                retain_recovery = True
        for target, old, new in reversed(writes):
            try:
                if read_file(target) == new:
                    target.write_bytes(old)
                else:
                    retain_recovery = True
            except OSError:
                retain_recovery = True
            except ValueError:
                retain_recovery = True
        if retain_recovery:
            print(
                "Formatter recovery bytes retained at " + str(temporary),
                file=__import__("sys").stderr,
            )
        raise
    finally:
        if fd is not None:
            os.close(fd)
        if not published and owns_lock():
            lock.unlink()
        if temporary is not None and not retain_recovery:
            shutil.rmtree(temporary)
