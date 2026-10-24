"""Native acceptance safety contracts for backup."""

from pathlib import Path
import tempfile
from unittest.mock import patch

from native_safety_support import HARNESS


class NativeBackupSafety:
    def test_native_backup_checks_source_data_before_and_after_transport_backup(self):
        retained = {
            "databases/logdate": {"sha256": "journal"},
            "files/media/original.jpg": {"sha256": "media"},
        }
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
                side_effect=[retained, retained],
            ) as snapshot,
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    b"Status: ok",
                    b"Backup Manager now enabled",
                    b"Backup Manager currently enabled",
                    f"Package {HARNESS.PACKAGE} with result: Success".encode(),
                ],
            ) as adb,
        ):
            result, before = HARNESS.backup_source(
                "emulator-5576", Path(temporary) / "backup.log"
            )
            self.assertIn(b"with result: Success", result)
            self.assertEqual(retained, before)
            self.assertEqual(2, snapshot.call_count)
            adb_calls = [arguments.args[1:] for arguments in adb.call_args_list]
            self.assertLess(
                adb_calls.index(
                    (
                        "shell",
                        "am",
                        "start",
                        "-W",
                        "-n",
                        f"{HARNESS.PACKAGE}/app.logdate.client.MainActivity",
                    )
                ),
                adb_calls.index(
                    (
                        "shell",
                        "bmgr",
                        "backupnow",
                        "@pm@",
                        HARNESS.PACKAGE,
                        "--non-incremental",
                    )
                ),
            )
            self.assertEqual(
                HARNESS.json.loads(
                    (
                        Path(temporary) / "source-files-before-native-backup.json"
                    ).read_text()
                ),
                retained,
            )
            self.assertEqual(
                HARNESS.json.loads(
                    (
                        Path(temporary) / "source-files-after-native-backup.json"
                    ).read_text()
                ),
                retained,
            )
            self.assertIn(
                (
                    "shell",
                    "bmgr",
                    "backupnow",
                    "@pm@",
                    HARNESS.PACKAGE,
                    "--non-incremental",
                ),
                [call.args[1:] for call in adb.call_args_list],
            )

    def test_native_backup_fails_if_source_journal_changes_during_backup(self):
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
                side_effect=[
                    {"databases/logdate": {"sha256": "before"}},
                    {"databases/logdate": {"sha256": "after"}},
                ],
            ),
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    b"Status: ok",
                    b"Backup Manager now enabled",
                    b"Backup Manager currently enabled",
                    f"Package {HARNESS.PACKAGE} with result: Success".encode(),
                ],
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "snapshot changed durable data"):
                HARNESS.backup_source("emulator-5576", Path(temporary) / "backup.log")

    def test_native_backup_validates_identity_before_allowing_secure_storage_rewrite(
        self,
    ):
        before = {
            "databases/logdate": {"sha256": "journal"},
            "shared_prefs/logdate_secure_storage.xml": {"sha256": "before"},
        }
        after = {
            **before,
            "shared_prefs/logdate_secure_storage.xml": {"sha256": "after"},
        }
        verified = []

        def verify_after_backup():
            self.assertEqual(
                ("emulator-5576", "shell", "am", "force-stop", HARNESS.PACKAGE),
                adb.call_args_list[-1].args,
            )
            verified.append(True)

        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
                side_effect=[before, after],
            ),
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    b"Status: ok",
                    b"Backup Manager now enabled",
                    b"Backup Manager currently enabled",
                    f"Package {HARNESS.PACKAGE} with result: Success".encode(),
                    b"",
                ],
            ) as adb,
        ):
            HARNESS.backup_source(
                "emulator-5576",
                Path(temporary) / "backup.log",
                verify_after_backup=verify_after_backup,
            )

        self.assertEqual([True], verified)

    def test_native_backup_still_rejects_other_data_changes_after_secure_validation(
        self,
    ):
        before = {
            "databases/logdate": {"sha256": "journal-before"},
            "shared_prefs/logdate_secure_storage.xml": {"sha256": "before"},
            "files/media/original.jpg": {"sha256": "media-before"},
        }
        after = {
            "databases/logdate": {"sha256": "journal-after"},
            "shared_prefs/logdate_secure_storage.xml": {"sha256": "after"},
            "files/media/original.jpg": {"sha256": "media-after"},
        }
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
                side_effect=[before, after],
            ),
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    b"Status: ok",
                    b"Backup Manager now enabled",
                    b"Backup Manager currently enabled",
                    f"Package {HARNESS.PACKAGE} with result: Success".encode(),
                    b"",
                ],
            ) as adb,
        ):
            with self.assertRaisesRegex(RuntimeError, "files/media/original.jpg"):
                HARNESS.backup_source(
                    "emulator-5576",
                    Path(temporary) / "backup.log",
                    verify_after_backup=lambda: None,
                )
            adb.assert_any_call(
                "emulator-5576",
                "shell",
                "am",
                "force-stop",
                HARNESS.PACKAGE,
            )

    def test_native_backup_failure_records_platform_eligibility_state(self):
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
                return_value={"databases/logdate": {"sha256": "journal"}},
            ),
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    b"Status: ok",
                    b"Backup Manager now enabled",
                    b"Backup Manager currently enabled",
                    f"Package {HARNESS.PACKAGE} with result: Backup is not allowed".encode(),
                    b"enabled=false setupComplete=true",
                ],
            ) as adb,
        ):
            with self.assertRaisesRegex(
                RuntimeError, "Android did not complete the native backup"
            ):
                HARNESS.backup_source("emulator-5576", Path(temporary) / "backup.log")

            diagnostic = Path(temporary) / "backup-platform-diagnostics.log"
            self.assertIn(b"enabled=false setupComplete=true", diagnostic.read_bytes())
            self.assertIn(
                ("shell", "dumpsys", "backup"),
                [arguments.args[1:] for arguments in adb.call_args_list],
            )

    def test_native_backup_reenables_and_verifies_the_platform_service_before_request(
        self,
    ):
        with patch.object(
            HARNESS,
            "adb",
            side_effect=[
                b"Backup Manager now enabled",
                b"Backup Manager currently enabled",
            ],
        ) as adb:
            HARNESS.enable_backup("emulator-5576")

        self.assertEqual(
            [
                ("shell", "bmgr", "enable", "true"),
                ("shell", "bmgr", "enabled"),
            ],
            [call.args[1:] for call in adb.call_args_list],
        )

    def test_archive_readiness_runs_after_launch_and_before_the_unchanged_snapshot(
        self,
    ):
        events = []
        retained = {"databases/logdate": {"sha256": "journal"}}

        def adb(serial, *arguments):
            if arguments[:3] == ("shell", "am", "start"):
                events.append("launch")
                return b"Status: ok"
            if arguments[:3] == ("shell", "bmgr", "enabled"):
                return b"Backup Manager currently enabled"
            return f"Package {HARNESS.PACKAGE} with result: Success".encode()

        def snapshot(serial):
            events.append("snapshot")
            return retained

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(HARNESS, "adb", side_effect=adb),
            patch.object(
                HARNESS,
                "wait_for_timeline",
                side_effect=lambda *args, **kwargs: events.append("timeline"),
            ),
            patch.object(HARNESS, "package_file_snapshot", side_effect=snapshot),
        ):
            HARNESS.backup_source(
                "emulator-5554",
                Path(folder) / "backup.log",
                prepare_for_snapshot=lambda: events.append("ready"),
            )
        self.assertEqual(
            ["launch", "timeline", "ready", "snapshot", "snapshot"], events
        )
