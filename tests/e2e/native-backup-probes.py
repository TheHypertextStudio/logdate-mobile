"""Native backup probes; dependencies are supplied by the entry point."""

import base64
import re
import shlex
import uuid
import xml.etree.ElementTree as ET


def probe(
    h,
    serial,
    mode,
    output,
    metadata=None,
    identity="phrase",
    missing_media_path=None,
    source_has_session=False,
    source_session_id=None,
    installation_trust=False,
    recording_transport="cloud",
    pending_camera_draft=False,
):
    arguments = [
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        h.PROBE,
        "-e",
        "nativeBackupMode",
        mode,
        "-e",
        "nativeIdentityMode",
        identity,
    ]
    if mode == "seed" and installation_trust is True:
        arguments.extend(("-e", "nativeInstallationTrustFixture", "true"))
    if metadata:
        for name, key in (
            ("sourceOwner", "owner"),
            ("sourceDevice", "device"),
            ("sourceFingerprint", "fingerprint"),
            ("sourceMediaNonce", "mediaNonce"),
            ("sourceMediaCiphertext", "mediaCiphertext"),
        ):
            value = metadata.get(key)
            if key == "mediaNonce" and not value:
                value = base64.b64encode(bytes(12)).decode()
            if value is None:
                raise ValueError(f"existing source fixture is missing {key}")
            arguments.extend(("-e", name, value))
        if metadata.get("recordingTransferFixture"):
            if recording_transport not in ("cloud", "d2d"):
                raise ValueError(
                    "recording probe requires a known Android backup transport"
                )
            arguments.extend(("-e", "sourceRecordingTransferFixture", "true"))
            arguments.extend(("-e", "sourceRecordingTransport", recording_transport))
        if metadata.get("installationTrustFixture") is True:
            arguments.extend(("-e", "sourceInstallationTrustFixture", "true"))
    if mode == "source-backup-integrity":
        arguments.extend(
            ("-e", "sourceHasSession", "true" if source_has_session else "false")
        )
    if pending_camera_draft:
        arguments.extend(("-e", "pendingCameraDraftFixture", "true"))
    if source_session_id is not None:
        arguments.extend(("-e", "sourceSessionId", source_session_id))
    if missing_media_path:
        arguments.extend(("-e", "expectedMissingMediaPath", missing_media_path))
    arguments.append(h.RUNNER)
    result = h.run_backup_probe(serial, output, *arguments)
    if not h.instrumentation_passed(result):
        failure = RuntimeError(f"native {mode} probe failed; inspect {output}")
        try:
            h.private_file(
                output.with_suffix(".logcat.log"),
                h.adb(serial, "logcat", "-d", "-b", "crash", "-v", "threadtime"),
            )
        except Exception as error:
            h.add_failure_note(
                failure, f"Crash diagnostics could not be collected: {error}"
            )
        if mode in {"data-only", "verify", "verify-restored", "partial-media"}:
            _capture_recovery_failure(h, serial, output, failure)
        raise failure


def _capture_recovery_failure(h, serial, output, failure):
    commands = (
        (
            "callbacks",
            (
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-s",
                "BackupServiceBinder:D",
                "AndroidRuntime:E",
            ),
        ),
        (
            "delivery",
            (
                "exec-out",
                "run-as",
                h.PACKAGE,
                "cat",
                "no_backup/.logdate_native_restore_privacy_received",
                "no_backup/.logdate_native_restore_pending",
            ),
        ),
        ("jobs", ("shell", "dumpsys", "jobscheduler")),
        (
            "custody",
            (
                "exec-out",
                "run-as",
                h.PACKAGE,
                "find",
                "no_backup/native-journal-replay-custody",
                "-type",
                "f",
                "-name",
                "entry.json",
                "-exec",
                "cat",
                "{}",
                "+",
            ),
        ),
        ("recovery-state", ("exec-out", "run-as", h.PACKAGE, "ls", "-la", "no_backup")),
    )
    for label, arguments in commands:
        try:
            h.private_file(
                output.with_suffix(f".{label}.log"),
                h.adb(serial, *arguments, timeout=20),
            )
        except Exception as error:
            h.add_failure_note(
                failure,
                f"{label} recovery diagnostics could not be collected: {type(error).__name__}",
            )


