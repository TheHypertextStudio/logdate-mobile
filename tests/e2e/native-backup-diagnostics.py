"""Bounded, private failure evidence; never changes an acceptance result."""

import hashlib
import json
import re
import subprocess
import time

MAX_RECORD = 262144
RECORD = re.compile(r"files/archive_recovery/records/[a-f0-9-]{36}\.json")


def capture_archive_records(h, serial, snapshot):
    result = {}
    paths = sorted(path for path in snapshot if RECORD.fullmatch(path))[:8]
    for path in paths:
        try:
            if snapshot[path].get("bytes", MAX_RECORD + 1) > MAX_RECORD:
                raise ValueError("Recovery diagnostic record exceeds its bound")
            raw = h.adb(
                serial,
                "exec-out",
                "run-as",
                h.PACKAGE,
                "head",
                "-c",
                str(MAX_RECORD + 1),
                path,
                timeout=20,
            )
            if len(raw) > MAX_RECORD:
                raise ValueError("Recovery diagnostic record grew beyond its bound")
            result[path] = {
                "data": json.loads(raw),
                "snapshotSha256": snapshot[path].get("sha256"),
                "readSha256": hashlib.sha256(raw).hexdigest(),
            }
        except Exception as error:
            result[path] = {"diagnosticError": type(error).__name__}
    return result


def _description(value):
    if isinstance(value, bool) or value is None:
        return {"type": type(value).__name__, "value": value}
    raw = json.dumps(value, sort_keys=True).encode()
    return {"type": type(value).__name__, "sha256": hashlib.sha256(raw).hexdigest()}


def _changes(before, after, field=""):
    if isinstance(before, dict) and isinstance(after, dict):
        result = []
        for key in sorted(before.keys() | after.keys()):
            name = (
                key
                if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", key)
                else hashlib.sha256(key.encode()).hexdigest()
            )
            result += _changes(before.get(key), after.get(key), field + "/" + name)
        return result
    if before == after:
        return []
    return [
        {"field": field, "before": _description(before), "after": _description(after)}
    ]


def record_archive_changes(h, output, before, after):
    if not before and not after:
        return
    records = []
    for path in sorted(before.keys() | after.keys()):
        old, new = before.get(path, {}), after.get(path, {})
        records.append(
            {
                "path": path,
                "beforeAligned": bool(old.get("readSha256"))
                and old.get("snapshotSha256") == old["readSha256"],
                "afterAligned": bool(new.get("readSha256"))
                and new.get("snapshotSha256") == new["readSha256"],
                "changes": _changes(old.get("data"), new.get("data")),
                "readErrors": [
                    item["diagnosticError"]
                    for item in (old, new)
                    if "diagnosticError" in item
                ],
            }
        )
    h.private_file(
        output.with_suffix(".archive-changes.json"),
        json.dumps(records, indent=2).encode(),
    )


def _bounded_startup_output(raw):
    if isinstance(raw, str):
        raw = raw.encode()
    raw = raw or b""
    limit = 512 * 1024
    if len(raw) <= limit:
        return raw
    marker = f"\n[diagnostic truncated; original bytes={len(raw)}]\n".encode()
    head = (limit - len(marker)) // 2
    return raw[:head] + marker + raw[-(limit - len(marker) - head) :]


def run_backup_probe(h, serial, output, *arguments):
    try:
        result = h.adb(serial, *arguments, timeout=h.INSTRUMENTATION_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as failure:
        try:
            h.private_file(output, _bounded_startup_output(failure.stdout))
            if failure.stderr is not None:
                h.private_file(
                    output.with_suffix(".stderr.log"),
                    _bounded_startup_output(failure.stderr),
                )
            h.private_file(
                output.with_suffix(".timeout.json"),
                json.dumps(
                    {
                        "timeoutSeconds": failure.timeout,
                        "stdoutBytes": len(failure.stdout or b""),
                        "stderrBytes": len(failure.stderr or b""),
                        "acceptancePassed": False,
                    }
                ).encode(),
            )
        except Exception as diagnostic:
            h.add_failure_note(
                failure,
                "Partial instrumentation evidence unavailable: "
                + type(diagnostic).__name__,
            )
        # Caller cleanup starts another invocation of the same test method.
        # Preserve this phase before UTP replaces its shared method logcat.
        try:
            data = h.adb(
                serial,
                "logcat",
                "-d",
                "-t",
                "4000",
                "-v",
                "threadtime",
                "-s",
                "NativeTransferProbeTrace:D",
                "NativeCurrentDraftTrace:D",
                "TestRunner:I",
                "AndroidRuntime:E",
                timeout=20,
            )
            h.private_file(
                output.with_suffix(".timeout.logcat.log"),
                _bounded_startup_output(data),
            )
        except Exception as diagnostic:
            h.add_failure_note(
                failure,
                "Timed-out probe logcat unavailable: " + type(diagnostic).__name__,
            )
        raise
    h.private_file(output, result)
    return result


def capture_backup_failure(h, serial, output, failure):
    started = time.monotonic()
    try:
        data = h.adb(
            serial,
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-s",
            "BackupServiceBinder:D",
            "BackupAgent:D",
            "BackupManagerService:D",
            "AndroidRuntime:E",
            timeout=20,
        )
        h.private_file(output.with_suffix(".agent.log"), data)
    except Exception as error:
        h.add_failure_note(
            failure, "Backup failure diagnostics unavailable: " + type(error).__name__
        )

    commands = (
        (".activity.log", ("shell", "dumpsys", "activity", "activities")),
        (".png", ("exec-out", "screencap", "-p")),
        (
            ".startup.log",
            (
                "logcat",
                "-d",
                "-t",
                "1000",
                "-v",
                "threadtime",
                "-s",
                "LogDateAppLaunch:D",
                "ApplicationStartup:D",
                "BackupAgent:D",
                "AndroidRuntime:E",
            ),
        ),
    )
    for suffix, command in commands:
        remaining = 20 - (time.monotonic() - started)
        if remaining <= 0:
            h.add_failure_note(
                failure,
                "Backup startup evidence exhausted the existing 20-second diagnostic budget",
            )
            return
        try:
            data = h.adb(serial, *command, timeout=remaining)
            if suffix == ".png":
                if (
                    not data.startswith(b"\x89PNG\r\n\x1a\n")
                    or len(data) > 8 * 1024 * 1024
                ):
                    raise ValueError(
                        "Native backup screen capture unavailable or oversized"
                    )
            else:
                data = _bounded_startup_output(data)
            h.private_file(output.with_suffix(suffix), data)
        except Exception as error:
            h.add_failure_note(
                failure,
                "Backup startup " + suffix + " unavailable: " + type(error).__name__,
            )


FUNCTIONS = {
    "capture_archive_records": capture_archive_records,
    "record_archive_changes": record_archive_changes,
    "capture_backup_failure": capture_backup_failure,
    "run_backup_probe": run_backup_probe,
}
