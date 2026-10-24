"""Evaluate LogDate's native backup coverage under Android's XML rule semantics."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
RULES = ROOT / "app/android-main/src/main/res/xml"


def matches(rule, domain, path):
    prefix = rule.get("path", ".").rstrip("/")
    return rule.get("domain") == domain and (
        prefix == "." or path == prefix or path.startswith(prefix + "/")
    )


def included(domain, path, *, modern, transfer, encrypted=True):
    document = ET.parse(
        RULES / ("data_extraction_rules.xml" if modern else "backup_rules.xml")
    ).getroot()
    section = (
        document.find("device-transfer" if transfer else "cloud-backup")
        if modern
        else document
    )
    if (
        modern
        and not transfer
        and not encrypted
        and section.get("disableIfNoEncryptionCapabilities") == "true"
    ):
        return False
    flags = (
        {"deviceToDeviceTransfer"}
        if transfer
        else ({"clientSideEncryption"} if encrypted else set())
    )
    includes = section.findall("include")
    allowed = not includes or any(
        matches(rule, domain, path)
        and set(filter(None, rule.get("requireFlags", "").split("|"))).issubset(flags)
        for rule in includes
    )
    return allowed and not any(
        matches(rule, domain, path) for rule in section.findall("exclude")
    )


class NativeBackupPolicyTest(unittest.TestCase):
    def test_cloud_preserves_actual_owner_preferences_and_database_keys(self):
        for modern in (False, True):
            for domain, path in (
                ("file", "logdate.preferences_pb"),
                ("database", "logdate"),
                ("file", "db_recovery/passphrase_backup"),
                ("file", "identity_recovery/phrase_backup"),
                ("file", "identity_recovery/account_key_backup"),
                ("file", "identity_recovery/media_key_backup"),
            ):
                with self.subTest(modern=modern, path=path):
                    self.assertTrue(
                        included(domain, path, modern=modern, transfer=False)
                    )

    def test_cloud_never_exports_recovery_secrets_without_client_encryption(self):
        for modern in (False, True):
            for path in (
                "db_recovery/passphrase_backup",
                "identity_recovery/phrase_backup",
                "identity_recovery/account_key_backup",
                "identity_recovery/media_key_backup",
            ):
                with self.subTest(modern=modern, path=path):
                    self.assertFalse(
                        included(
                            "file", path, modern=modern, transfer=False, encrypted=False
                        )
                    )

    def test_direct_transfer_requires_agent_selection_for_original_media(self):
        # XML default traversal omits originals; the pinned agent selection emits them.
        for modern in (False, True):
            for path in (
                "audio_notes/accepted.m4a",
                "media/objects/photo.jpg",
                "media/objects/movie.mp4",
                "user_media/retained.jpg",
            ):
                with self.subTest(modern=modern, path=path):
                    self.assertFalse(
                        included("file", path, modern=modern, transfer=True)
                    )
                    self.assertFalse(
                        included("file", path, modern=modern, transfer=False)
                    )
            for path in (
                "native_journal_transfer/current/database/logdate",
                "native_journal_transfer/current/media-inventory.json",
            ):
                with self.subTest(modern=modern, selected=path):
                    self.assertTrue(
                        included("file", path, modern=modern, transfer=True)
                    )
                    self.assertFalse(
                        included("file", path, modern=modern, transfer=False)
                    )

    def test_direct_transfer_excludes_unfinished_camera_capture_outputs(self):
        for modern in (False, True):
            for path in (
                "camera-captures/logdate-camera-photo.jpg",
                "camera-captures/logdate-camera-video.mp4",
            ):
                with self.subTest(modern=modern, path=path):
                    self.assertFalse(
                        included("file", path, modern=modern, transfer=True)
                    )

    def test_placed_widget_choices_survive_restore_before_launcher_id_remapping(self):
        # WidgetInstanceSettings.remap needs the old IDs' chosen entry and presentation
        # preferences; restoring only the journal cannot reconstruct those choices.
        for modern in (False, True):
            for transfer in (False, True):
                with self.subTest(modern=modern, transfer=transfer):
                    self.assertTrue(
                        included(
                            "sharedpref",
                            "logdate_widget_instances.xml",
                            modern=modern,
                            transfer=transfer,
                        )
                    )

    def test_direct_transfer_excludes_keystore_ciphertext_and_cloned_jobs(self):
        for modern in (False, True):
            for domain, path in (
                ("sharedpref", "logdate_secure_storage.xml"),
                ("database", "androidx.work.workdb"),
                ("database", "androidx.work.workdb-wal"),
                ("file", "media/staging/incomplete.part"),
                ("file", "audio_notes/staging/recording_123.aac"),
                ("file", "audio_notes/staging/recording_123.m4a.tmp"),
            ):
                with self.subTest(modern=modern, path=path):
                    self.assertFalse(
                        included(domain, path, modern=modern, transfer=True)
                    )

    def test_vendor_installation_identifiers_are_not_transferred_as_user_content(self):
        for modern in (False, True):
            for path in (
                "PersistedInstallation.fixture.json",
                "watch_incoming/incomplete.part",
                "unknown-sdk-state/token",
            ):
                with self.subTest(modern=modern, path=path):
                    self.assertFalse(
                        included("file", path, modern=modern, transfer=True)
                    )

    def test_uncommitted_key_replacements_are_excluded(self):
        for modern in (False, True):
            for transfer in (False, True):
                for path in (
                    "db_recovery/passphrase_backup.part",
                    "identity_recovery/phrase_backup.part",
                    "identity_recovery/account_key_backup.part",
                    "identity_recovery/media_key_backup.part",
                ):
                    with self.subTest(modern=modern, transfer=transfer, path=path):
                        self.assertFalse(
                            included("file", path, modern=modern, transfer=transfer)
                        )


if __name__ == "__main__":
    unittest.main()