def verify_restarted_content(
    h, serial, output, metadata, identity, *, recording_transport="cloud"
):
    arguments = [
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        "app.logdate.client.e2e.NativeRetainedOfflineEditProbeTest",
        "-e",
        "nativeRetainedEdit",
        "true",
    ]
    if "archiveJournalIds" in metadata:
        journals = metadata["archiveJournalIds"]
        if (
            not isinstance(journals, list)
            or len(journals) != 2
            or not all(isinstance(value, str) for value in journals)
        ):
            raise ValueError("Archive restart requires exactly two seeded journal IDs")
        journals = [str(uuid.UUID(value)) for value in journals]
        base = {
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
        }
        if len(set(journals)) != 2 or base.intersection(journals):
            raise ValueError(
                "Archive restart journal IDs must be distinct from the transfer fixture"
            )
        arguments.extend(("-e", "nativeRetainedArchiveJournals", ",".join(journals)))
    result = h.adb(
        serial, *arguments, h.RUNNER, timeout=h.INSTRUMENTATION_TIMEOUT_SECONDS
    )
    log = output / "destination-retained-offline-edit.log"
    h.private_file(log, result)
    if not h.instrumentation_passed(result):
        raise RuntimeError(
            f"The retained offline edit did not pass read-only verification; inspect {log}"
        )
    h.probe(
        serial,
        "verify",
        output / "destination-restart.log",
        metadata,
        identity,
        recording_transport=recording_transport,
    )


def privacy_probe(h, serial, mode, output):
    result = h.run_backup_probe(
        serial,
        output,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        "app.logdate.client.e2e.NativeRestorePrivacyRuntimeTest",
        "-e",
        "restorePrivacyMode",
        mode,
        h.RUNNER,
    )
    if not h.instrumentation_passed(result):
        failure = RuntimeError(f"native privacy {mode} probe failed; inspect {output}")
        for suffix in ("png", "xml"):
            try:
                captured = h.adb(
                    serial,
                    "exec-out",
                    "run-as",
                    h.PACKAGE,
                    "cat",
                    "no_backup/native-privacy-failure." + suffix,
                    timeout=20,
                )
                valid = (
                    captured.startswith(b"\x89PNG\r\n\x1a\n")
                    if suffix == "png"
                    else ET.fromstring(captured).tag == "hierarchy"
                )
                if not valid:
                    raise ValueError("Native privacy screen capture is unavailable")
                h.private_file(output.with_suffix("." + suffix), captured)
            except Exception as error:
                h.add_failure_note(
                    failure,
                    f"Privacy {suffix} diagnostics unavailable: {type(error).__name__}",
                )
        _capture_recovery_failure(h, serial, output, failure)
        raise failure


def verify_privacy_backup_refusal(h, result, diagnostics, started_at):
    expected = f"Package {h.PACKAGE} with result: Transport rejected package because it wasn't able to process it at the time"
    if expected not in result.decode().splitlines():
        raise RuntimeError(
            "Android did not report the expected package backup rejection"
        )
    agents = {}
    for line in diagnostics.decode(errors="replace").splitlines():
        match = re.match(
            r"\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+[VDIWEF]\s+BackupServiceBinder\s*:\s*(.*)",
            line,
        )
        if match and float(match[1]) >= started_at:
            agents.setdefault(match[2], []).append(match[3])
    if not any(
        "onFullBackup" in "\n".join(lines)
        and "app.logdate.client.data.restore.NativeAccountBackupAgent"
        in "\n".join(lines)
        and "NativeRestorePrivacyMetadataRequiredException" in "\n".join(lines)
        and "NativeRestorePrivacyPolicy$prepareBackup" in "\n".join(lines)
        for lines in agents.values()
    ):
        raise RuntimeError(
            "The current backup did not reach the agent's missing-privacy refusal"
        )


