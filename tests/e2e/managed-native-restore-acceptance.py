#!/usr/bin/env python3
"""Run native transport oracles while a separate GMD driver survives LogDate restarts."""

import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import runpy
import shlex
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

# These modules are exported through the live dependency facade to extracted helpers.
__all__ = ["base64", "hashlib", "platform", "shlex", "uuid"]

ROOT = Path(__file__).resolve().parents[2]
TARGET = runpy.run_path(str(Path(__file__).with_name("managed-native-target.py")))
EMULATOR = runpy.run_path(str(Path(__file__).with_name("managed-native-emulator.py")))
PREPARATION = runpy.run_path(
    str(Path(__file__).with_name("managed-native-preparation.py"))
)
RESOURCES = runpy.run_path(str(Path(__file__).with_name("managed-native-resources.py")))
DRIVER = TARGET["DRIVER"]
DRIVER_CASE = "app.logdate.tools.restoredriver.NativeRestoreDriverTest"
FOLLOWUPS = {
    "account-job": "native-account-job-acceptance.py",
    "missing-media": "native-missing-media-acceptance.py",
    "storage": "native-storage-recovery-acceptance.py",
    "ui": "native-recovery-ui-acceptance.py",
    "permissions": "native-permission-acceptance.py",
    "contracts": "native-post-transfer-acceptance.py",
}
SCENARIO_FILES = {
    "transfer": "native-backup-acceptance.py",
    "publication": "native-publication-acceptance.py",
    "work-restart": "native-restore-work-restart-acceptance.py",
    "d2d": "native-d2d-acceptance.py",
    "enrollment": "native-device-enrollment-acceptance.py",
    "upgrade": "native-released-upgrade-acceptance.py",
    "network-policy": "native-missing-media-acceptance.py",
    "widget": "native-widget-transfer-acceptance.py",
    "late-media": "native-late-media-acceptance.py",
}
SYNTHETIC_DATA_ARGUMENT = "--synthetic-data"
add_failure_note = runpy.run_path(
    str(Path(__file__).with_name("native-failure-diagnostics.py"))
)["add_failure_note"]


class _ManagedDependencies:
    def __init__(self, namespace):
        self.namespace = namespace

    def __getattr__(self, name):
        return self.namespace[name]


_managed_dependencies = _ManagedDependencies(globals())
_managed_helpers = {}
for _concern in ("pair", "profiles", "artifacts"):
    _managed_helpers.update(
        runpy.run_path(str(Path(__file__).with_name(f"managed-native-{_concern}.py")))
    )


def retain_terminal_results(process, results, output, started_ns):
    if process.poll() is None or not results.is_dir():
        return None
    retained = output / "managed-results"
    retained.mkdir(mode=0o700)
    for source in results.rglob("*"):
        if (
            source.is_symlink()
            or not source.is_file()
            or source.stat().st_mtime_ns < started_ns
        ):
            continue
        target = retained / source.relative_to(results)
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o600)
    return retained


def acceptance_receipt(mode, native_arguments, followups):
    return _managed_helpers["acceptance_receipt"](
        _managed_dependencies, mode, native_arguments, followups
    )


def acceptance_budget(followups, *, mode="transfer"):
    return _managed_helpers["acceptance_budget"](
        _managed_dependencies, followups, mode=mode
    )


def validate_followups(mode, followups):
    return _managed_helpers["validate_followups"](
        _managed_dependencies, mode, followups
    )


def followup_command(name, destination, app, probe, output):
    return _managed_helpers["followup_command"](
        _managed_dependencies, name, destination, app, probe, output
    )


def scenario_command(
    mode,
    source,
    destination,
    app,
    probe,
    output,
    arguments,
    *,
    classpath=None,
    fixture_environment=None,
    fixture_control=None,
):
    return _managed_helpers["scenario_command"](
        _managed_dependencies,
        mode,
        source,
        destination,
        app,
        probe,
        output,
        arguments,
        classpath=classpath,
        fixture_environment=fixture_environment,
        fixture_control=fixture_control,
    )


def managed_environment(sdk, inherited, package):
    return dict(
        inherited,
        ANDROID_HOME=str(sdk),
        ANDROID_SDK_ROOT=str(sdk),
        GRADLE_USER_HOME=str(Path.home() / ".gradle"),
        JAVA_TOOL_OPTIONS="-Djava.awt.headless=true",
        LOGDATE_NATIVE_ACCEPTANCE_PACKAGE=package,
    )


def managed_boot_deadline(current_deadline, qemu_observed_at, boot_budget):
    return (
        current_deadline
        if current_deadline is not None
        else qemu_observed_at + boot_budget
    )


def pinned_install_arguments(app, probe):
    return _managed_helpers["pinned_install_arguments"](
        _managed_dependencies, app, probe
    )


