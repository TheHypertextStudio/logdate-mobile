import argparse
import ast
from contextlib import redirect_stderr
import hashlib
import io
from pathlib import Path
import subprocess
import tarfile
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


class PartialNativeTransportTest(unittest.TestCase):
    def test_removes_one_original_media_file_and_preserves_the_transport_tar(self):
        package = NATIVE_BACKUP_ACCEPTANCE.PACKAGE
        inner = self.make_tar(
            [
                (f"apps/{package}/f/media/objects/audio.wav", b"audio"),
                (f"apps/{package}/f/media/objects/photo.png", b"photo"),
                (f"apps/{package}/f/media/objects/video.mp4", b"video"),
                (f"apps/{package}/f/db_recovery/passphrase_backup", b"key"),
            ]
        )
        outer = self.make_tar([(f"1/_full/{package}", inner)])

        filtered, removed_path = NATIVE_BACKUP_ACCEPTANCE.without_one_native_media_file(
            outer
        )

        self.assertEqual("files/media/objects/photo.png", removed_path)
        with tarfile.open(fileobj=io.BytesIO(filtered), mode="r:") as transport:
            full = transport.extractfile(f"1/_full/{package}")
            self.assertIsNotNone(full)
            with tarfile.open(fileobj=full, mode="r:") as app_backup:
                names = {member.name for member in app_backup.getmembers()}
        self.assertNotIn(f"apps/{package}/f/media/objects/photo.png", names)
        self.assertIn(f"apps/{package}/f/media/objects/audio.wav", names)
        self.assertIn(f"apps/{package}/f/media/objects/video.mp4", names)
        self.assertIn(f"apps/{package}/f/db_recovery/passphrase_backup", names)

    @staticmethod
    def make_tar(entries):
        output = io.BytesIO()
        with tarfile.open(
            fileobj=output, mode="w:", format=tarfile.USTAR_FORMAT
        ) as archive:
            for name, contents in entries:
                info = tarfile.TarInfo(name)
                info.size = len(contents)
                archive.addfile(info, io.BytesIO(contents))
        return output.getvalue()