def runtime_permission_grant(h, package_state, permission):
    requested = re.search(
        rf"^\s*{re.escape(permission)}\s*$",
        package_state,
        re.MULTILINE,
    )
    if requested is None:
        raise RuntimeError(f"Android did not report the runtime grant for {permission}")
    state = re.search(
        rf"^\s*{re.escape(permission)}: granted=(true|false)(?:, flags=\[[^\]]*\])?\s*$",
        package_state,
        re.MULTILINE,
    )
    if state is None:
        if re.search(rf"^\s*{re.escape(permission)}:", package_state, re.MULTILINE):
            raise RuntimeError(
                f"Android reported an invalid runtime grant for {permission}"
            )
        return False
    return state.group(1) == "true"


def recording_policy_probe(h, serial, output):
    permissions = ["android.permission.RECORD_AUDIO"]
    sdk = int(h.adb(serial, "shell", "getprop", "ro.build.version.sdk").strip())
    if sdk >= 33:
        permissions.append("android.permission.POST_NOTIFICATIONS")
    original = {}
    package_state = h.adb(serial, "shell", "dumpsys", "package", h.PACKAGE).decode()
    for permission in permissions:
        original[permission] = h.runtime_permission_grant(package_state, permission)
    probe_error = None
    try:
        h.probe(serial, "recording-policy", output)
    except BaseException as error:
        probe_error = error
    finally:
        cleanup_errors = []
        for permission, was_granted in original.items():
            try:
                current_state = h.adb(
                    serial, "shell", "dumpsys", "package", h.PACKAGE
                ).decode()
                current_grant = h.runtime_permission_grant(current_state, permission)
                if current_grant != was_granted:
                    action = "grant" if was_granted else "revoke"
                    h.adb(
                        serial,
                        "shell",
                        "pm",
                        action,
                        "--user",
                        "0",
                        h.PACKAGE,
                        permission,
                    )
                restored_state = h.adb(
                    serial, "shell", "dumpsys", "package", h.PACKAGE
                ).decode()
                if (
                    h.runtime_permission_grant(restored_state, permission)
                    != was_granted
                ):
                    raise RuntimeError(
                        f"Android did not restore the original runtime grant for {permission}"
                    )
            except Exception as error:
                cleanup_errors.append(f"{permission}: {error}")
        if probe_error is not None:
            for error in cleanup_errors:
                h.add_failure_note(
                    probe_error, f"Runtime permission cleanup also failed: {error}"
                )
            raise probe_error
        if cleanup_errors:
            raise RuntimeError(
                "Could not restore recording probe permissions: "
                + "; ".join(cleanup_errors)
            )


def archive_probe(h, serial, mode, output, fixture=None, fixture_label="transfer"):
    arguments = [
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        "app.logdate.client.e2e.ArchiveMediaRecoveryE2ETest",
        "-e",
        "archiveRecoveryMode",
        mode,
        "-e",
        "archiveRecoveryFixtureLabel",
        fixture_label,
    ]
    if fixture:
        arguments.extend(("-e", "archiveRecoveryFixture", fixture))
    arguments.append(h.RUNNER)
    result = h.run_backup_probe(serial, output, "shell", shlex.join(arguments))
    if not h.instrumentation_passed(result):
        raise RuntimeError(f"archive {mode} probe failed; inspect {output}")


FUNCTIONS = {
    "probe": probe,
    "verify_restarted_content": verify_restarted_content,
    "privacy_probe": privacy_probe,
    "verify_privacy_backup_refusal": verify_privacy_backup_refusal,
    "runtime_permission_grant": runtime_permission_grant,
    "recording_policy_probe": recording_policy_probe,
    "archive_probe": archive_probe,
}
