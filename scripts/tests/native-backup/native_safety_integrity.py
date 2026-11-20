"""Native acceptance safety contracts for integrity."""

from native_safety_support import HARNESS


class NativeIntegritySafety:
    def test_snapshot_rejects_changes_to_the_existing_journal(self):
        original = {
            "databases/logdate": {"sha256": "before"},
            "files/db_recovery/passphrase_backup": {"sha256": "key"},
            "databases/logdate-wal": {"sha256": "wal-before"},
            "files/media/objects/original.jpg": {"sha256": "media-before"},
            "files/logdate.preferences_pb": {"sha256": "settings-before"},
        }
        unchanged = {**original, "databases/logdate-wal": {"sha256": "wal-after"}}
        changed = {**unchanged, "databases/logdate": {"sha256": "after"}}
        changed_key = {
            **unchanged,
            "files/db_recovery/passphrase_backup": {"sha256": "changed-key"},
        }

        HARNESS.assert_snapshot_preserved(original, unchanged)
        with self.assertRaisesRegex(RuntimeError, "snapshot changed durable data"):
            HARNESS.assert_snapshot_preserved(original, changed)
        with self.assertRaisesRegex(RuntimeError, "snapshot changed durable data"):
            HARNESS.assert_snapshot_preserved(original, changed_key)
        with self.assertRaisesRegex(RuntimeError, "snapshot changed durable data"):
            HARNESS.assert_snapshot_preserved(
                original,
                {
                    **unchanged,
                    "files/media/objects/original.jpg": {"sha256": "media-after"},
                },
            )
        with self.assertRaisesRegex(RuntimeError, "snapshot changed durable data"):
            HARNESS.assert_snapshot_preserved(
                original,
                {
                    **unchanged,
                    "files/logdate.preferences_pb": {"sha256": "settings-after"},
                },
            )

    def test_snapshot_ignores_firebase_session_datastore_temporary_files_only(self):
        original = {
            "databases/logdate": {"sha256": "journal"},
            "files/datastore/firebaseSessions/sessionDataStore.data.tmp": {
                "sha256": "partial-write"
            },
            "files/datastore/firebaseSessions/sessionDataStore.data": {
                "sha256": "runtime-session"
            },
        }
        after_temporary_write = {
            **original,
            "files/datastore/firebaseSessions/sessionDataStore.data.tmp": {
                "sha256": "new-partial-write"
            },
        }
        after_session_change = {
            **original,
            "files/datastore/firebaseSessions/sessionDataStore.data": {
                "sha256": "changed-session"
            },
        }

        HARNESS.assert_snapshot_preserved(original, after_temporary_write)
        HARNESS.assert_snapshot_preserved(original, after_session_change)
        for neighbor in (
            "files/datastore/firebaseSessions/sessionDataStore.data.extra",
            "files/datastore/firebaseSessions/user-draft.preferences_pb",
            "files/datastore/firebaseSessions/other.data",
        ):
            with self.subTest(neighbor=neighbor):
                retained = {**original, neighbor: {"sha256": "retained-user-data"}}
                changed = {**retained, neighbor: {"sha256": "changed-user-data"}}
                with self.assertRaisesRegex(
                    RuntimeError, "snapshot changed durable data"
                ):
                    HARNESS.assert_snapshot_preserved(retained, changed)

    def test_preference_snapshot_ignores_runtime_search_index_generation(self):
        def field(number, value):
            tag = bytes([(number << 3) | 2])
            return tag + bytes([len(value)]) + value

        def preference(name, value):
            value_message = (
                b"\x20\x01"
                if name == "android_platform_search_index_generation"
                else field(5, value.encode())
            )
            return field(1, name.encode()) + field(2, value_message)

        before = field(1, preference("theme", "dark")) + field(
            1, preference("android_platform_search_index_generation", "1")
        )
        after = field(
            1, preference("android_platform_search_index_generation", "2")
        ) + field(1, preference("theme", "dark"))
        changed_setting = field(
            1, preference("android_platform_search_index_generation", "2")
        ) + field(1, preference("theme", "light"))

        self.assertEqual(
            HARNESS.canonical_preferences(before), HARNESS.canonical_preferences(after)
        )
        self.assertNotEqual(
            HARNESS.canonical_preferences(before),
            HARNESS.canonical_preferences(changed_setting),
        )

    def test_preference_snapshot_ignores_sync_status_written_by_background_sync(self):
        def field(number, value):
            return bytes([(number << 3) | 2, len(value)]) + value

        def preference(name, value):
            return field(1, field(1, name.encode()) + field(2, field(5, value.encode())))

        before = preference("theme", "dark")
        after_failed_sync = preference("sync_last_error_type", "NETWORK") + before
        changed_setting = preference("sync_last_error_type", "NETWORK") + preference("theme", "light")

        self.assertEqual(
            HARNESS.canonical_preferences(before),
            HARNESS.canonical_preferences(after_failed_sync),
        )
        self.assertEqual(
            {"added": [], "removed": [], "changed": []},
            HARNESS.preference_changes(before, after_failed_sync),
        )
        self.assertNotEqual(
            HARNESS.canonical_preferences(before),
            HARNESS.canonical_preferences(changed_setting),
        )

    def test_preference_snapshot_rejects_malformed_datastore_file(self):
        with self.assertRaisesRegex(ValueError, "invalid DataStore preferences"):
            HARNESS.canonical_preferences(b"\x0a\x80")

    def test_preference_snapshot_rejects_duplicate_keys_and_malformed_values(self):
        def field(number, value):
            return bytes([(number << 3) | 2, len(value)]) + value

        duplicate_key = (
            field(1, b"android_platform_search_index_generation")
            + field(1, b"theme")
            + field(2, b"\x20\x01")
        )
        malformed_value = field(1, b"theme") + field(2, b"\x2a\x05x")
        duplicate_value = (
            field(1, b"theme") + field(2, b"\x2a\x01x") + field(2, b"\x2a\x01y")
        )

        for entry in (duplicate_key, malformed_value, duplicate_value):
            with (
                self.subTest(entry=entry),
                self.assertRaisesRegex(ValueError, "invalid DataStore preferences"),
            ):
                HARNESS.canonical_preferences(field(1, entry))
