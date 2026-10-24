#!/usr/bin/env python3
"""Verify missing-original recovery on explicitly selected synthetic Android emulators."""

import argparse
import json
import os
from pathlib import Path
import re
import runpy
import shlex
import subprocess
import threading
import time
import uuid

# Use the adjacent helper for both standalone and importlib-loaded entry points.
instrumentation_passed = runpy.run_path(
    str(Path(__file__).with_name("native-instrumentation-results.py"))
)["instrumentation_passed"]


managed_adb_command = runpy.run_path(
    str(Path(__file__).with_name("managed-native-target.py"))
)["managed_adb_command"]
add_failure_note = runpy.run_path(
    str(Path(__file__).with_name("native-failure-diagnostics.py"))
)["add_failure_note"]


_environment = runpy.run_path(
    str(Path(__file__).with_name("native-missing-media-environment.py"))
)


PACKAGE = os.environ.get(
    "LOGDATE_NATIVE_ACCEPTANCE_PACKAGE", "studio.hypertext.logdate.debug"
)
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST = "app.logdate.client.e2e.NativeMissingMediaE2ETest"
PERMISSIONS = [
    "READ_MEDIA_IMAGES",
    "READ_MEDIA_VIDEO",
    "READ_MEDIA_AUDIO",
    "POST_NOTIFICATIONS",
]


def adb(serial, *args, timeout=180, check=True, input=None):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("An explicit emulator-NNNN serial is required")
    return subprocess.run(
        managed_adb_command(serial, args),
        input=input,
        capture_output=True,
        timeout=timeout,
        check=check,
    )