def candidate_apk_paths(app_override, probe_override, root):
    return _managed_helpers["candidate_apk_paths"](
        _managed_dependencies, app_override, probe_override, root
    )


def initial_installation(mode, app, probe, fixture_environment, output):
    return _managed_helpers["initial_installation"](
        _managed_dependencies, mode, app, probe, fixture_environment, output
    )


def validate_native_arguments(arguments):
    allowed = {
        "--exercise-quota",
        "--exercise-archive-recovery",
        "--exercise-missing-keys",
        "--exercise-missing-privacy",
        "--exercise-credentials-only",
        "--exercise-cloud-backup",
        "--exercise-data-only",
        "--exercise-duplicate-restore",
        "--exercise-partial-media",
        "--exercise-pending-camera-draft",
    }
    remaining = iter(arguments)
    for argument in remaining:
        if argument == "--identity":
            if next(remaining, None) not in {"phrase", "account", "legacy-account"}:
                raise ValueError("Select a supported native identity fixture")
        elif argument not in allowed:
            raise ValueError(
                "Only native scenario choices can be passed through the managed controller"
            )


def command(*args, check=True, timeout=15):
    return subprocess.run(
        args, cwd=ROOT, check=check, capture_output=True, timeout=timeout
    )


def process_snapshot(*, execute=command, retries=2, sleep=time.sleep):
    """Retry transient host process-list stalls without weakening emulator ownership checks."""
    for attempt in range(retries):
        try:
            return execute("ps", "-axo", "pid,args").stdout.decode().splitlines()
        except subprocess.TimeoutExpired:
            if attempt + 1 == retries:
                return None
            sleep(0.2)


def verify_managed_emulator(sdk, output, *, command=command):
    result = command(
        str(sdk / "emulator/emulator"), "-no-window", "-version", check=False
    )
    log = output / "emulator-preflight.log"
    log.write_bytes(
        b"stdout:\n" + result.stdout[:16_384] + b"\nstderr:\n" + result.stderr[:16_384]
    )
    log.chmod(0o600)
    result.check_returncode()


def discovery_identity(pid, *, process_identity=TARGET["process_identity"]):
    try:
        return process_identity(pid)
    except (subprocess.SubprocessError, RuntimeError):
        return None


class ManagedPairAmbiguousError(RuntimeError):
    def __init__(self, candidates):
        self.candidates = candidates
        super().__init__("More than two new managed devices; custody is ambiguous")


def live_managed_candidates(
    candidates,
    expected_avd,
    *,
    diagnostics=None,
    process_identity=TARGET["process_identity"],
):
    """A ps snapshot may outlive a failed boot; bind only still-live matching instances."""
    live = {}
    for pid, candidate in candidates.items():
        identity = discovery_identity(pid, process_identity=process_identity)
        if identity is None:
            continue
        current = TARGET["managed_candidates"](
            [f"{pid} {identity['command']}"], set(), expected_avd
        )
        if current.get(pid) == candidate:
            live[pid] = {**candidate, "identity": identity}
    if len(live) > 2:
        if diagnostics is not None:
            save(diagnostics, live)
        raise ManagedPairAmbiguousError(live)
    return live


def observe_driver_receipt(
    adb,
    serial,
    nonce,
    candidate,
    *,
    command=command,
    process_identity=TARGET["process_identity"],
):
    """An unresponsive boot observation is not permission to replace a live emulator."""

    def same_process():
        return (
            discovery_identity(candidate["pid"], process_identity=process_identity)
            == candidate["identity"]
        )

    if not same_process():
        return None
    receipt = None
    try:
        raw = command(
            adb,
            "-s",
            serial,
            "exec-out",
            "run-as",
            DRIVER,
            "cat",
            f"no_backup/managed-restore-{nonce}.json",
            check=False,
        )
        receipt = json.loads(raw.stdout)
    except (subprocess.TimeoutExpired, ValueError, UnicodeError):
        pass
    # AGP's setup emulator can finish before the waiting test drivers start. It has
    # never entered custody, so discard its observation and keep the original Gradle
    # handle. Once bound, require_binding still treats any identity change as fatal.
    return receipt if same_process() else None


