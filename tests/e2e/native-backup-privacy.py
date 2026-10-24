"""Keep missing-policy delivery and repair tied to one intact cloud backup generation."""


def capture_missing_privacy_payload(
    h, source, destination, output, verify_after_backup
):
    """A partial cloud delivery has no authenticated D2D snapshot that repairs its missing policy."""
    parameters = (
        h.adb(
            source,
            "shell",
            "settings",
            "get",
            "secure",
            "backup_local_transport_parameters",
        )
        .decode()
        .strip()
    )
    cloud = "is_encrypted=true,is_device_transfer=false"
    try:
        for serial in (source, destination):
            h.adb(
                serial,
                "shell",
                "settings",
                "put",
                "secure",
                "backup_local_transport_parameters",
                cloud,
            )
        cloud_output = output / "privacy-cloud-backup"
        cloud_output.mkdir(mode=448)
        _, source_files = h.backup_source(
            source,
            cloud_output / "native-backup.log",
            verify_after_backup=lambda: verify_after_backup(cloud_output),
        )
        payload = h.adb(
            source, "exec-out", "tar", "-C", h.TRANSPORT_FILES, "-cf", "-", "1"
        )
        h.assert_cloud_backup_contents(payload)
        h.private_file(cloud_output / "native-transport-original.tar", payload)
        return (
            h.without_privacy_metadata(payload),
            {
                **source_files,
                "databases/logdate": h.selected_database_snapshot(
                    payload, cloud_backup=True
                ),
            },
            payload,
        )
    finally:
        h.adb(
            source,
            "shell",
            "settings",
            "put",
            "secure",
            "backup_local_transport_parameters",
            parameters,
        )


def verify_privacy_recovery(
    h, destination, output, selected_source_files, original_payload
):
    print(
        "Verifying missing privacy metadata blocks every database consumer",
        flush=True,
    )
    h.privacy_probe(destination, "blocked", output / "destination-privacy-blocked.log")
    started_at = float(h.adb(destination, "shell", "date", "+%s.%N").strip())
    refused_backup = h.adb(destination, "shell", "bmgr", "backupnow", h.PACKAGE)
    h.private_file(output / "destination-privacy-backup-refused.log", refused_backup)
    diagnostics = h.adb(
        destination, "logcat", "-d", "-v", "epoch", "-s", "BackupServiceBinder:D"
    )
    h.verify_privacy_backup_refusal(refused_backup, diagnostics, started_at)
    h.privacy_probe(
        destination, "blocked", output / "destination-privacy-after-backup.log"
    )
    h.private_file(
        output / "destination-privacy-before-repair.json",
        h.json.dumps(h.package_file_snapshot(destination), indent=2).encode(),
    )
    # Deliver the original source policy through Android, without clearing destination data.
    h.adb(destination, "shell", "am", "force-stop", h.PACKAGE)
    # Cloud replay may checkpoint its WAL; this occupied journal is now authoritative.
    before_repair = h.package_file_snapshot(destination)
    before_preferences = h.adb(
        destination,
        "exec-out",
        "run-as",
        h.PACKAGE,
        "cat",
        "files/logdate.preferences_pb",
    )
    h.private_file(output / "native-transport-complete.tar", original_payload)
    h.adb(
        destination,
        "push",
        str(output / "native-transport-complete.tar"),
        "/data/local/tmp/logdate-native-transport.tar",
    )
    h.adb(
        destination,
        "shell",
        "tar",
        "-C",
        h.TRANSPORT_FILES,
        "-xf",
        "/data/local/tmp/logdate-native-transport.tar",
    )
    h.adb(
        destination, "shell", "chown", "-R", "system:system", h.TRANSPORT_FILES + "/1"
    )
    h.adb(destination, "shell", "restorecon", "-R", h.TRANSPORT_FILES + "/1")
    h.adb(
        destination,
        "shell",
        "settings",
        "put",
        "secure",
        "backup_local_transport_parameters",
        "is_encrypted=true,is_device_transfer=true",
    )
    repaired = h.adb(destination, "shell", "bmgr", "restore", "1", h.PACKAGE)
    h.private_file(output / "native-restore-complete.log", repaired)
    if b"restoreFinished: 0" not in repaired:
        raise RuntimeError(
            "Android did not finish delivering the original privacy policy"
        )
    repaired_files = h.package_file_snapshot(destination)
    h.private_file(
        output / "destination-privacy-after-repair.json",
        h.json.dumps(repaired_files, indent=2).encode(),
    )
    try:
        h.verify_privacy_repair_snapshot(
            before_repair,
            repaired_files,
            before_preferences,
            h.adb(
                destination,
                "exec-out",
                "run-as",
                h.PACKAGE,
                "cat",
                "files/logdate.preferences_pb",
            ),
            h.selected_backup_contents(
                original_payload,
                f"apps/{h.PACKAGE}/f/logdate.preferences_pb",
            ),
        )
    except Exception as failure:
        try:
            h.privacy_probe(
                destination, "resolved", output / "destination-privacy-resolved.log"
            )
        except Exception as diagnostic:
            h.add_failure_note(
                failure,
                f"Privacy repair probe also failed: {type(diagnostic).__name__}; inspect destination-privacy-resolved.log",
            )
        raise
    h.privacy_probe(
        destination, "resolved", output / "destination-privacy-resolved.log"
    )
    backup = h.adb(destination, "shell", "bmgr", "backupnow", h.PACKAGE)
    h.private_file(output / "destination-privacy-backup-after-repair.log", backup)
    if f"Package {h.PACKAGE} with result: Success" not in backup.decode().splitlines():
        raise RuntimeError("Native backup is still refused after privacy repair")
    h.privacy_probe(
        destination,
        "resolved",
        output / "destination-privacy-after-successful-backup.log",
    )
    print(
        "Original native preferences restored journal access and native backup without replacing its key",
        flush=True,
    )
    return


FUNCTIONS = {
    "capture_missing_privacy_payload": capture_missing_privacy_payload,
    "verify_privacy_recovery": verify_privacy_recovery,
}
