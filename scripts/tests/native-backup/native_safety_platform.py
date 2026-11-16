"""Native acceptance safety contracts for platform."""

import argparse
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

from native_safety_support import HARNESS


class NativePlatformSafety:
    def test_native_backup_stops_when_source_activity_launch_fails(self):
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
            ) as snapshot,
            patch.object(
                HARNESS, "adb", return_value=b"Error: Activity class does not exist"
            ),
        ):
            with self.assertRaisesRegex(
                RuntimeError, "source app did not start before native backup"
            ):
                HARNESS.backup_source("emulator-5576", Path(temporary) / "backup.log")

        snapshot.assert_not_called()

    def test_native_backup_waits_for_timeline_after_android_start_timeout(self):
        retained = {"databases/logdate": {"sha256": "journal"}}
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                HARNESS,
                "package_file_snapshot",
                return_value=retained,
            ),
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    b"Status: timeout",
                    b"Backup Manager now enabled",
                    b"Backup Manager currently enabled",
                    f"Package {HARNESS.PACKAGE} with result: Success".encode(),
                ],
            ) as adb,
            patch.object(HARNESS, "wait_for_timeline") as wait_for_timeline,
        ):
            output = Path(temporary) / "native-backup.log"
            HARNESS.backup_source("emulator-5576", output)
            wait_for_timeline.assert_called_once_with(
                "emulator-5576", Path(temporary), timeout_seconds=120, prefix="source"
            )
            self.assertEqual(
                b"Status: timeout",
                (
                    Path(temporary) / "source-launch-before-native-backup.log"
                ).read_bytes(),
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
                [arguments.args[1:] for arguments in adb.call_args_list],
            )

    def test_radio_cleanup_attempts_wifi_after_airplane_mode_failure(self):
        original = (False, b"1")
        with (
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    subprocess.CalledProcessError(1, "airplane"),
                    b"wifi restored",
                ],
            ) as adb,
            patch.object(HARNESS, "radio_state", return_value=original),
        ):
            HARNESS.restore_radio_state("emulator-5582", original)

        self.assertEqual(2, adb.call_count)
        self.assertEqual(
            ("shell", "svc", "wifi", "enable"), adb.call_args_list[1].args[1:]
        )

    def test_prepare_leaves_network_state_for_the_scenario_to_control(self):
        with (
            patch.object(
                HARNESS,
                "adb",
                side_effect=[b"1", b"", b"", b"", b"0", b"0", b"", b"1", b"", b""],
            ) as adb,
            patch.object(
                HARNESS,
                "wait_for_package_manager",
            ),
            patch.object(HARNESS, "select_local_transport"),
        ):
            HARNESS.prepare("emulator-5582")

        self.assertIn(
            ("shell", "settings", "put", "secure", "user_setup_complete", "1"),
            [call.args[1:] for call in adb.call_args_list],
        )
        self.assertFalse(
            any(
                call.args[2:4] in (("cmd", "connectivity"), ("svc", "wifi"))
                for call in adb.call_args_list
            ),
            "Transport preparation must not force a cold app launch to happen offline",
        )

    def test_prepare_sets_encrypted_transport_flags_before_selection(self):
        events = []

        def adb(serial, *arguments, **kwargs):
            events.append(arguments)
            if arguments == ("shell", "getprop", "ro.kernel.qemu"):
                return b"1"
            if arguments == ("shell", "id", "-u"):
                return b"0"
            return b""

        with (
            patch.object(HARNESS, "adb", side_effect=adb),
            patch.object(HARNESS, "wait_for_package_manager"),
            patch.object(HARNESS, "ensure_emulator_setup_complete"),
            patch.object(
                HARNESS,
                "select_local_transport",
                side_effect=lambda serial: events.append("selected"),
            ),
        ):
            HARNESS.prepare("emulator-5582")

        parameters = (
            "shell",
            "settings",
            "put",
            "secure",
            "backup_local_transport_parameters",
            "is_encrypted=true,is_device_transfer=true",
        )
        self.assertLess(events.index(parameters), events.index("selected"))

    def test_resume_preparation_requires_an_existing_synthetic_package(self):
        with (
            patch.object(HARNESS, "adb", side_effect=[b"1", b""]) as adb,
            patch.object(
                HARNESS,
                "wait_for_package_manager",
            ),
        ):
            with self.assertRaisesRegex(
                RuntimeError, "resume requires the synthetic source"
            ):
                HARNESS.prepare("emulator-5582", allow_existing=True)

        self.assertEqual(2, adb.call_count)

    def test_package_manager_readiness_retries_until_the_android_service_is_available(
        self,
    ):
        with (
            patch.object(
                HARNESS,
                "adb",
                side_effect=[
                    subprocess.CalledProcessError(20, "pm"),
                    b"package:android\n",
                ],
            ) as adb,
            patch.object(HARNESS.time, "sleep") as sleep,
        ):
            HARNESS.wait_for_package_manager("emulator-5582", attempts=2)

        self.assertEqual(2, adb.call_count)
        sleep.assert_called_once_with(1)

    def test_package_manager_readiness_stops_after_its_bounded_attempts(self):
        with (
            patch.object(
                HARNESS, "adb", side_effect=subprocess.CalledProcessError(20, "pm")
            ) as adb,
            patch.object(
                HARNESS.time,
                "sleep",
            ),
        ):
            with self.assertRaisesRegex(
                RuntimeError, "Package Manager did not become ready"
            ):
                HARNESS.wait_for_package_manager("emulator-5582", attempts=2)

        self.assertEqual(2, adb.call_count)

    def test_physical_remote_and_ambiguous_targets_are_rejected(self):
        for value in (
            "38191FDJG00FNY",
            "192.0.2.1:5555",
            "adb-phone._tcp",
            "emulator-",
            "emulator-5580; id",
        ):
            with self.subTest(serial=value):
                with self.assertRaises(argparse.ArgumentTypeError):
                    HARNESS.emulator_serial(value)

    def test_failed_managed_adb_retains_timing_and_stderr_without_input_or_stdout(self):
        failures = (
            subprocess.CalledProcessError(
                1,
                ["owned-adb"],
                output=b"private-output",
                stderr=b"cat files/media/original.wav: No such file",
            ),
            subprocess.TimeoutExpired(
                ["owned-adb"],
                180,
                output=b"private-output",
                stderr=b"device unavailable",
            ),
        )
        for failure in failures:
            with (
                self.subTest(failure=type(failure).__name__),
                patch.object(
                    HARNESS, "managed_adb_command", return_value=["owned-adb"]
                ),
                patch.object(HARNESS.subprocess, "run", side_effect=failure),
                patch.object(HARNESS.time, "monotonic_ns", side_effect=(100, 150)),
                patch.object(HARNESS.time, "time_ns", return_value=200),
            ):
                with self.assertRaises(type(failure)) as caught:
                    HARNESS.adb(
                        "emulator-5554",
                        "exec-out",
                        "fixture",
                        input_bytes=b"private-input",
                    )
            notes = " ".join(getattr(caught.exception, "__notes__", []))
            self.assertIn("start_monotonic_ns=100", notes)
            self.assertIn("failure_monotonic_ns=150", notes)
            self.assertIn("failure_unix_ns=200", notes)
            self.assertIn(failure.stderr.decode(), notes)
            self.assertNotIn("private-input", notes)
            self.assertNotIn("private-output", notes)

    def test_managed_adb_failure_stderr_is_bounded(self):
        failure = subprocess.CalledProcessError(1, ["owned-adb"], stderr=b"x" * 10_000)
        with (
            patch.object(HARNESS, "managed_adb_command", return_value=["owned-adb"]),
            patch.object(HARNESS.subprocess, "run", side_effect=failure),
        ):
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                HARNESS.adb("emulator-5554", "exec-out", "fixture")
        notes = " ".join(getattr(caught.exception, "__notes__", []))
        self.assertIn("x" * 4096, notes)
        self.assertNotIn("x" * 4097, notes)

    def test_successful_managed_adb_returns_original_bytes(self):
        with (
            patch.object(HARNESS, "managed_adb_command", return_value=["owned-adb"]),
            patch.object(
                HARNESS.subprocess,
                "run",
                return_value=subprocess.CompletedProcess(
                    ["owned-adb"], 0, stdout=b"original", stderr=b""
                ),
            ),
        ):
            self.assertEqual(
                b"original", HARNESS.adb("emulator-5554", "exec-out", "fixture")
            )

    def test_explicit_emulator_serial_is_accepted(self):
        self.assertEqual("emulator-5582", HARNESS.emulator_serial("emulator-5582"))
