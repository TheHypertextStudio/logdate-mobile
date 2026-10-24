#!/usr/bin/env python3
"""Run locked shared Git validation and the repository's declared product hooks."""

import os
import sys

if not sys.flags.isolated or not sys.flags.no_site:
    os.execv(
        sys.executable, [sys.executable, "-I", "-S", "-B", __file__, *sys.argv[1:]]
    )

import hashlib
import json
from pathlib import Path
import subprocess
import types

ROOT = Path(__file__).resolve().parents[1]


def shared_engine():
    """Load the same hash-locked modules as the staged formatter entrypoint."""
    root = ROOT / ".hypertext/formatting"
    expected = ("__init__", "gate", "runner", "setup_files", "formatting", "git_policy")
    lock = json.loads((root / "LOCK.json").read_text())
    if set(lock) != {name + ".py" for name in expected}:
        raise ValueError("Invalid shared Git engine lock")
    sources = {}
    for name in expected:
        path = root / "core" / (name + ".py")
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
            raise ValueError("Unsafe shared Git engine input: " + name)
        sources[name] = path.read_bytes()
        if hashlib.sha256(sources[name]).hexdigest() != lock[name + ".py"]:
            raise ValueError("Shared Git engine lock mismatch: " + name)
    package = types.ModuleType("core")
    package.__path__ = []
    sys.modules["core"] = package
    for name in expected:
        module = package if name == "__init__" else types.ModuleType("core." + name)
        module.__file__ = str(root / "core" / (name + ".py"))
        module.__package__ = "core"
        if name != "__init__":
            sys.modules[module.__name__] = module
            setattr(package, name, module)
        exec(compile(sources[name], module.__file__, "exec"), module.__dict__)
    return package


def product_hooks(mode):
    manifest = json.loads((ROOT / ".hypertext/git-hooks.json").read_text())
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported product hook manifest")
    hooks = manifest["hooks"][mode]
    phases = {}
    # Validate both phases before executing anything, including staged formatting.
    for phase in ("before", "after"):
        phases[phase] = []
        for entry in hooks[phase]:
            relative = Path(entry["path"])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Product hook must stay inside the checkout")
            path = ROOT / relative
            if (
                path.is_symlink()
                or not path.resolve().is_relative_to(ROOT)
                or not path.is_file()
                or path.stat().st_size > 1024 * 1024
                or hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]
            ):
                raise ValueError("Product hook hash mismatch: " + str(relative))
            phases[phase].append(path)
    return phases


def run_product(paths, arguments, transaction):
    for path in paths:
        subprocess.run(
            [str(path), *arguments],
            cwd=ROOT,
            input=transaction,
            check=True,
        )


def main():
    mode, *arguments = sys.argv[1:]
    counts = {"pre-commit": 0, "commit-msg": 1, "pre-push": 2}
    if mode not in counts or len(arguments) != counts[mode]:
        raise ValueError(
            "Expected pre-commit, commit-msg MESSAGE, or pre-push REMOTE URL"
        )
    engine = shared_engine()
    policy = engine.git_policy
    phases = product_hooks(mode)
    transaction = None
    if mode == "pre-push":
        transaction = sys.stdin.buffer.read(1024 * 1024 + 1)
        if len(transaction) > 1024 * 1024:
            raise ValueError("Push transaction exceeds 1 MiB")
    if mode == "pre-commit":
        exception = policy.require_studio_identity(ROOT)
        if exception:
            print(exception, file=sys.stderr)
        policy.hook_policy(ROOT)
    run_product(phases["before"], arguments, transaction)
    # The formatter bundle exposes the validators as library functions. Its CLI
    # entrypoint depends on operator modules deliberately absent from this bundle.
    if mode == "pre-commit":
        policy.hook_policy(ROOT)
        changed = engine.formatting.format_staged(ROOT)
        policy.hook_policy(ROOT)
        if changed:
            print("Automatically formatted " + str(len(changed)) + " staged file(s).")
    elif mode == "commit-msg":
        registry = policy.read_policy(ROOT / policy.POLICY_PATH)
        policy.check_registry(ROOT, registry)
        registry = policy.hook_policy(ROOT)
        expected = (
            [os.environ["HYPERTEXT_AGENT_COAUTHOR"]]
            if os.environ.get("HYPERTEXT_AGENT_COAUTHOR")
            else []
        )
        policy.validate_message(
            policy.read_text(arguments[0]).replace("\r\n", "\n"), registry, expected
        )
    else:
        registry = policy.read_policy(ROOT / policy.POLICY_PATH)
        policy.check_hook_context(ROOT)
        policy.verify_push(
            ROOT,
            arguments[0],
            transaction.decode("utf-8", errors="surrogateescape"),
            registry,
            arguments[1],
        )
    run_product(phases["after"], arguments, transaction)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        raise SystemExit(
            error.returncode if error.returncode > 0 else 128 - error.returncode
        )
    except (
        OSError,
        ValueError,
        KeyError,
        TypeError,
        subprocess.SubprocessError,
    ) as error:
        print("Git validation blocked: " + str(error), file=sys.stderr)
        raise SystemExit(1)
