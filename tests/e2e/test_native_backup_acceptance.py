import argparse
import ast
from contextlib import redirect_stderr
import hashlib
import io
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import tempfile
import time
import unittest
from unittest.mock import call, patch
from native_backup_test_support import (
    SCRIPT,
    NATIVE_BACKUP_ACCEPTANCE,
    NATIVE_RESTORE_WORK_ACCEPTANCE,
    D2D_SCRIPT,
    BackupPayloadFixture,
)


class AndroidBackupRulesContractTest(unittest.TestCase):
    def test_legacy_and_api31_rules_include_recovery_source_and_drafts_with_cloud_encryption(
        self,
    ):
        root = Path(__file__).resolve().parents[2]
        legacy = ET.parse(
            root / "app/android-main/src/main/res/xml/backup_rules.xml"
        ).getroot()
        modern = ET.parse(
            root / "app/android-main/src/main/res/xml/data_extraction_rules.xml"
        ).getroot()

        def included(parent, domain, path, required_flags=None):
            return any(
                node.tag == "include"
                and node.attrib.get("domain") == domain
                and node.attrib.get("path") == path
                and (
                    required_flags is None
                    or node.attrib.get("requireFlags") == required_flags
                )
                for node in parent
            )

        legacy_cloud = (
            "clientSideEncryption",
            (
                ("file", "logdate.preferences_pb"),
                ("file", "datastore/"),  # Includes pending editor drafts.
                ("file", "db_recovery/"),
                ("file", "identity_recovery/"),
                ("database", "logdate"),
                ("database", "logdate-wal"),
                ("database", "logdate-shm"),
            ),
        )
        legacy_cloud_flags, legacy_cloud_paths = legacy_cloud
        for domain, path in legacy_cloud_paths:
            with self.subTest(rules="legacy cloud", path=path):
                self.assertTrue(included(legacy, domain, path, legacy_cloud_flags))
        for domain, path in legacy_cloud_paths:
            with self.subTest(rules="legacy device transfer", path=path):
                self.assertTrue(
                    included(legacy, domain, path, "deviceToDeviceTransfer")
                )
        self.assertTrue(
            included(
                legacy,
                "file",
                "native_journal_transfer/current/",
                "deviceToDeviceTransfer",
            )
        )

        cloud = modern.find("cloud-backup")
        transfer = modern.find("device-transfer")
        self.assertIsNotNone(cloud)
        self.assertIsNotNone(transfer)
        self.assertEqual("true", cloud.attrib.get("disableIfNoEncryptionCapabilities"))
        modern_cloud_paths = legacy_cloud_paths
        for domain, path in modern_cloud_paths:
            with self.subTest(rules="API 31 cloud", path=path):
                self.assertTrue(included(cloud, domain, path))
        for domain, path in legacy_cloud_paths:
            if domain == "database":
                continue  # The complete database/key/prefs snapshot is beneath native_journal_transfer/current/.
            with self.subTest(rules="API 31 device transfer", path=path):
                self.assertTrue(included(transfer, domain, path))
        self.assertTrue(included(transfer, "file", "native_journal_transfer/current/"))
        for path in (
            "db_recovery/passphrase_backup",
            "db_recovery/working_passphrase_backup",
            "db_recovery/passphrase_backup.part",
            "db_recovery/working_passphrase_backup.part",
        ):
            self.assertTrue(
                any(
                    node.tag == "exclude"
                    and node.attrib.get("domain") == "file"
                    and node.attrib.get("path") == path
                    for node in transfer
                ),
                f"API 31 device transfer must keep the root-only portable key out of the direct file walk: {path}",
            )


