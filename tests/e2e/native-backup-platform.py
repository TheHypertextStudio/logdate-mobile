"""Native backup platform; dependencies are supplied by the entry point."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import time


def emulator_serial(h, value):
    if not re.fullmatch(r"emulator-[0-9]+", value):
        raise argparse.ArgumentTypeError("an explicit emulator-NNNN serial is required")
    return value


def adb(h, serial, *arguments, timeout=180, input_bytes=None):
    h.emulator_serial(serial)
    started_ns = time.monotonic_ns()
    try:
        return subprocess.run(
            h.managed_adb_command(serial, arguments),
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=True,
        ).stdout
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        failed_ns = time.monotonic_ns()
        stderr = error.stderr or b""
        if isinstance(stderr, bytes):
            stderr = stderr.decode(errors="replace")
        h.add_failure_note(
            error,
            f"Managed ADB failure: start_monotonic_ns={started_ns}, failure_monotonic_ns={failed_ns}, "
            f"failure_unix_ns={time.time_ns()}, stderr={stderr[:4096]}",
        )
        raise


def wait_for_package_manager(h, serial, attempts=60):
    for attempt in range(attempts):
        try:
            h.adb(serial, "shell", "pm", "list", "packages", timeout=10)
            return
        except subprocess.CalledProcessError as error:
            if attempt + 1 == attempts:
                raise RuntimeError(
                    "Android Package Manager did not become ready"
                ) from error
            time.sleep(1)


def private_file(h, path, contents):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(contents)


def overwrite_private_file(h, path, contents):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(contents)
    os.chmod(path, 0o600)


def package_file_snapshot(h, serial):
    """Hash all retained journal, media, preference, and recovery files without exposing contents."""
    snapshot = {}
    for path in h.package_user_files(serial):
        if not h.is_user_content_path(path):
            continue
        path = path.decode().removeprefix("./")
        contents = h.adb(serial, "exec-out", "run-as", h.PACKAGE, "cat", path)
        if path == "files/logdate.preferences_pb":
            contents = h.canonical_preferences(contents)
        snapshot[path] = {
            "bytes": len(contents),
            "sha256": hashlib.sha256(contents).hexdigest(),
        }
    return snapshot


def assert_snapshot_preserved(
    h, before, after, *, require_database=True, ignored_paths=()
):
    def is_volatile(path):
        if path in {
            "databases/logdate-wal",
            "databases/logdate-shm",
            "files/logdate.preferences_pb.tmp",
            # Firebase Sessions persists process IDs/background times independently of journal backup.
            "files/datastore/firebaseSessions/sessionDataStore.data",
        } or path.startswith(h.RUNTIME_ONLY_SNAPSHOT_PREFIXES):
            return True
        return path.startswith("files/datastore/firebaseSessions/") and path.endswith(
            ".tmp"
        )

    ignored_paths = set(ignored_paths)
    before_durable = {
        path: value
        for path, value in before.items()
        if not is_volatile(path) and path not in ignored_paths
    }
    after_durable = {
        path: value
        for path, value in after.items()
        if not is_volatile(path) and path not in ignored_paths
    }
    if require_database and before.get("databases/logdate") is None:
        raise RuntimeError("snapshot has no journal database to preserve")
    if before_durable != after_durable:
        changed_paths = sorted(
            path
            for path in before_durable.keys() | after_durable.keys()
            if before_durable.get(path) != after_durable.get(path)
        )
        raise RuntimeError("snapshot changed durable data: " + ", ".join(changed_paths))


def enable_backup(h, serial, attempts=3):
    state = b""
    for attempt in range(attempts):
        h.adb(serial, "shell", "bmgr", "enable", "true")
        state = h.adb(serial, "shell", "bmgr", "enabled")
        if b"Backup Manager currently enabled" in state:
            return
        if attempt + 1 < attempts:
            time.sleep(1)
    raise RuntimeError(
        "Android backup service remained disabled after enable request: "
        + state.decode().strip()
    )


def backup_source(
    h, serial, output, *, verify_after_backup=None, prepare_for_snapshot=None
):
    """Back up a retained fixture and prove Android's backup did not change it."""
    launch = h.adb(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        h.PACKAGE + "/app.logdate.client.MainActivity",
    )
    h.private_file(output.with_name("source-launch-before-native-backup.log"), launch)
    if b"Status: ok" not in launch:
        if b"Status: timeout" not in launch:
            raise RuntimeError("The source app did not start before native backup")
    if b"Status: ok" not in launch or prepare_for_snapshot is not None:
        h.wait_for_timeline(serial, output.parent, timeout_seconds=120, prefix="source")
    if prepare_for_snapshot is not None:
        prepare_for_snapshot()
    before = h.package_file_snapshot(serial)
    archive_before = h.capture_archive_records(serial, before)
    preference_path = "files/logdate.preferences_pb"
    original_preferences = (
        h.adb(serial, "exec-out", "run-as", h.PACKAGE, "cat", preference_path)
        if preference_path in before
        else b""
    )
    if before.get("databases/logdate") is None:
        raise RuntimeError("The synthetic source has no database to back up")
    h.enable_backup(serial)
    # A managed device can inherit a prior LocalTransport dataset even when its app fixture is
    # fresh. Force a complete package snapshot so the nested archive belongs to this source.
    backup = h.adb(
        serial, "shell", "bmgr", "backupnow", "@pm@", h.PACKAGE, "--non-incremental"
    )
    h.private_file(output, backup)
    if f"Package {h.PACKAGE} with result: Success".encode() not in backup:
        try:
            diagnostics = h.adb(serial, "shell", "dumpsys", "backup", timeout=60)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            diagnostics = f"Backup service diagnostics unavailable: {type(error).__name__}".encode()
        diagnostic_path = output.with_name("backup-platform-diagnostics.log")
        h.private_file(diagnostic_path, diagnostics)
        failure = RuntimeError(
            f"Android did not complete the native backup; inspect {diagnostic_path}"
        )
        h.capture_backup_failure(serial, output, failure)
        raise failure
    after = h.package_file_snapshot(serial)
    archive_after = h.capture_archive_records(serial, after)
    h.private_file(
        output.with_name("source-files-before-native-backup.json"),
        json.dumps(before, indent=2).encode(),
    )
    h.private_file(
        output.with_name("source-files-after-native-backup.json"),
        json.dumps(after, indent=2).encode(),
    )
    if before.get(preference_path) != after.get(preference_path):
        prepared_preferences = h.adb(
            serial, "exec-out", "run-as", h.PACKAGE, "cat", preference_path
        )
        if h.explicit_backup_privacy_defaults(
            original_preferences
        ) != h.canonical_preferences(prepared_preferences):
            h.private_file(
                output.with_name("source-preference-changes.json"),
                json.dumps(
                    h.preference_changes(original_preferences, prepared_preferences),
                    indent=2,
                ).encode(),
            )
            raise RuntimeError(
                "native backup changed preferences beyond explicit missing privacy defaults"
            )
        before = {**before, preference_path: after[preference_path]}
    if verify_after_backup is not None:
        h.adb(serial, "shell", "am", "force-stop", h.PACKAGE)
        verify_after_backup()
    try:
        h.assert_snapshot_preserved(
            before,
            after,
            # SQLCipher may checkpoint the live WAL while the agent selects an encrypted database
            # snapshot. The post-backup probe verifies the source journal and original media logically.
            ignored_paths={
                "shared_prefs/logdate_secure_storage.xml",
                "databases/logdate",
            }
            if verify_after_backup is not None
            else (),
        )
    except Exception as failure:
        try:
            h.record_archive_changes(output, archive_before, archive_after)
        except Exception as error:
            h.add_failure_note(
                failure,
                "Archive checkpoint diagnostics unavailable: " + type(error).__name__,
            )
        raise
    return backup, before


