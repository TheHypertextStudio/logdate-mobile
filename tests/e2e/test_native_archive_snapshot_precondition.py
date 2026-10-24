from contextlib import contextmanager, nullcontext
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

from native_backup_test_support import NATIVE_BACKUP_ACCEPTANCE as H


class ArchiveSnapshotPreconditionTest(unittest.TestCase):
    @contextmanager
    def backup(self, *, changed=False, launch=b"Status: ok"):
        events = []
        archive = (
            "files/archive_recovery/records/9c95e470-f773-41f3-bcec-eb53afe37e25.json"
        )
        before = {
            "databases/logdate": {"bytes": 1, "sha256": "journal"},
            archive: {"sha256": "settled"},
        }
        after = {**before, archive: {"sha256": "changed"}} if changed else before
        snapshots = iter((before, after))

        def snapshot(serial):
            events.append("snapshot")
            return next(snapshots)

        def adb(serial, *args, **kwargs):
            if args[:3] == ("shell", "am", "start"):
                events.append("launch")
                return launch
            if "backupnow" in args:
                events.append("backup")
                return f"Package {H.PACKAGE} with result: Success".encode()
            return b""

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.object(H, "adb", side_effect=adb),
            patch.object(H, "package_file_snapshot", side_effect=snapshot),
            patch.object(H, "enable_backup"),
            patch.object(H, "private_file"),
            patch.object(H, "capture_archive_records", return_value={}),
            patch.object(H, "record_archive_changes"),
            patch.object(
                H,
                "wait_for_timeline",
                side_effect=lambda *args, **kwargs: events.append("timeline"),
            ),
        ):
            yield events, Path(folder) / "backup.log", archive

    def test_prepare_runs_after_launch_and_before_strict_baseline(self):
        with self.backup() as (events, output, _):
            H.backup_source(
                "emulator-5554",
                output,
                prepare_for_snapshot=lambda: events.append("prepare"),
            )
        self.assertEqual(
            ["launch", "timeline", "prepare", "snapshot", "backup", "snapshot"], events
        )

    def test_timeout_launch_waits_for_timeline_before_preparing(self):
        with self.backup(launch=b"Status: timeout") as (events, output, _):
            H.backup_source(
                "emulator-5554",
                output,
                prepare_for_snapshot=lambda: events.append("prepare"),
            )
        self.assertEqual(
            ["launch", "timeline", "prepare", "snapshot", "backup", "snapshot"], events
        )

    def test_failed_precondition_preserves_failure_and_never_snapshots_or_backs_up(
        self,
    ):
        failure = RuntimeError("real archive work not settled")

        def prepare():
            raise failure

        with self.backup() as (events, output, _):
            with self.assertRaises(RuntimeError) as caught:
                H.backup_source("emulator-5554", output, prepare_for_snapshot=prepare)
        self.assertIs(failure, caught.exception)
        self.assertEqual(["launch", "timeline"], events)

    def test_post_prepare_archive_mutation_still_fails_exact_snapshot_comparison(self):
        with self.backup(changed=True) as (events, output, archive):
            with self.assertRaisesRegex(
                RuntimeError, "snapshot changed durable data"
            ) as caught:
                H.backup_source(
                    "emulator-5554",
                    output,
                    prepare_for_snapshot=lambda: events.append("prepare"),
                )
            self.assertIn(archive, str(caught.exception))
        self.assertEqual(
            ["launch", "timeline", "prepare", "snapshot", "backup", "snapshot"], events
        )

    def test_unprepared_backup_preserves_original_order_and_strict_comparison(self):
        with self.backup(changed=True) as (events, output, _):
            with self.assertRaisesRegex(RuntimeError, "snapshot changed durable data"):
                H.backup_source("emulator-5554", output)
        self.assertEqual(["launch", "snapshot", "backup", "snapshot"], events)

    def test_only_archive_transfer_supplies_source_settlement_callback_with_same_fixture(
        self,
    ):
        class ReachedBackup(Exception):
            pass

        for archive in (True, False):
            with self.subTest(archive=archive), tempfile.TemporaryDirectory() as folder:
                probes = []

                def adb(serial, *args, **kwargs):
                    if str(args[-1]).endswith("native-backup-fixture.json"):
                        return b"{}"
                    return json.dumps(
                        {"journal": "archive-journal", "extraJournal": "extra-journal"}
                    ).encode()

                def backup(serial, output, **kwargs):
                    callback = kwargs.get("prepare_for_snapshot")
                    self.assertEqual(archive, callback is not None)
                    if callback is not None:
                        callback()
                    raise ReachedBackup()

                with (
                    patch.object(H, "prepare_source"),
                    patch.object(H, "adb", side_effect=adb),
                    patch.object(
                        H,
                        "archive_probe",
                        side_effect=lambda *args, **kwargs: probes.append(
                            (args, kwargs)
                        ),
                    ),
                    patch.object(H, "private_file"),
                    patch.object(
                        H, "data_only_source_session", return_value=nullcontext(None)
                    ),
                    patch.object(H, "backup_source", side_effect=backup),
                ):
                    with self.assertRaises(ReachedBackup):
                        H.transfer(
                            "emulator-5554",
                            "emulator-5556",
                            Path("app.apk"),
                            Path("test.apk"),
                            Path(folder),
                            "phrase",
                            exercise_archive_recovery=archive,
                        )
                if archive:
                    self.assertEqual(
                        ["seed", "source-waiting"], [args[1] for args, _ in probes]
                    )
                    self.assertEqual(
                        probes[0][1]["fixture_label"], probes[1][1]["fixture_label"]
                    )
                    self.assertTrue(
                        all(args[0] == "emulator-5554" for args, _ in probes)
                    )
                else:
                    self.assertEqual([], probes)


if __name__ == "__main__":
    unittest.main()
