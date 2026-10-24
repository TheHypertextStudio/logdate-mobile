#!/usr/bin/env python3
"""Prepare only pinned formatter dependencies; never run on commit."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parents[1]
SPEC = (
    json.loads((TOOLS / "tools.json").read_text())
    if (TOOLS / "tools.json").exists()
    else None
)
CACHE = (
    Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache")))
    / "hypertext-studio/formatters"
)


def prettier_version():
    source = SPEC["prettier"]
    if "version" in source:
        return source["version"]
    manifest = ROOT / source["manifest"]
    data = json.loads(manifest.read_text())
    value = next(
        (
            data.get(k, {}).get("prettier")
            for k in ("devDependencies", "dependencies")
            if data.get(k, {}).get("prettier")
        ),
        None,
    )
    if value and value.startswith("catalog:"):
        workspace = (ROOT / "pnpm-workspace.yaml").read_text()
        match = re.search(
            r"^\s+prettier:\s*[\'\"]?(\d+\.\d+\.\d+)[\'\"]?\s*$",
            workspace,
            re.MULTILINE,
        )
        value = match[1] if match else None
    if not value or not re.fullmatch(r"\d+\.\d+\.\d+", value):
        raise ValueError(
            "Existing Prettier must have an exact repository pin before setup"
        )
    return value


def npm_command():
    npm = shutil.which("npm")
    if not npm:
        raise ValueError("Install Node/npm to prepare pinned Markdown formatting")
    path = Path(npm).resolve()
    script = (
        path
        if path.suffix == ".js"
        else path.parent / "node_modules/npm/bin/npm-cli.js"
    )
    return ["node", str(script)] if script.is_file() else [npm]


def npm_inventory_matches(folder, lock, recipe):
    """Check the installed locked package inventory, including every direct plugin."""
    packages = lock.get("packages", {})
    for name, version in recipe["dependencies"].items():
        if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[\w.-]+)?", version):
            raise ValueError("Formatter dependencies require exact pins: " + name)
        metadata = folder / "node_modules" / name / "package.json"
        try:
            if json.loads(metadata.read_text()).get("version") != version:
                return False
        except (OSError, ValueError):
            return False
    for name, expected in packages.items():
        if not name:
            continue
        relative = Path(name)
        if (
            relative.is_absolute()
            or ".." in relative.parts
            or relative.parts[0] != "node_modules"
        ):
            raise ValueError("Formatter lock contains an unsafe package path")
        metadata = folder / relative / "package.json"
        if expected.get("optional") and not metadata.exists():
            # npm omits platform-incompatible optional packages. Direct dependencies were
            # checked above and never receive this exemption.
            continue
        try:
            if json.loads(metadata.read_text()).get("version") != expected.get(
                "version"
            ):
                return False
        except (OSError, ValueError):
            return False
    return True


def install_npm(folder, recipe, arguments):
    """Authenticate private packages only during explicit setup, without saved tokens."""
    private = any(
        name.startswith("@thehypertextstudio/") for name in recipe["dependencies"]
    )
    kwargs = {}
    options = []
    if private:
        configuration = folder / ".npmrc"
        content = (
            "@thehypertextstudio:registry=https://npm.pkg.github.com\n"
            "//npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}\n"
        )
        if configuration.exists() and configuration.read_text() != content:
            raise ValueError(
                "Formatter cache registry configuration needs reconciliation"
            )
        environment = os.environ.copy()
        token = (
            environment.get("NODE_AUTH_TOKEN")
            or environment.get("GH_TOKEN")
            or environment.get("GITHUB_TOKEN")
        )
        if not token and shutil.which("gh"):
            result = subprocess.run(
                ["gh", "auth", "token", "--hostname", "github.com"],
                capture_output=True,
                check=False,
                text=True,
                timeout=15,
            )
            if result.returncode == 0:
                token = result.stdout.strip()
        if not token:
            raise ValueError(
                "Studio package access requires GitHub authentication or NODE_AUTH_TOKEN during setup"
            )
        environment["NODE_AUTH_TOKEN"] = token
        configuration.write_text(content)
        options = ["--userconfig", str(configuration)]
        kwargs["env"] = environment
    subprocess.run([*npm_command(), *arguments, *options], check=True, **kwargs)


@contextmanager
def cache_lock(folder, timeout=180):
    """Serialize explicit setup across processes; commits never acquire this lock."""
    folder.parent.mkdir(parents=True, exist_ok=True)
    lock = folder.with_name(folder.name + ".lock")
    deadline = time.monotonic() + timeout
    while True:
        try:
            lock.mkdir()
            break
        except FileExistsError:
            if lock.is_symlink() or not lock.is_dir():
                raise ValueError("Unsafe formatter setup lock: " + str(lock)) from None
            if time.monotonic() >= deadline:
                raise ValueError(
                    "Formatter setup is locked; inspect its owner and retry: "
                    + str(lock)
                ) from None
            time.sleep(0.1)
    try:
        (lock / "owner.json").write_text(json.dumps({"pid": os.getpid()}) + "\n")
        yield
    finally:
        (lock / "owner.json").unlink(missing_ok=True)
        lock.rmdir()


def npm_cache_key(recipe, lock_bytes):
    # Different reviewed transitive locks never mutate the same prepared runtime.
    return hashlib.sha256(
        json.dumps(recipe, sort_keys=True).encode() + b"\0" + lock_bytes
    ).hexdigest()[:20]


def prepare_npm(folder, recipe, lock_bytes):
    with cache_lock(folder):
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "package.json").write_text(json.dumps(recipe, indent=2) + "\n")
        (folder / "package-lock.json").write_bytes(lock_bytes)
        _prepare_npm_locked(folder, recipe, lock_bytes)


def _prepare_npm_locked(folder, recipe, lock_bytes):
    """Reuse only a complete install bound to the exact reviewed integrity lock."""
    data = json.loads(lock_bytes)
    if (
        data.get("packages", {}).get("", {}).get("dependencies")
        != recipe["dependencies"]
    ):
        raise ValueError("Formatter lock differs from current repository pins")
    for name, package in data["packages"].items():
        if name and (not package.get("version") or not package.get("integrity")):
            raise ValueError(
                "Formatter lock lacks package version or integrity: " + name
            )
    wanted = {
        "schema_version": 1,
        "lock_sha256": hashlib.sha256(lock_bytes).hexdigest(),
        "recipe_sha256": hashlib.sha256(
            json.dumps(recipe, sort_keys=True).encode()
        ).hexdigest(),
    }
    marker = folder / ".installed.json"
    try:
        complete = json.loads(marker.read_text()) == wanted
    except (OSError, ValueError):
        complete = False
    if complete and npm_inventory_matches(folder, data, recipe):
        return
    marker.unlink(missing_ok=True)
    install_npm(
        folder,
        recipe,
        [
            "ci",
            "--prefix",
            str(folder),
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
            "--registry=https://registry.npmjs.org",
        ],
    )
    if not npm_inventory_matches(folder, data, recipe):
        raise ValueError(
            "Formatter dependency installation is incomplete; rerun hook setup"
        )
    # A failed or interrupted npm ci never publishes a successful installation marker.
    temporary = marker.with_suffix(".tmp." + str(os.getpid()))
    try:
        temporary.write_text(json.dumps(wanted, indent=2) + "\n")
        os.replace(temporary, marker)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    version = prettier_version()
    recipe = {
        "name": "studio-formatter-runtime",
        "private": True,
        "dependencies": {"prettier": version, **SPEC["npm"]},
    }
    lock = TOOLS / "npm-lock.json"
    if not lock.exists():
        # Resolve a missing lock in a private temporary directory, never a shared runtime.
        with tempfile.TemporaryDirectory(
            prefix="studio-formatter-recipe-"
        ) as temporary:
            staging = Path(temporary)
            (staging / "package.json").write_text(json.dumps(recipe, indent=2) + "\n")
            install_npm(
                staging,
                recipe,
                [
                    "install",
                    "--prefix",
                    str(staging),
                    "--package-lock-only",
                    "--ignore-scripts",
                    "--no-audit",
                    "--no-fund",
                    "--registry=https://registry.npmjs.org",
                ],
            )
            temporary_lock = lock.with_suffix(".tmp." + str(os.getpid()))
            try:
                temporary_lock.write_bytes((staging / "package-lock.json").read_bytes())
                os.replace(temporary_lock, lock)
            finally:
                temporary_lock.unlink(missing_ok=True)
    lock_bytes = lock.read_bytes()
    data = json.loads(lock_bytes)
    if data["packages"][""]["dependencies"] != recipe["dependencies"]:
        raise ValueError(
            "Formatter lock differs from current repository pins; regenerate npm-lock.json with the reviewed dependency change"
        )
    folder = CACHE / "npm" / npm_cache_key(recipe, lock_bytes)
    prepare_npm(folder, recipe, lock_bytes)
    ruffdir = CACHE / "ruff" / SPEC["ruff"]
    python = ruffdir / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    ruff = ruffdir / ("Scripts/ruff.exe" if os.name == "nt" else "bin/ruff")
    with cache_lock(ruffdir):
        if not python.is_file():
            subprocess.run([sys.executable, "-m", "venv", str(ruffdir)], check=True)
        if (
            not ruff.is_file()
            or subprocess.check_output([str(ruff), "--version"]).strip()
            != ("ruff " + SPEC["ruff"]).encode()
        ):
            subprocess.run(
                [
                    str(python),
                    "-m",
                    "pip",
                    "install",
                    "--disable-pip-version-check",
                    "ruff==" + SPEC["ruff"],
                ],
                check=True,
            )
    for command in SPEC.get("native_setup", []):
        subprocess.run(
            [
                v.replace("{repo}", str(ROOT)).replace("{root}", str(ROOT))
                for v in command
            ],
            cwd=ROOT,
            check=True,
        )
    runtime = {
        "npm": str(folder / "node_modules"),
        "prettier": version,
        "ruff": str(ruff),
        "spec": SPEC,
    }
    (TOOLS / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n")
    print("Pinned commit formatters prepared.")


if __name__ == "__main__":
    if sys.argv[1:]:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--npm-only", action="store_true", required=True)
        parser.add_argument("command", choices=["ci"])
        parser.add_argument("--prefix", type=Path, default=TOOLS)
        arguments = parser.parse_args()
        folder = arguments.prefix.resolve()
        manifest = json.loads((folder / "package.json").read_text())
        install_npm(
            folder,
            {"dependencies": manifest["devDependencies"]},
            [
                "ci",
                "--prefix",
                str(folder),
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
            ],
        )
    else:
        main()