def select_local_transport(h, serial, attempts=30):
    registered = None
    selection = None
    selected = None
    for attempt in range(attempts):
        try:
            registered = (
                h.adb(serial, "shell", "bmgr", "list", "transports", timeout=60)
                .decode()
                .splitlines()
            )
            if any(line.lstrip("* ").strip() == h.TRANSPORT for line in registered):
                selection = (
                    h.adb(serial, "shell", "bmgr", "transport", h.TRANSPORT, timeout=60)
                    .decode()
                    .strip()
                )
                selected = (
                    h.adb(serial, "shell", "bmgr", "list", "transports", timeout=60)
                    .decode()
                    .splitlines()
                )
                if any(line.strip() == "* " + h.TRANSPORT for line in selected):
                    return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            if attempt + 1 == attempts:
                raise RuntimeError(
                    "Android local backup transport could not be selected"
                ) from error
        if attempt + 1 < attempts:
            time.sleep(1)
    raise RuntimeError(
        "Android's local backup transport did not become selected; "
        f"registered={registered!r}, selection={selection!r}, selected={selected!r}"
    )


def ensure_emulator_setup_complete(h, serial):
    setting = ("shell", "settings", "get", "secure", "user_setup_complete")
    if h.adb(serial, *setting).strip() == b"1":
        return
    h.adb(serial, "shell", "settings", "put", "secure", "user_setup_complete", "1")
    if h.adb(serial, *setting).strip() != b"1":
        raise RuntimeError("Android setup is incomplete; native backup is unavailable")


