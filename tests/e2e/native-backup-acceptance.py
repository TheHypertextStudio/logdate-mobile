#!/usr/bin/env python3
"""Exercise Android's debug backup transport between two fresh, rootable emulators.

Android restores application files from the source transport's payload. Fixtures
cover phrase-derived and account-unlocked encryption identities.
"""

import hashlib as hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import tarfile as tarfile
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


PACKAGE = os.environ.get(
    "LOGDATE_NATIVE_ACCEPTANCE_PACKAGE", "studio.hypertext.logdate.debug"
)
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PROBE = "app.logdate.client.e2e.NativeBackupTransferProbeTest"
TRANSPORT = "com.android.localtransport/.LocalTransport"
TRANSPORT_FILES = "/data/user/0/com.android.localtransport/files"
# Managed guests can spend several minutes compiling the first instrumentation APK.
INSTRUMENTATION_TIMEOUT_SECONDS = 600
RUNTIME_ONLY_PREFERENCES = {"android_platform_search_index_generation"}
RUNTIME_ONLY_SNAPSHOT_PREFIXES = ("files/datastore/FirebaseHeartBeat",)


from functools import wraps


class _HarnessDependencies:
    """Keep one explicit dependency namespace for CLI use and isolated host fixtures."""

    def __init__(self, namespace):
        self.namespace = namespace

    def __getattr__(self, name):
        return self.namespace[name]


_dependencies = _HarnessDependencies(globals())


def _bind(implementation):
    @wraps(implementation)
    def invoke(*args, **kwargs):
        return implementation(_dependencies, *args, **kwargs)

    return invoke


for _concern in (
    "preferences",
    "payloads",
    "platform",
    "system-ui",
    "probes",
    "privacy",
    "source",
    "sequences",
    "diagnostics",
):
    _helpers = runpy.run_path(
        str(Path(__file__).with_name(f"native-backup-{_concern}.py"))
    )
    if _concern == "platform":
        USER_CONTENT_PATHS = _helpers["USER_CONTENT_PATHS"]
    globals().update(
        {
            name: _bind(implementation)
            for name, implementation in _helpers["FUNCTIONS"].items()
        }
    )


