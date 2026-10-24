import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from native_backup_test_support import NATIVE_BACKUP_ACCEPTANCE, BackupPayloadFixture


class NativePrivacyCaptureTest(unittest.TestCase):
    def test_partial_privacy_capture_uses_cloud_bytes_and_preserves_the_source_transport_setting(
        self,
    ):
        harness = NATIVE_BACKUP_ACCEPTANCE
        prefix = f"apps/{harness.PACKAGE}"
        preferences = harness.length_delimited_field(
            1, BackupPayloadFixture.preference_entry("security_level", "BIOMETRIC")
        )
        nested = BackupPayloadFixture.make_tar(
            [
                (prefix + "/f/logdate.preferences_pb", preferences),
                (prefix + "/db/logdate", b"cloud encrypted database"),
                (prefix + "/db/logdate-wal", b"selected cloud WAL B"),
                (prefix + "/f/db_recovery/passphrase_backup", b"retained key"),
                (prefix + "/f/identity_recovery/media_key_backup", b"media-key"),
                (prefix + "/f/identity_recovery/phrase_backup", b"phrase-key"),
            ]
        )
        payload = BackupPayloadFixture.make_tar(
            [(f"1/_full/{harness.PACKAGE}", nested)]
        )
        calls = []

        def adb(serial, *arguments):
            calls.append((serial, arguments))
            if arguments[:3] == ("shell", "settings", "get"):
                return b"original source setting"
            if arguments[0] == "exec-out":
                return payload
            return b""

        def record_cloud_backup(serial, output, **arguments):
            harness.private_file(
                output.with_name("source-launch-before-native-backup.log"),
                b"cloud launch",
            )
            arguments["verify_after_backup"]()
            return (b"success", {"databases/logdate": {"sha256": "before checkpoint"}})

        def verifier(evidence_output=None):
            harness.private_file(
                (evidence_output or first_integrity.parent)
                / "source-backup-integrity.log",
                b"cloud integrity",
            )

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(harness, "adb", side_effect=adb),
            patch.object(
                harness, "backup_source", side_effect=record_cloud_backup
            ) as backup,
        ):
            first_launch = Path(folder) / "source-launch-before-native-backup.log"
            harness.private_file(first_launch, b"authenticated D2D launch")
            first_integrity = Path(folder) / "source-backup-integrity.log"
            harness.private_file(first_integrity, b"authenticated D2D integrity")
            filtered, files, original = harness.capture_missing_privacy_payload(
                "emulator-5554", "emulator-5556", Path(folder), verifier
            )
            self.assertEqual(payload, original)
            self.assertEqual(
                original,
                (
                    Path(folder)
                    / "privacy-cloud-backup"
                    / "native-transport-original.tar"
                ).read_bytes(),
            )
            backup.assert_called_once()
            self.assertEqual(
                (
                    "emulator-5554",
                    Path(folder) / "privacy-cloud-backup" / "native-backup.log",
                ),
                backup.call_args.args,
            )
            self.assertTrue(callable(backup.call_args.kwargs["verify_after_backup"]))
            self.assertEqual(
                b"authenticated D2D integrity", first_integrity.read_bytes()
            )
            self.assertEqual(
                b"cloud integrity",
                (
                    Path(folder) / "privacy-cloud-backup" / first_integrity.name
                ).read_bytes(),
            )
            self.assertEqual(b"authenticated D2D launch", first_launch.read_bytes())
            self.assertEqual(
                b"cloud launch",
                (
                    Path(folder) / "privacy-cloud-backup" / first_launch.name
                ).read_bytes(),
            )
        with tarfile.open(fileobj=io.BytesIO(original)) as outer:
            with tarfile.open(
                fileobj=outer.extractfile(f"1/_full/{harness.PACKAGE}")
            ) as inner:
                intact_files = {
                    member.name: inner.extractfile(member).read()
                    for member in inner
                    if member.isfile()
                }
        with tarfile.open(fileobj=io.BytesIO(filtered)) as outer:
            with tarfile.open(
                fileobj=outer.extractfile(f"1/_full/{harness.PACKAGE}")
            ) as inner:
                filtered_files = {
                    member.name: inner.extractfile(member).read()
                    for member in inner
                    if member.isfile()
                }
        self.assertEqual(intact_files.keys(), filtered_files.keys())
        for name, data in intact_files.items():
            if name != prefix + "/f/logdate.preferences_pb":
                self.assertEqual(data, filtered_files[name], name)
        self.assertEqual(
            hashlib.sha256(b"cloud encrypted database").hexdigest(),
            files["databases/logdate"]["sha256"],
        )
        self.assertEqual(
            (
                "emulator-5554",
                (
                    "shell",
                    "settings",
                    "put",
                    "secure",
                    "backup_local_transport_parameters",
                    "original source setting",
                ),
            ),
            calls[-1],
        )
        with tarfile.open(fileobj=io.BytesIO(filtered)) as outer:
            with tarfile.open(
                fileobj=io.BytesIO(
                    outer.extractfile(f"1/_full/{harness.PACKAGE}").read()
                )
            ) as inner:
                self.assertNotIn(
                    b"security_level",
                    inner.extractfile(prefix + "/f/logdate.preferences_pb").read(),
                )
                self.assertEqual(
                    b"retained key",
                    inner.extractfile(
                        prefix + "/f/db_recovery/passphrase_backup"
                    ).read(),
                )

    def test_failed_cloud_capture_restores_source_setting_without_claiming_a_payload(
        self,
    ):
        harness = NATIVE_BACKUP_ACCEPTANCE
        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(harness, "adb", return_value=b"original") as adb,
            patch.object(
                harness, "backup_source", side_effect=RuntimeError("source changed")
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "source changed"):
                harness.capture_missing_privacy_payload(
                    "emulator-5554", "emulator-5556", Path(folder), lambda: None
                )
        self.assertEqual("original", adb.call_args.args[-1])