def safe_target(serial):
    if adb(serial, "shell", "getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise RuntimeError("Target did not identify itself as an emulator")
    return adb(serial, "emu", "avd", "name").stdout.decode().splitlines()[0]


def set_metered(serial, network, mode):
    return _environment["set_metered"](serial, network, mode, adb=adb)


def release_network_handoff(serial, network, nonce, report_path):
    if not re.fullmatch(r"[a-f0-9]{32}", nonce):
        raise ValueError("Network handoff requires a fresh fixture nonce")
    started = time.monotonic()
    report = {"acknowledged": False, "steps": []}

    def completed(stage):
        report["steps"].append(
            {"stage": stage, "elapsedSeconds": time.monotonic() - started}
        )

    try:
        adb(serial, "shell", "cmd", "netpolicy", "set", "restrict-background", "false")
        completed("dataSaverSet")
        state = adb(
            serial, "shell", "cmd", "netpolicy", "get", "restrict-background"
        ).stdout
        if b"disabled" not in state or b"enabled" in state:
            raise RuntimeError("Data Saver did not reach its requested disabled state")
        completed("dataSaverVerified")
        set_metered(serial, network, "false")
        completed("meteredVerified")
        remote = f"no_backup/restore-network-handoff-{nonce}.txt"
        # The device may consume the ACK immediately after publication.
        script = f"umask 077; cat > {remote}.part && cat {remote}.part && mv {remote}.part {remote}"
        result = adb(
            serial,
            "shell",
            shlex.join(["run-as", PACKAGE, "sh", "-c", script]),
            input=b"applied",
        )
        if result.stdout != b"applied":
            raise RuntimeError("Network handoff acknowledgment did not round-trip")
        completed("acknowledged")
        report["acknowledged"] = True
    except BaseException as error:
        report["failureType"] = type(error).__name__
        if isinstance(error, subprocess.CalledProcessError):
            report["commandFailure"] = (error.stderr or b"").decode(errors="replace")[
                -2000:
            ]
        raise
    finally:
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        report_path.chmod(0o600)


def run_network_cases(serial, network, directory):
    completed = []
    for saver in [False, True]:
        adb(
            serial,
            "shell",
            "cmd",
            "netpolicy",
            "set",
            "restrict-background",
            str(saver).lower(),
        )
        set_metered(serial, network, "true")
        nonce = uuid.uuid4().hex
        label = "data-saver" if saver else "metered"
        instrument(
            serial,
            TEST + "#dMeteredRecoveryResumesWhenPlatformAllowsMedia",
            directory / (label + "-recovery.log"),
            extras=[
                "-e",
                "restoreNetworkPolicyExpected",
                "restricted" if saver else "metered",
                "-e",
                "restoreNetworkHandoff",
                nonce,
            ],
            release_network=lambda: release_network_handoff(
                serial, network, nonce, directory / (label + "-handoff.json")
            ),
            timeout=180,
        )
        completed.append(label + "-recovery")
    return completed


def instrument_failure_cleanup(serial, log, failure, failure_diagnostics):
    if failure_diagnostics is not None:
        try:
            failure_diagnostics(serial, log, failure)
        except Exception as error:
            add_failure_note(
                failure,
                "Failure diagnostics could not be retained: " + type(error).__name__,
            )
    try:
        safe_target(serial)
    except Exception as error:
        add_failure_note(
            failure,
            "Instrument cleanup ownership check failed: " + type(error).__name__,
        )
        return
    for package in (PACKAGE, PACKAGE + ".test"):
        try:
            adb(serial, "shell", "am", "force-stop", package)
        except Exception as error:
            add_failure_note(
                failure, "Instrument force-stop cleanup failed: " + type(error).__name__
            )


def instrument(
    serial,
    selector,
    log,
    extras=(),
    release_network=None,
    status_actions=None,
    timeout=120,
    failure_diagnostics=None,
    expected_tests=1,
):
    safe_target(serial)
    command = shlex.join(
        ["am", "instrument", "-w", "-r", "-e", "class", selector, *extras, RUNNER]
    )
    process = subprocess.Popen(
        managed_adb_command(serial, ["shell", command]),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    timer = threading.Timer(timeout, process.kill)
    timer.start()
    output = bytearray()
    released = False
    pending_actions = dict(status_actions or {})
    primary = None
    try:
        log.write_bytes(b"")
        log.chmod(0o600)
        for line in process.stdout:
            output.extend(line)
            with log.open("ab") as transcript:
                transcript.write(line)
            for marker in list(pending_actions):
                if marker in line:
                    pending_actions.pop(marker)()
            if release_network and b"RESTORE_NETWORK_DEFERRED" in line:
                release_network()
                released = True
        code = process.wait()
        if (
            code != 0
            or not instrumentation_passed(output, expected_tests)
            or release_network
            and not released
            or pending_actions
        ):
            raise RuntimeError(f"Emulator acceptance failed; see {log}")
    except BaseException as failure:
        primary = failure
        instrument_failure_cleanup(serial, log, failure, failure_diagnostics)
        raise
    finally:

        def stop_pending():
            if process.poll() is None:
                process.kill()

        errors = []
        for operation in (
            timer.cancel,
            stop_pending,
            lambda: log.write_bytes(output),
            lambda: log.chmod(0o600),
        ):
            try:
                operation()
            except Exception as error:
                errors.append(error)
        if errors:
            if primary is None:
                primary = errors[0]
                instrument_failure_cleanup(serial, log, primary, None)
                for error in errors[1:]:
                    add_failure_note(
                        primary,
                        "Instrumentation finalization also failed: "
                        + type(error).__name__,
                    )
                raise primary
            for error in errors:
                add_failure_note(
                    primary,
                    "Instrumentation finalization also failed: " + type(error).__name__,
                )


def restore_environment(serial, network, original, saver, airplane, wifi, grants):
    return _environment["restore_environment"](
        serial,
        network,
        original,
        saver,
        airplane,
        wifi,
        grants,
        adb=adb,
        safe_target=safe_target,
        set_metered=set_metered,
        package_name=PACKAGE,
        sleep=time.sleep,
    )


def run(args, serial):
    avd = safe_target(serial)
    directory = args.output / serial
    directory.mkdir(mode=0o700)
    for apk in [args.app_apk, args.test_apk]:
        safe_target(serial)
        adb(serial, "install", "-r", "-t", apk)
    package = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout.decode()
    grants = {
        permission: bool(
            re.search(
                r"android\.permission\." + permission + r": granted=true", package
            )
        )
        for permission in PERMISSIONS
    }
    networks = (
        adb(serial, "shell", "cmd", "netpolicy", "list", "wifi-networks")
        .stdout.decode()
        .splitlines()
    )
    original = next(
        (
            line.rsplit(";", 1)[1]
            for line in networks
            if line.rsplit(";", 1)[0] == args.wifi_network
        ),
        None,
    )
    if original is None:
        raise RuntimeError(
            "The selected Wi-Fi network is not configured on this emulator"
        )
    original_saver = (
        b"enabled"
        in adb(serial, "shell", "cmd", "netpolicy", "get", "restrict-background").stdout
    )
    airplane = (
        adb(
            serial, "shell", "settings", "get", "global", "airplane_mode_on"
        ).stdout.strip()
        == b"1"
    )
    wifi = adb(serial, "shell", "settings", "get", "global", "wifi_on").stdout.strip()
    if wifi not in [b"0", b"1", b"2"]:
        raise RuntimeError("Could not determine the original Wi-Fi state")
    cellular = adb(
        serial, "shell", "settings", "get", "global", "mobile_data"
    ).stdout.strip()
    if cellular not in (b"0", b"1"):
        raise RuntimeError("Could not determine the original cellular state")
    (directory / "environment-before.json").write_text(
        json.dumps(
            {
                "grants": grants,
                "meteredOverride": original,
                "dataSaver": original_saver,
                "airplane": airplane,
                "wifi": wifi.decode(),
            },
            indent=2,
        )
    )
    try:
        adb(serial, "shell", "svc", "data", "disable")
        if getattr(args, "network_only", False):
            # Exercise the same radio round-trip as an offline first launch before
            # checking policy delivery on each managed replacement candidate.
            adb(serial, "shell", "svc", "wifi", "disable")
            adb(serial, "shell", "cmd", "connectivity", "airplane-mode", "enable")
        adb(serial, "shell", "cmd", "connectivity", "airplane-mode", "disable")
        adb(serial, "shell", "svc", "wifi", "enable")
        if args.wifi_network != "AndroidWifi":
            raise RuntimeError(
                "Network acceptance requires the managed emulator's open AndroidWifi fixture"
            )
        adb(
            serial, "shell", "cmd", "wifi", "connect-network", args.wifi_network, "open"
        )
        if getattr(args, "network_only", False):
            cases = run_network_cases(serial, args.wifi_network, directory)
            return {
                "serial": serial,
                "avd": avd,
                "tests": len(cases),
                "cases": cases,
                "networkPolicyOnly": True,
            }
        for permission in PERMISSIONS:
            result = adb(
                serial,
                "shell",
                "pm",
                "revoke",
                PACKAGE,
                "android.permission." + permission,
                check=False,
            )
            if (
                result.returncode
                and b"has not requested permission" not in result.stderr + result.stdout
            ):
                raise RuntimeError("Could not revoke a fixture permission")
        cases = []
        for method in [
            "aSeedProviderCollisionForColdRestore",
            "bQuarantinedOriginalSurvivesProcessRestart",
            "cPrivateOriginalWorksWithoutLibraryOrNotificationPermission",
            "eCorruptCanonicalOriginalsAreRepairedOnce",
            "partialNativeMediaRecognizesMissingOriginalAcrossReferenceSpellings",
        ]:
            if method == "bQuarantinedOriginalSurvivesProcessRestart":
                safe_target(serial)
                adb(serial, "shell", "am", "force-stop", PACKAGE)
            instrument(serial, TEST + "#" + method, directory / (method + ".log"))
            cases.append(method)
        cases.extend(run_network_cases(serial, args.wifi_network, directory))
        label = "permissions-" + uuid.uuid4().hex
        for mode in ["seed", "automatic", "verify"]:
            instrument(
                serial,
                "app.logdate.client.e2e.ArchiveMediaRecoveryE2ETest#archiveRecoveryAcrossProcessRestart",
                directory / ("archive-" + mode + ".log"),
                extras=[
                    "-e",
                    "archiveRecoveryFixtureLabel",
                    label,
                    "-e",
                    "archiveRecoveryMode",
                    mode,
                ],
            )
            cases.append("archive-" + mode)
            safe_target(serial)
            adb(serial, "shell", "am", "force-stop", PACKAGE)
    finally:
        try:
            restore_environment(
                serial,
                args.wifi_network,
                original,
                original_saver,
                airplane,
                wifi,
                grants,
            )
        finally:
            adb(
                serial,
                "shell",
                "svc",
                "data",
                "enable" if cellular == b"1" else "disable",
            )
            if (
                adb(
                    serial, "shell", "settings", "get", "global", "mobile_data"
                ).stdout.strip()
                != cellular
            ):
                raise RuntimeError(
                    "The owned fixture's cellular setting was not restored"
                )
    return {
        "serial": serial,
        "avd": avd,
        "tests": len(cases),
        "cases": cases,
        "originalNetworkOverride": original,
        "corruptOriginalRecoveryVerified": True,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emulator", action="append", required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--wifi-network", default="AndroidWifi")
    parser.add_argument(
        "--network-only",
        action="store_true",
        help="Run only the metered and Data Saver policy cases",
    )
    parser.add_argument(
        "--synthetic-data",
        action="store_true",
        required=True,
        help="Confirm these emulators contain only synthetic LogDate data",
    )
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(mode=0o700, parents=True)
    results = [run(args, serial) for serial in args.emulator]
    (args.output / "acceptance.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results))


if __name__ == "__main__":
    main()