class ManagedWorkSourceGuardTest(unittest.TestCase):
    def test_preinstalled_guard_checks_the_selected_package_even_when_import_defaults_differ(
        self,
    ):
        harness = NATIVE_RESTORE_WORK_ACCEPTANCE
        with (
            patch.object(
                harness, "adb", return_value=("package:" + harness.PACKAGE).encode()
            ),
            patch.dict(
                harness.NATIVE,
                {
                    "package_user_files": lambda serial, package: (
                        self.assertEqual(harness.PACKAGE, package) or []
                    )
                },
            ),
        ):
            harness.require_empty_install("emulator-5556", True)

    def test_preinstalled_source_requires_explicit_opt_in_and_refuses_retained_content(
        self,
    ):
        harness = NATIVE_RESTORE_WORK_ACCEPTANCE
        installed = ("package:" + harness.PACKAGE).encode()
        with (
            patch.object(harness, "adb", return_value=installed),
            patch.dict(
                harness.NATIVE, {"package_user_files": lambda serial, package: []}
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "fresh isolated"):
                harness.require_empty_install("emulator-5556", False)
            harness.require_empty_install("emulator-5556", True)
        for path in (
            "./databases/logdate",
            "./files/logdate.preferences_pb",
            "./files/audio_notes/draft.m4a",
            "./files/db_recovery/passphrase_backup",
        ):
            with (
                self.subTest(path=path),
                patch.object(harness, "adb", return_value=installed),
                patch.dict(
                    harness.NATIVE,
                    {"package_user_files": lambda serial, package: [path]},
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "retained data"):
                    harness.require_empty_install("emulator-5556", True)


class DataOnlySourceSessionTest(unittest.TestCase):
    def test_synthetic_session_stays_offline_through_backup_and_restores_radio_on_failure(
        self,
    ):
        harness = NATIVE_BACKUP_ACCEPTANCE
        events = []
        failure = RuntimeError("backup rejected")
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(harness, "radio_state", return_value=(False, b"1")),
            patch.object(
                harness,
                "set_offline",
                side_effect=lambda *args: events.append(("offline", args)),
            ),
            patch.object(
                harness,
                "probe",
                side_effect=lambda serial, mode, output, **kwargs: events.append(mode),
            ),
            patch.object(
                harness, "adb", side_effect=lambda *args: events.append(("adb", args))
            ),
            patch.object(
                harness,
                "restore_radio_state",
                side_effect=lambda *args: events.append(("restore", args)),
            ),
        ):
            with self.assertRaises(RuntimeError) as raised:
                with harness.data_only_source_session(
                    "emulator-5556", Path(directory), True
                ):
                    events.append("backup")
                    raise failure
        self.assertIs(failure, raised.exception)
        self.assertEqual(
            [
                ("offline", ("emulator-5556", True)),
                "source-account-session",
                "backup",
                "source-account-session-cleanup",
                (
                    "adb",
                    ("emulator-5556", "shell", "am", "force-stop", harness.PACKAGE),
                ),
                ("restore", ("emulator-5556", (False, b"1"))),
            ],
            events,
        )

    def test_failed_setup_cleanup_retains_the_same_unique_session_identity(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        failure = RuntimeError("existing session refused")
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(harness, "radio_state", return_value=(False, b"1")),
            patch.object(harness, "set_offline"),
            patch.object(harness, "probe", side_effect=[failure, None]) as probe,
            patch.object(harness, "adb"),
            patch.object(harness, "restore_radio_state"),
        ):
            with self.assertRaises(RuntimeError) as raised:
                with harness.data_only_source_session(
                    "emulator-5556", Path(directory), True
                ):
                    self.fail("A rejected source session cannot reach backup")
        self.assertIs(failure, raised.exception)
        identifiers = [
            call.kwargs["source_session_id"] for call in probe.call_args_list
        ]
        self.assertEqual(2, len(identifiers))
        self.assertEqual(identifiers[0], identifiers[1])
        self.assertRegex(identifiers[0], r"^[a-f0-9-]{36}$")

    def test_normal_transfer_does_not_change_source_connectivity(self):
        with patch.object(NATIVE_BACKUP_ACCEPTANCE, "radio_state") as radio:
            with NATIVE_BACKUP_ACCEPTANCE.data_only_source_session(
                "emulator-5556", Path("unused"), False
            ):
                pass
        radio.assert_not_called()


class RestoreSnapshotValidationTest(unittest.TestCase):
    def test_backup_accepts_checkpointed_database_only_after_logical_probe(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        before = {"databases/logdate": {"bytes": 4096, "sha256": "before"}}
        after = {"databases/logdate": {"bytes": 741376, "sha256": "after"}}
        calls = []

        def adb(serial, *arguments, **kwargs):
            if arguments[:3] == ("shell", "am", "start"):
                return b"Status: ok"
            if arguments[:3] == ("shell", "bmgr", "backupnow"):
                calls.append(arguments)
                return f"Package {harness.PACKAGE} with result: Success".encode()
            return b""

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(harness, "adb", side_effect=adb),
            patch.object(harness, "enable_backup"),
            patch.object(harness, "package_file_snapshot", side_effect=[before, after]),
            patch.object(harness, "private_file"),
        ):
            harness.backup_source(
                "emulator-5554",
                Path(directory) / "backup.log",
                verify_after_backup=lambda: calls.append("logical"),
            )
        self.assertEqual(
            [
                (
                    "shell",
                    "bmgr",
                    "backupnow",
                    "@pm@",
                    harness.PACKAGE,
                    "--non-incremental",
                ),
                "logical",
            ],
            calls,
        )

    def test_datastore_atomic_write_staging_is_not_an_accepted_preferences_change(self):
        retained = {
            "databases/logdate": {"sha256": "journal"},
            "files/logdate.preferences_pb": {"sha256": "preferences"},
        }
        during_write = {
            **retained,
            "files/logdate.preferences_pb.tmp": {"sha256": "staging"},
        }
        NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(during_write, retained)

    def test_atomic_write_exclusion_does_not_hide_accepted_preferences_or_recovery_keys(
        self,
    ):
        for path in (
            "files/logdate.preferences_pb",
            "files/db_recovery/passphrase_backup.part",
            "files/media/original.tmp",
        ):
            with self.subTest(path=path):
                before = {
                    "databases/logdate": {"sha256": "journal"},
                    path: {"sha256": "before"},
                }
                after = {
                    "databases/logdate": {"sha256": "journal"},
                    path: {"sha256": "after"},
                }
                with self.assertRaisesRegex(
                    RuntimeError, "snapshot changed durable data"
                ):
                    NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(before, after)

    def test_preference_change_diagnostics_identify_keys_without_values(self):
        harness = NATIVE_BACKUP_ACCEPTANCE

        def preferences(items):
            return b"".join(
                harness.length_delimited_field(
                    1, BackupPayloadFixture.preference_entry(k, v)
                )
                for k, v in items.items()
            )

        before = preferences(
            {"same": "retained", "changed": "old-secret", "removed": "removed-secret"}
        )
        after = preferences(
            {"same": "retained", "changed": "new-secret", "added": "added-secret"}
        )
        changes = harness.preference_changes(before, after)
        self.assertEqual(
            {"added": ["added"], "removed": ["removed"], "changed": ["changed"]},
            changes,
        )
        self.assertNotIn("secret", str(changes))

    def test_first_backup_may_make_only_missing_privacy_defaults_explicit(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        original = harness.length_delimited_field(
            1, BackupPayloadFixture.preference_entry("security_level", "BIOMETRIC")
        )
        explicit = harness.explicit_backup_privacy_defaults(original)
        self.assertIn(b"BIOMETRIC", explicit)
        self.assertNotIn(b"NONE", explicit)
        self.assertIn(b"is_onboarded", explicit)
        self.assertEqual(explicit, harness.explicit_backup_privacy_defaults(explicit))
        extra = harness.length_delimited_field(
            1, BackupPayloadFixture.preference_entry("draft", "changed")
        )
        self.assertNotEqual(explicit, harness.canonical_preferences(explicit + extra))

    def test_snapshot_ignores_firebase_backup_heartbeat_file(self):
        before = {
            "databases/logdate": {"bytes": 1, "sha256": "journal"},
            "files/datastore/FirebaseHeartBeatW0RFRkFVTFRd.preferences_pb": {
                "bytes": 1,
                "sha256": "before",
            },
        }
        after = {
            "databases/logdate": {"bytes": 1, "sha256": "journal"},
            "files/datastore/FirebaseHeartBeatW0RFRkFVTFRd.preferences_pb": {
                "bytes": 1,
                "sha256": "after",
            },
        }

        NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(before, after)

    def test_snapshot_still_rejects_changes_to_logdate_datastore(self):
        before = {
            "databases/logdate": {"bytes": 1, "sha256": "journal"},
            "files/datastore/logdate.preferences_pb": {"bytes": 1, "sha256": "before"},
        }
        after = {
            "databases/logdate": {"bytes": 1, "sha256": "journal"},
            "files/datastore/logdate.preferences_pb": {"bytes": 1, "sha256": "after"},
        }

        with self.assertRaisesRegex(
            RuntimeError,
            "snapshot changed durable data: files/datastore/logdate.preferences_pb",
        ):
            NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(before, after)

    def test_verified_checkpoint_allows_only_the_live_database_bytes_to_change(self):
        before = {
            "databases/logdate": {"bytes": 4096, "sha256": "live-before-checkpoint"},
            "files/db_recovery/passphrase_backup": {"bytes": 32, "sha256": "key"},
        }
        checkpointed = {
            **before,
            "databases/logdate": {"bytes": 741376, "sha256": "live-after-checkpoint"},
        }
        with self.assertRaisesRegex(RuntimeError, "databases/logdate"):
            NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(before, checkpointed)
        NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(
            before, checkpointed, ignored_paths={"databases/logdate"}
        )
        changed_key = {
            **checkpointed,
            "files/db_recovery/passphrase_backup": {"bytes": 32, "sha256": "different"},
        }
        with self.assertRaisesRegex(
            RuntimeError, "files/db_recovery/passphrase_backup"
        ):
            NATIVE_BACKUP_ACCEPTANCE.assert_snapshot_preserved(
                before, changed_key, ignored_paths={"databases/logdate"}
            )

    def test_credentials_only_snapshot_accepts_absent_database_and_media(self):
        restored = {
            "files/db_recovery/passphrase_backup": {
                "bytes": 32,
                "sha256": "passphrase",
            },
            "files/identity_recovery/phrase_backup": {"bytes": 24, "sha256": "phrase"},
            "files/identity_recovery/media_key_backup": {
                "bytes": 32,
                "sha256": "media-key",
            },
        }

        NATIVE_BACKUP_ACCEPTANCE.verify_restored_snapshot(
            restored, {}, credentials_only=True
        )

    def test_credentials_only_snapshot_reports_missing_keys_without_key_error(self):
        with self.assertRaisesRegex(RuntimeError, "journal recovery key"):
            NATIVE_BACKUP_ACCEPTANCE.verify_restored_snapshot(
                {}, {}, credentials_only=True
            )

    def test_data_only_snapshot_reports_missing_database_without_key_error(self):
        with self.assertRaisesRegex(
            RuntimeError, "without delivering the journal database"
        ):
            NATIVE_BACKUP_ACCEPTANCE.verify_restored_snapshot(
                {}, {}, credentials_only=False
            )

    def test_data_only_snapshot_rejects_a_database_that_differs_from_source(self):
        with self.assertRaisesRegex(RuntimeError, "destination database differs"):
            NATIVE_BACKUP_ACCEPTANCE.verify_restored_snapshot(
                {"databases/logdate": {"sha256": "different"}},
                {"databases/logdate": {"sha256": "source"}},
                credentials_only=False,
            )

    def test_started_camera_restore_allows_database_changes_after_prestart_comparison(
        self,
    ):
        NATIVE_BACKUP_ACCEPTANCE.verify_restored_snapshot(
            {"databases/logdate": {"sha256": "migrated"}},
            {"databases/logdate": {"sha256": "source"}},
            credentials_only=False,
            allow_database_changes=True,
        )

    def test_camera_only_restore_keeps_strict_database_comparison(self):
        transfer = next(
            node
            for node in ast.parse(SCRIPT.read_text()).body
            if isinstance(node, ast.FunctionDef) and node.name == "transfer"
        )
        comparisons = [
            node
            for node in ast.walk(transfer)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id == "verify_restored_snapshot"
        ]
        expected_condition = ast.dump(
            ast.parse(
                "exercise_pending_camera_draft and exercise_duplicate_restore",
                mode="eval",
            ).body
        )
        self.assertTrue(
            any(
                keyword.arg == "allow_database_changes"
                and ast.dump(keyword.value) == expected_condition
                for call in comparisons
                for keyword in call.keywords
            )
        )
        self.assertTrue(
            any(
                ast.unparse(call.args[0]) == "package_file_snapshot(destination)"
                and ast.unparse(call.args[1]) == "selected_source_files"
                and {
                    keyword.arg: ast.unparse(keyword.value) for keyword in call.keywords
                }
                == {"credentials_only": "False"}
                for call in comparisons
            ),
            "Pending-camera replay must compare the exact initial database before startup",
        )

    def test_missing_key_snapshot_accepts_rejected_journal_and_retained_preferences(
        self,
    ):
        NATIVE_BACKUP_ACCEPTANCE.verify_missing_keys_snapshot(
            {"files/logdate.preferences_pb": {"sha256": "preferences"}},
        )

    def test_missing_key_snapshot_rejects_a_delivered_database_or_portable_key(self):
        for path in (
            "databases/logdate",
            "databases/logdate-wal",
            "files/db_recovery/passphrase_backup",
            "files/identity_recovery/phrase_backup",
            "files/media/objects/original.png",
        ):
            with (
                self.subTest(path=path),
                self.assertRaisesRegex(RuntimeError, "missing-key restore"),
            ):
                NATIVE_BACKUP_ACCEPTANCE.verify_missing_keys_snapshot(
                    {
                        "files/logdate.preferences_pb": {"sha256": "preferences"},
                        path: {"sha256": "unexpected"},
                    },
                )

    def test_credentials_only_snapshot_allows_startup_created_empty_database(self):
        NATIVE_BACKUP_ACCEPTANCE.verify_restored_snapshot(
            {
                "databases/logdate": {"bytes": 4096, "sha256": "empty-sqlite"},
                "files/db_recovery/passphrase_backup": {
                    "bytes": 32,
                    "sha256": "passphrase",
                },
                "files/identity_recovery/phrase_backup": {
                    "bytes": 24,
                    "sha256": "phrase",
                },
                "files/identity_recovery/media_key_backup": {
                    "bytes": 32,
                    "sha256": "media-key",
                },
            },
            {},
            credentials_only=True,
        )