def save(path, contents):
    temporary = path.with_suffix(path.suffix + ".part")
    with open(
        temporary, "w", opener=lambda name, flags: os.open(name, flags, 0o600)
    ) as output:
        json.dump(contents, output, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


def console_port(listeners):
    ports = {
        int(p)
        for p in re.findall(r"^n(?:127\.0\.0\.1|\[::1\]):(\d+)$", listeners, re.M)
        if 5554 <= int(p) <= 5682 and int(p) % 2 == 0
    }
    if len(ports) != 1:
        return None
    return ports.pop()


def require_outer_results(directory):
    reports = list(directory.glob("**/TEST-*.xml"))
    cases = []
    for report in reports:
        root = ET.parse(report).getroot()
        if any(
            re.search(r"\bcancel(?:ed|led|lation)\b", error.text or "", re.IGNORECASE)
            for error in root.iter("system-err")
        ):
            raise RuntimeError("The managed driver was canceled")
        for suite in root.iter("testsuite"):
            if any(
                int(suite.get(attribute, "0"))
                for attribute in ("failures", "errors", "skipped")
            ):
                raise RuntimeError(
                    "The managed driver reported a failed or skipped suite"
                )
        for case in root.iter("testcase"):
            try:
                duration = float(case.get("time", "0"))
            except ValueError as error:
                raise RuntimeError(
                    "The managed case has an invalid duration"
                ) from error
            if not math.isfinite(duration) or duration < 0:
                raise RuntimeError(
                    "The managed case did not complete with a valid duration"
                )
            if any(
                case.find(tag) is not None for tag in ("failure", "error", "skipped")
            ):
                raise RuntimeError("The managed driver failed or skipped")
            cases.append((case.get("classname"), case.get("name")))
    if sorted(cases) != [
        (DRIVER_CASE, "retainsManagedLifecycle1"),
        (DRIVER_CASE, "retainsManagedLifecycle2"),
    ]:
        raise RuntimeError(
            "Expected exactly one completed waiting driver on each managed device"
        )


def assemble_candidate_apks(
    output,
    package,
    *,
    root=ROOT,
    run=subprocess.run,
    recovery_assets=None,
    app_only=False,
    sdk=None,
):
    return _managed_helpers["assemble_candidate_apks"](
        _managed_dependencies,
        output,
        package,
        root=root,
        run=run,
        recovery_assets=recovery_assets,
        app_only=app_only,
        sdk=sdk,
    )


def android_signing_origin(sdk, apk, *, execute=None):
    return _managed_helpers["android_signing_origin"](
        _managed_dependencies, sdk, apk, execute=execute
    )


def start_manual_fixture(options, output, sdk, package):
    return _managed_helpers["start_manual_fixture"](
        _managed_dependencies, options, output, sdk, package
    )


def verify_pinned_packages(sdk, app, probe, package, *, execute=None):
    return _managed_helpers["verify_pinned_packages"](
        _managed_dependencies, sdk, app, probe, package, execute=execute
    )


def run_managed_pair(
    options, *, recovery_assets=None, fixture_control=None, expected_android_origin=None
):
    observers = []
    try:
        return _managed_helpers["run_managed_pair"](
            _managed_dependencies,
            options,
            recovery_assets=recovery_assets,
            fixture_control=fixture_control,
            expected_android_origin=expected_android_origin,
            resource_owner=observers,
        )
    finally:
        primary = sys.exc_info()[1]
        for observer in observers:
            diagnostic = None
            try:
                if not observer.close():
                    diagnostic = "Native resource collector exceeded its bounded join"
            except Exception as failure:
                diagnostic = (
                    "Native resource collector cleanup failed: "
                    + type(failure).__name__
                )
            if diagnostic:
                if primary is not None:
                    add_failure_note(primary, diagnostic)
                else:
                    print(diagnostic, file=sys.stderr)


def run(options):
    if options.mode != "manual-account":
        return run_managed_pair(options)
    if options.after_transfer or options.native_arguments:
        raise ValueError("Focused manual recovery uses its own untouched managed pair")
    output = options.output.resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    sdk = options.sdk.resolve()
    package = os.environ.get(
        "LOGDATE_NATIVE_ACCEPTANCE_PACKAGE", "studio.hypertext.logdate.debug"
    )
    process, fixture, origin = start_manual_fixture(options, output, sdk, package)
    try:
        run_managed_pair(
            options,
            recovery_assets=fixture / "assets",
            fixture_control=fixture / "server-control",
            expected_android_origin=origin,
        )
        process.wait(timeout=30)
        if process.returncode != 0:
            raise RuntimeError(
                "The local recovery fixture did not finish successfully; inspect its private log"
            )
    except BaseException:
        (output / "acceptance.json").unlink(missing_ok=True)
        raise
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--sdk",
        type=Path,
        default=Path(
            os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))
        ),
    )
    parser.add_argument(
        "--after-transfer", action="append", choices=sorted(FOLLOWUPS), default=[]
    )
    parser.add_argument(
        "--mode",
        choices=(
            "transfer",
            "publication",
            "work-restart",
            "d2d",
            "enrollment",
            "upgrade",
            "network-policy",
            "widget",
            "late-media",
            "manual-account",
        ),
        default="transfer",
    )
    parser.add_argument("--classpath", type=Path)
    parser.add_argument("--fixture-environment", type=Path)
    parser.add_argument("--candidate-app-apk", type=Path)
    parser.add_argument("--candidate-test-apk", type=Path)
    options, native_arguments = parser.parse_known_args()
    if native_arguments[:1] == ["--"]:
        native_arguments = native_arguments[1:]
    options.native_arguments = native_arguments
    run(options)