def transfer(
    source,
    destination,
    app_apk,
    test_apk,
    output,
    identity,
    exercise_archive_recovery=False,
    exercise_missing_keys=False,
    exercise_credentials_only=False,
    seed_source=True,
    snapshot_source=False,
    exercise_duplicate_restore=False,
    exercise_partial_media=False,
    exercise_data_only=False,
    exercise_missing_privacy=False,
    exercise_system_widgets=False,
    exercise_cloud_backup=False,
    exercise_pending_camera_draft=False,
):
    cloud_selection = (
        exercise_credentials_only or exercise_cloud_backup or exercise_missing_privacy
    )
    recording_transport = "cloud" if cloud_selection else "d2d"
    prepare_source(
        source,
        app_apk,
        test_apk,
        output,
        identity,
        seed_source,
        snapshot_source,
        exercise_pending_camera_draft,
    )
    # The backup agent's file-based restore mode is a manifest contract. Update a resumed fixture
    # without clearing its synthetic content so the source actually writes that format.
    adb(source, "install", "-r", "-t", str(app_apk))
    raw_metadata = adb(
        source,
        "exec-out",
        "cat",
        f"/data/user/0/{PACKAGE}/no_backup/native-backup-fixture.json",
    )
    private_file(output / "source-fixture.json", raw_metadata)
    metadata = json.loads(raw_metadata)
    if exercise_cloud_backup and metadata.get("recordingTransferFixture") is not True:
        raise RuntimeError(
            "Full cloud recovery requires the complete synthetic recording and media fixture"
        )
    exercise_duplicate_restore = (
        exercise_duplicate_restore
        or exercise_cloud_backup
        or exercise_pending_camera_draft
    )
    archive_fixture = None
    source_preparation = {}
    archive_fixture_label = "transfer-" + uuid.uuid4().hex
    if exercise_archive_recovery:
        archive_probe(
            source,
            "seed",
            output / "archive-source-seed.log",
            fixture_label=archive_fixture_label,
        )
        archive_fixture = adb(
            source,
            "exec-out",
            "cat",
            f"/data/user/0/{PACKAGE}/no_backup/archive-media-recovery-{archive_fixture_label}.json",
        )
        private_file(output / "archive-source-fixture.json", archive_fixture)
        archive_state = json.loads(archive_fixture)
        metadata["archiveJournalIds"] = [
            archive_state["journal"],
            archive_state["extraJournal"],
        ]
        source_preparation["prepare_for_snapshot"] = lambda: archive_probe(
            source,
            "source-waiting",
            output / "archive-source-waiting.log",
            fixture_label=archive_fixture_label,
        )
    with data_only_source_session(
        source, output, exercise_data_only
    ) as source_session_id:
        validate_source_integrity = lambda evidence_output=output: probe(
            source,
            "source-backup-integrity",
            evidence_output / "source-backup-integrity.log",
            metadata,
            identity,
            source_has_session=exercise_data_only,
            source_session_id=source_session_id,
        )
        backup, source_files = backup_source(
            source,
            output / "native-backup.log",
            verify_after_backup=validate_source_integrity,
            **source_preparation,
        )
    payload = adb(source, "exec-out", "tar", "-C", TRANSPORT_FILES, "-cf", "-", "1")
    original_payload = payload
    private_file(output / "native-transport-original.tar", original_payload)
    if cloud_selection:
        assert_cloud_backup_contents(original_payload)
    if exercise_credentials_only:
        selected_source_files = source_files
    else:
        selected_source_files = {
            **source_files,
            "databases/logdate": selected_database_snapshot(
                original_payload, cloud_backup=cloud_selection
            ),
        }
    missing_media_path = None
    if exercise_missing_keys:
        payload = without_portable_recovery_keys(payload)
    elif exercise_credentials_only:
        payload = credentials_only(payload)
    elif exercise_partial_media:
        payload, missing_media_path = without_one_native_media_file(payload)
        private_file(
            output / "omitted-native-media-path.txt", missing_media_path.encode()
        )
    elif exercise_missing_privacy:
        (output / "native-transport-original.tar").rename(
            output / "native-transport-initial.tar"
        )
        payload, selected_source_files, original_payload = (
            capture_missing_privacy_payload(
                source, destination, output, validate_source_integrity
            )
        )
        private_file(output / "native-transport-original.tar", original_payload)
    private_file(output / "native-transport.tar", payload)
    print("Restoring through Android's destination backup service", flush=True)
    adb(
        destination,
        "push",
        str(output / "native-transport.tar"),
        "/data/local/tmp/logdate-native-transport.tar",
    )
    adb(
        destination,
        "shell",
        "tar",
        "-C",
        TRANSPORT_FILES,
        "-xf",
        "/data/local/tmp/logdate-native-transport.tar",
    )
    adb(destination, "shell", "chown", "-R", "system:system", TRANSPORT_FILES + "/1")
    adb(destination, "shell", "restorecon", "-R", TRANSPORT_FILES + "/1")
    # Some clean destination AVDs already have the debug package installed but
    # have never created app data. Updating keeps that safe test slot reusable.
    adb(destination, "install", "-r", "-t", str(app_apk))
    restored = restore_transport(destination, system_widgets=exercise_system_widgets)
    private_file(output / "native-restore.log", restored)
    if b"restoreFinished: 0" not in restored:
        raise RuntimeError("Android did not complete the native restore")
    if exercise_system_widgets:
        capture_widget_restore_delivery(destination, output / "widget-delivery-initial")
    if exercise_pending_camera_draft:
        # Establish exact database delivery before app startup; the following reconciliation
        # legitimately applies the transferred draft and may migrate the destination database.
        verify_restored_snapshot(
            package_file_snapshot(destination),
            selected_source_files,
            credentials_only=False,
        )
    if exercise_duplicate_restore:
        if exercise_pending_camera_draft:
            print(
                "Starting the restored app so retained journal reconciliation can finish before replay",
                flush=True,
            )
            launch = adb(
                destination,
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                PACKAGE + "/app.logdate.client.MainActivity",
            )
            private_file(output / "destination-pre-replay-launch.log", launch)
            adb(destination, "install", "-r", "-t", str(test_apk))
            probe(
                destination,
                "verify",
                output / "destination-pre-replay-camera-probe.log",
                metadata,
                identity,
                pending_camera_draft=True,
                recording_transport=recording_transport,
            )
        before_replay = package_file_snapshot(destination)
        replay = restore_transport(destination, system_widgets=exercise_system_widgets)
        private_file(output / "native-restore-replay.log", replay)
        if b"restoreFinished: 0" not in replay:
            raise RuntimeError("Android did not complete the replayed native restore")
        if exercise_system_widgets:
            capture_widget_restore_delivery(
                destination, output / "widget-delivery-replay"
            )
        assert_snapshot_preserved(
            before_replay,
            package_file_snapshot(destination),
            require_database=not exercise_credentials_only,
            # Widget IDs are newly allocated by each full system restore; the widget
            # acceptance probe checks every retained choice against the real binding.
            ignored_paths={"shared_prefs/logdate_widget_instances.xml"}
            if exercise_system_widgets
            else (),
        )
    restored_files = package_file_snapshot(destination)
    private_file(
        output / "source-files-before-backup.json",
        json.dumps(source_files, indent=2).encode(),
    )
    private_file(
        output / "destination-files-after-restore.json",
        json.dumps(restored_files, indent=2).encode(),
    )
    if exercise_cloud_backup and contains_media_files(restored_files):
        raise RuntimeError("Cloud restore unexpectedly delivered original media")
    if exercise_missing_keys:
        verify_missing_keys_snapshot(restored_files)
    else:
        verify_restored_snapshot(
            restored_files,
            selected_source_files,
            credentials_only=exercise_credentials_only,
            allow_database_changes=exercise_pending_camera_draft
            and exercise_duplicate_restore,
        )
    print("Launching the restored app through its normal startup path", flush=True)
    launch = adb(
        destination,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        PACKAGE + "/app.logdate.client.MainActivity",
    )
    private_file(output / "destination-first-launch.log", launch)
    adb(destination, "install", "-r", "-t", str(test_apk))
    if exercise_missing_privacy:
        verify_privacy_recovery(
            destination, output, selected_source_files, original_payload
        )
        return
    if exercise_missing_keys:
        print(
            "Verifying an incomplete native journal stays unavailable for recovery",
            flush=True,
        )
        probe(
            destination,
            "missing-keys-rejected",
            output / "destination-missing-keys.log",
        )
        print(
            "Incomplete journal remained unavailable and recovery state persisted",
            flush=True,
        )
        return
    if exercise_credentials_only:
        print(
            "Verifying restored credentials do not synthesize or replace journal content",
            flush=True,
        )
        probe(
            destination,
            "credentials-only",
            output / "destination-credentials-only.log",
            metadata,
            identity,
        )
        return
    if exercise_partial_media:
        print(
            "Verifying a partial native media delivery remains recoverable", flush=True
        )
        probe(
            destination,
            "partial-media",
            output / "destination-partial-media.log",
            metadata,
            identity,
            missing_media_path,
            recording_transport=recording_transport,
        )
        return
    print("Verifying original media, identity, drafts, and offline editing", flush=True)
    verify_mode = "data-only" if exercise_data_only else "verify"
    if exercise_duplicate_restore and metadata.get("installationTrustFixture") is True:
        # Establish only installation trust before the final full database delivery.
        # The ordinary editing probe must run afterward, before the read-only restart oracle.
        probe(
            destination,
            "verify-restored",
            output / "destination-before-trust-replay.log",
            metadata,
            identity,
            recording_transport=recording_transport,
        )
        replay_destination_trust(
            destination,
            output,
            metadata,
            identity,
            system_widgets=exercise_system_widgets,
            recording_transport=recording_transport,
        )
    probe(
        destination,
        verify_mode,
        output / f"destination-{verify_mode}.log",
        metadata,
        identity,
        pending_camera_draft=exercise_pending_camera_draft,
        recording_transport=recording_transport,
    )
    original_radio = radio_state(destination)
    try:
        adb(destination, "shell", "am", "force-stop", PACKAGE)
        set_offline(destination, True)
        verify_restarted_content(
            destination,
            output,
            metadata,
            identity,
            recording_transport=recording_transport,
        )
        if archive_fixture:
            archive_probe(
                destination,
                "repair",
                output / "archive-destination-repair.log",
                archive_fixture.decode(),
                fixture_label=archive_fixture_label,
            )
            adb(destination, "shell", "am", "force-stop", PACKAGE)
            archive_probe(
                destination,
                "verify",
                output / "archive-destination-restart.log",
                fixture_label=archive_fixture_label,
            )
        launch = adb(
            destination,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            PACKAGE + "/app.logdate.client.MainActivity",
        )
        private_file(output / "destination-launch.log", launch)
        wait_for_timeline(destination, output)
    finally:
        restore_radio_state(destination, original_radio)
    print("Native transport and offline restart checks passed", flush=True)


if __name__ == "__main__":
    main()