class PinnedNativeTransferArchiveTest(unittest.TestCase):
    def test_selected_database_hash_is_taken_from_transport_snapshot(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        prefix = f"apps/{harness.PACKAGE}/f/"
        nested = PartialNativeTransportTest.make_tar(
            [
                (
                    prefix + "native_journal_transfer/current/database/logdate",
                    b"selected encrypted journal",
                ),
                (prefix + "db_recovery/unrelated", b"unrelated"),
            ]
        )
        outer = PartialNativeTransportTest.make_tar(
            [(f"1/_full/{harness.PACKAGE}", nested)]
        )

        selected = harness.selected_database_snapshot(outer)

        self.assertEqual(len(b"selected encrypted journal"), selected["bytes"])
        self.assertEqual(
            hashlib.sha256(b"selected encrypted journal").hexdigest(),
            selected["sha256"],
        )

    def test_selected_database_hash_rejects_missing_snapshot(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        nested = PartialNativeTransportTest.make_tar(
            [(f"apps/{harness.PACKAGE}/f/db_recovery/unrelated", b"unrelated")]
        )
        outer = PartialNativeTransportTest.make_tar(
            [(f"1/_full/{harness.PACKAGE}", nested)]
        )

        with self.assertRaisesRegex(RuntimeError, "selected journal database"):
            harness.selected_database_snapshot(outer)

    def test_missing_key_fixture_removes_selected_database_key_and_identity_keys_only(
        self,
    ):
        harness = NATIVE_BACKUP_ACCEPTANCE
        prefix = f"apps/{harness.PACKAGE}/f/"
        selected_key = (
            prefix
            + "native_journal_transfer/current/files/db_recovery/passphrase_backup"
        )
        identity_key = prefix + "identity_recovery/phrase_backup"
        media_key = prefix + "identity_recovery/media_key_backup"
        retained = {
            prefix
            + "native_journal_transfer/current/database/logdate": b"selected journal",
            prefix
            + "native_journal_transfer/current/files/logdate.preferences_pb": b"selected preferences",
            prefix + "media/objects/photo.png": b"original",
            prefix + "db_recovery/unrelated": b"unrelated",
        }
        nested = PartialNativeTransportTest.make_tar(
            [
                (selected_key, b"database key"),
                (identity_key, b"identity key"),
                (media_key, b"media key"),
                *retained.items(),
            ]
        )
        outer = PartialNativeTransportTest.make_tar(
            [(f"1/_full/{harness.PACKAGE}", nested)]
        )

        filtered = harness.without_portable_recovery_keys(outer)

        with tarfile.open(fileobj=io.BytesIO(filtered), mode="r:") as transport:
            full = transport.extractfile(f"1/_full/{harness.PACKAGE}")
            self.assertIsNotNone(full)
            with tarfile.open(fileobj=full, mode="r:") as app_backup:
                names = {member.name for member in app_backup.getmembers()}
                self.assertTrue(
                    {selected_key, identity_key, media_key}.isdisjoint(names)
                )
                self.assertEqual(
                    retained,
                    {name: app_backup.extractfile(name).read() for name in retained},
                )

    def test_missing_key_fixture_rejects_an_archive_without_the_selected_key(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        prefix = f"apps/{harness.PACKAGE}/f/"
        nested = PartialNativeTransportTest.make_tar(
            [
                (prefix + "db_recovery/passphrase_backup", b"stale live key"),
                (prefix + "identity_recovery/phrase_backup", b"identity key"),
            ]
        )
        outer = PartialNativeTransportTest.make_tar(
            [(f"1/_full/{harness.PACKAGE}", nested)]
        )

        with self.assertRaisesRegex(RuntimeError, "expected portable key"):
            harness.without_portable_recovery_keys(outer)


class MissingPrivacyBackupFilterTest(unittest.TestCase):
    def test_policy_repair_preserves_the_occupied_destination_and_only_adds_source_policy(
        self,
    ):
        harness = NATIVE_BACKUP_ACCEPTANCE
        before = {
            "databases/logdate": {"sha256": "occupied-cloud"},
            "files/db_recovery/passphrase_backup": {"sha256": "installed-key"},
            "files/datastore/entry_drafts.preferences_pb": {"sha256": "draft"},
        }
        owner = harness.length_delimited_field(
            1, BackupPayloadFixture.preference_entry("owner", "retained")
        )
        policy = self.policy(harness)
        harness.verify_privacy_repair_snapshot(
            before, dict(before), owner, owner + policy, policy
        )
        for path in before:
            with (
                self.subTest(path=path),
                self.assertRaisesRegex(RuntimeError, "changed durable data"),
            ):
                changed = {**before, path: {"sha256": "incoming-replacement"}}
                harness.verify_privacy_repair_snapshot(
                    before, changed, owner, owner + policy, policy
                )

    def test_policy_repair_rejects_unrelated_changes_present_policy_and_wrong_source_policy(
        self,
    ):
        harness = NATIVE_BACKUP_ACCEPTANCE
        files = {"databases/logdate": {"sha256": "occupied"}}
        owner = harness.length_delimited_field(
            1, BackupPayloadFixture.preference_entry("owner", "retained")
        )
        policy = self.policy(harness)
        other = harness.length_delimited_field(
            1, BackupPayloadFixture.preference_entry("owner", "replaced")
        )
        none = self.policy(harness, security="NONE")
        for before, after, source in (
            (owner, other + policy, policy),
            (owner + policy, owner + policy, policy),
            (owner, owner + none, policy),
            (owner, owner, policy),
        ):
            with (
                self.subTest(before=bool(before), after=len(after)),
                self.assertRaisesRegex(RuntimeError, "privacy repair"),
            ):
                harness.verify_privacy_repair_snapshot(
                    files, files, before, after, source
                )

    def test_policy_oracle_selects_the_authenticated_snapshot_preferences(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        path = f"apps/{harness.PACKAGE}/f/native_journal_transfer/current/files/logdate.preferences_pb"
        nested = PartialNativeTransportTest.make_tar(
            [
                (path, self.policy(harness)),
                (
                    f"apps/{harness.PACKAGE}/f/logdate.preferences_pb",
                    self.policy(harness, security="NONE"),
                ),
            ]
        )
        outer = PartialNativeTransportTest.make_tar(
            [(f"1/_full/{harness.PACKAGE}", nested)]
        )
        self.assertEqual(
            self.policy(harness), harness.selected_backup_contents(outer, path)
        )

    @staticmethod
    def policy(harness, security="BIOMETRIC"):
        onboarded = harness.length_delimited_field(
            1, b"is_onboarded"
        ) + harness.length_delimited_field(2, b"\x08\x01")
        return harness.length_delimited_field(
            1, onboarded
        ) + harness.length_delimited_field(
            1,
            BackupPayloadFixture.preference_entry("security_level", security),
        )

    def test_requires_package_rejection_and_current_backup_agent_privacy_failure(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        result = f"Package {harness.PACKAGE} with result: Transport rejected package because it wasn't able to process it at the time\n".encode()
        diagnostic = (
            "100.100 42 43 D BackupServiceBinder: onFullBackup[M] (app.logdate.client.data.restore.NativeAccountBackupAgent) threw\n"
            "100.101 42 43 D BackupServiceBinder: NativeRestorePrivacyMetadataRequiredException\n"
            "100.102 42 43 D BackupServiceBinder: at NativeRestorePrivacyPolicy$prepareBackup$3.invokeSuspend\n"
        ).encode()
        harness.verify_privacy_backup_refusal(result, diagnostic, 100.0)
        for unrelated in (
            b"Backup is not enabled",
            b"Backup finished with result: Success",
            b"Package missing with result: Success",
        ):
            with self.assertRaises(RuntimeError):
                harness.verify_privacy_backup_refusal(unrelated, diagnostic, 100.0)
        for unrelated in (
            b"",
            diagnostic.replace(b"prepareBackup", b"verifyBeforeContent"),
        ):
            with self.assertRaises(RuntimeError):
                harness.verify_privacy_backup_refusal(result, unrelated, 100.0)
        with self.assertRaises(RuntimeError):
            harness.verify_privacy_backup_refusal(result, diagnostic, 101.0)

    def test_removes_only_privacy_metadata_and_preserves_nested_transport_content(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        prefix = f"apps/{harness.PACKAGE}"
        preferences = b"".join(
            harness.length_delimited_field(
                1, BackupPayloadFixture.preference_entry(name, value)
            )
            for name, value in [
                ("is_onboarded", "true"),
                ("security_level", "BIOMETRIC"),
                ("identity.canonical_owner_id", "native-test-owner"),
                ("editor_drafts_anonymous", "unfinished"),
            ]
        )
        generation = harness.length_delimited_field(
            1,
            harness.length_delimited_field(
                1, b"android_platform_search_index_generation"
            )
            + harness.length_delimited_field(
                2, harness.write_varint(4 << 3) + harness.write_varint(7)
            ),
        )
        preferences += generation
        entries = [
            (prefix + "/_manifest", b"manifest"),
            (prefix + "/f/logdate.preferences_pb", preferences),
            (prefix + "/db/logdate", b"encrypted journal"),
            (prefix + "/f/db_recovery/passphrase_backup", b"key"),
            (prefix + "/f/media/photo.png", b"original"),
        ]
        full_backup = BackupPayloadFixture.make_tar(entries)
        transport_name = f"1/_full/{harness.PACKAGE}"
        transport = BackupPayloadFixture.make_tar(
            [(transport_name, full_backup), ("1/@pm@", b"metadata")]
        )

        filtered = harness.without_privacy_metadata(transport)

        with tarfile.open(fileobj=io.BytesIO(filtered), mode="r:") as outer:
            self.assertEqual(b"metadata", outer.extractfile("1/@pm@").read())
            with tarfile.open(
                fileobj=io.BytesIO(outer.extractfile(transport_name).read()), mode="r:"
            ) as inner:
                for name, data in entries:
                    actual = inner.extractfile(name).read()
                    if name.endswith("logdate.preferences_pb"):
                        self.assertNotIn(b"is_onboarded", actual)
                        self.assertNotIn(b"security_level", actual)
                        self.assertIn(b"native-test-owner", actual)
                        self.assertIn(b"unfinished", actual)
                        self.assertIn(
                            generation,
                            actual,
                            "Only privacy fields may be removed from the original wire payload",
                        )
                    else:
                        self.assertEqual(data, actual)

    def test_rejects_fixture_that_never_had_privacy_metadata(self):
        harness = NATIVE_BACKUP_ACCEPTANCE
        full_backup = BackupPayloadFixture.make_tar(
            [
                (f"apps/{harness.PACKAGE}/f/logdate.preferences_pb", b""),
            ]
        )
        transport = BackupPayloadFixture.make_tar(
            [(f"1/_full/{harness.PACKAGE}", full_backup)]
        )
        with self.assertRaisesRegex(RuntimeError, "privacy metadata"):
            harness.without_privacy_metadata(transport)