def prepare(
    h, serial, allow_existing=False, allow_preinstalled_empty=False, cloud_backup=False
):
    if h.adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise RuntimeError("target did not identify itself as an emulator")
    h.wait_for_package_manager(serial)
    existing = (
        h.adb(serial, "shell", "pm", "list", "packages", h.PACKAGE)
        .decode()
        .splitlines()
    )
    installed = "package:" + h.PACKAGE in existing
    if not allow_existing and not allow_preinstalled_empty and installed:
        raise RuntimeError(
            "use a fresh emulator; this harness never clears or uninstalls LogDate"
        )
    if allow_existing and "package:" + h.PACKAGE not in existing:
        raise RuntimeError(
            "resume requires the synthetic source application to remain installed"
        )
    if allow_preinstalled_empty and installed:
        files = h.package_user_files(serial)
        if h.contains_user_content(files):
            raise RuntimeError(
                "preinstalled emulator contains retained data; use an empty emulator"
            )
    h.adb(serial, "root")
    h.adb(serial, "wait-for-device")
    if h.adb(serial, "shell", "id", "-u").strip() != b"0":
        raise RuntimeError(
            "a rootable Google APIs emulator is required for the local transport"
        )
    h.ensure_emulator_setup_complete(serial)
    h.adb(serial, "shell", "bmgr", "enable", "true")
    h.select_local_transport(serial)
    h.adb(
        serial,
        "shell",
        "settings",
        "put",
        "secure",
        "backup_local_transport_parameters",
        "is_encrypted=true,is_device_transfer=false"
        if cloud_backup
        else "is_encrypted=true,is_device_transfer=true",
    )


def radio_state(h, serial):
    airplane = (
        h.adb(serial, "shell", "settings", "get", "global", "airplane_mode_on").strip()
        == b"1"
    )
    wifi = h.adb(serial, "shell", "settings", "get", "global", "wifi_on").strip()
    if wifi not in (b"0", b"1", b"2"):
        raise RuntimeError("Android Wi-Fi state could not be read")
    return airplane, wifi


def set_offline(h, serial, offline):
    h.adb(
        serial,
        "shell",
        "cmd",
        "connectivity",
        "airplane-mode",
        "enable" if offline else "disable",
    )
    h.adb(serial, "shell", "svc", "wifi", "disable" if offline else "enable")


def restore_radio_state(h, serial, original):
    airplane, wifi = original
    failures = []
    for label, command in (
        (
            "airplane mode",
            (
                "shell",
                "cmd",
                "connectivity",
                "airplane-mode",
                "enable" if airplane else "disable",
            ),
        ),
        ("Wi-Fi", ("shell", "svc", "wifi", "disable" if wifi == b"0" else "enable")),
    ):
        try:
            h.adb(serial, *command)
        except Exception:
            failures.append(label)

    for _ in range(20):
        try:
            if h.radio_state(serial) == original:
                return
        except Exception:
            failures.append("radio state readback")
        time.sleep(0.25)
    detail = ", ".join(dict.fromkeys(failures)) or "radio state mismatch"
    raise RuntimeError(f"Could not restore emulator radio state: {detail}")


def package_user_files(h, serial, package=None):
    package = h.PACKAGE if package is None else package
    """List installed-app files, treating an absent package data directory as empty."""
    try:
        return h.adb(
            serial, "shell", "run-as", package, "find", ".", "-type", "f"
        ).splitlines()
    except subprocess.CalledProcessError as error:
        missing_directory = (
            f"couldn't stat /data/user/0/{package}: No such file or directory".encode()
        )
        if missing_directory in (error.stderr or b""):
            return []
        raise


FUNCTIONS = {
    "package_user_files": package_user_files,
    "emulator_serial": emulator_serial,
    "adb": adb,
    "wait_for_package_manager": wait_for_package_manager,
    "private_file": private_file,
    "overwrite_private_file": overwrite_private_file,
    "package_file_snapshot": package_file_snapshot,
    "assert_snapshot_preserved": assert_snapshot_preserved,
    "enable_backup": enable_backup,
    "backup_source": backup_source,
    "select_local_transport": select_local_transport,
    "ensure_emulator_setup_complete": ensure_emulator_setup_complete,
    "prepare": prepare,
    "radio_state": radio_state,
    "set_offline": set_offline,
    "restore_radio_state": restore_radio_state,
}


USER_CONTENT_PATHS = (
    "./shared_prefs/logdate_secure_storage.xml",
    "./databases/logdate",
    "./databases/logdate-wal",
    "./databases/logdate-shm",
    "./files/media/",
    "./files/user_media/",
    "./files/audio_notes/",
    "./files/camera-captures/",
    "./files/db_recovery/",
    "./files/datastore/",
    "./files/archive_recovery/records/",
    "./files/archive_recovery/sources/",
    "./files/identity_recovery/",
    "./files/logdate.preferences_pb",
    "./shared_prefs/logdate_widget_instances.xml",
)
