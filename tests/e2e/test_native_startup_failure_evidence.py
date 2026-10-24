"""Keep failed startup observations without changing native acceptance deadlines."""

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from native_backup_test_support import NATIVE_BACKUP_ACCEPTANCE as H


class NativeStartupFailureEvidenceTest(unittest.TestCase):
    def test_timeout_keeps_bounded_phase_logs_before_caller_cleanup(self):
        failure = subprocess.TimeoutExpired(
            "owned instrumentation", 600, output=b"entered"
        )
        events = []
        raw = b"blocked phase\n" + b"x" * 600_000 + b"\nlast stack frame"

        def adb(serial, *arguments, timeout):
            self.assertEqual("emulator-5554", serial)
            if "instrument" in arguments:
                self.assertEqual(600, timeout)
                raise failure
            self.assertEqual("logcat", arguments[0])
            self.assertIn("-t", arguments)
            self.assertIn("NativeTransferProbeTrace:D", arguments)
            self.assertEqual(20, timeout)
            events.append("capture")
            return raw

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(H, "adb", side_effect=adb),
        ):
            output = Path(directory) / "source-account-session.log"
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                try:
                    H.probe("emulator-5554", "source-account-session", output)
                finally:
                    events.append("caller cleanup")
            self.assertIs(failure, raised.exception)
            saved = output.with_suffix(".timeout.logcat.log")
            self.assertTrue(
                saved.exists(),
                "Capture the failed phase before cleanup replaces UTP method logs",
            )
            self.assertLessEqual(saved.stat().st_size, 512 * 1024)
            self.assertTrue(saved.read_bytes().startswith(b"blocked phase\n"))
            self.assertTrue(saved.read_bytes().endswith(b"\nlast stack frame"))
            self.assertEqual(0o600, saved.stat().st_mode & 0o777)
            self.assertEqual(["capture", "caller cleanup"], events)

    def test_partial_output_write_failure_still_attempts_phase_capture(self):
        failure = subprocess.TimeoutExpired(
            "owned instrumentation", 600, output=b"entered"
        )
        calls = []

        def adb(serial, *arguments, **kwargs):
            calls.append(arguments[0])
            if "instrument" in arguments:
                raise failure
            return b"blocked phase"

        with (
            patch.object(H, "adb", side_effect=adb),
            patch.object(H, "private_file", side_effect=OSError("storage unavailable")),
        ):
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                H.probe("emulator-5554", "source-account-session", Path("unused.log"))
        self.assertIs(failure, raised.exception)
        self.assertEqual(["shell", "logcat"], calls)
        self.assertEqual(2, len(failure.__notes__))

    def test_timed_out_probes_keep_partial_output_and_the_original_failure(self):
        for method, mode in [
            ("probe", "seed"),
            ("archive_probe", "seed"),
            ("privacy_probe", "blocked"),
        ]:
            with (
                self.subTest(method=method),
                tempfile.TemporaryDirectory() as directory,
            ):
                output = Path(directory) / "probe.log"
                failure = subprocess.TimeoutExpired(
                    "owned instrumentation",
                    600,
                    output=b"INSTRUMENTATION_STATUS: stage=entered\n",
                    stderr=b"native warning\n",
                )
                with patch.object(H, "adb", side_effect=failure):
                    with self.assertRaises(subprocess.TimeoutExpired) as raised:
                        getattr(H, method)("emulator-5554", mode, output)
                self.assertIs(failure, raised.exception)
                self.assertTrue(
                    output.exists(),
                    "Partial native stage evidence must survive timeout",
                )
                self.assertEqual(failure.stdout, output.read_bytes())
                self.assertEqual(
                    failure.stderr, output.with_suffix(".stderr.log").read_bytes()
                )
                self.assertEqual(0o600, output.stat().st_mode & 0o777)

    def test_timeout_output_is_bounded_and_retains_both_ends(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "probe.log"
            failure = subprocess.TimeoutExpired(
                "owned instrumentation",
                600,
                output=b"first stage\n" + b"x" * 2_000_000 + b"\nlast stage\n",
            )
            with patch.object(H, "adb", side_effect=failure):
                with self.assertRaises(subprocess.TimeoutExpired):
                    H.probe("emulator-5554", "seed", output)
            self.assertTrue(
                output.exists(),
                "Oversized timeout evidence needs a bounded retained record",
            )
            raw = output.read_bytes()
            self.assertLessEqual(len(raw), 512 * 1024)
            self.assertTrue(raw.startswith(b"first stage\n"))
            self.assertTrue(raw.endswith(b"\nlast stage\n"))
            self.assertIn(b"truncated", raw)

    def test_failed_evidence_write_does_not_replace_timeout(self):
        failure = subprocess.TimeoutExpired(
            "owned instrumentation", 600, output=b"partial"
        )
        with (
            patch.object(H, "adb", side_effect=failure),
            patch.object(H, "private_file", side_effect=OSError("storage unavailable")),
        ):
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                H.probe("emulator-5554", "seed", Path("unused.log"))
        self.assertIs(failure, raised.exception)
        self.assertIn("OSError", " ".join(getattr(failure, "__notes__", [])))

    def test_backup_diagnostics_do_not_extend_the_existing_global_budget(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(H.time, "monotonic", side_effect=[0, 21]),
            patch.object(H, "adb", return_value=b"agent log") as adb,
        ):
            failure = RuntimeError("agent failed")
            H.capture_backup_failure(
                "emulator-5554", Path(directory) / "backup.log", failure
            )
            adb.assert_called_once()
            self.assertEqual(20, adb.call_args.kwargs["timeout"])
            self.assertIn("20-second", " ".join(failure.__notes__))

    def test_failed_backup_keeps_actual_lifecycle_screen_and_startup_logs(self):
        screenshot = b"\x89PNG\r\n\x1a\noriginal fixture pixels"

        def adb(serial, *args, **kwargs):
            if "screencap" in args:
                return screenshot
            if "activity" in args:
                return b"MainActivity PAUSED; system dialog resumed"
            if "-t" in args:
                return b"LogDateAppLaunch: bootstrap started"
            return b"backup bind failed"

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(H, "adb", side_effect=adb) as calls,
        ):
            output = Path(directory) / "backup.log"
            failure = RuntimeError("agent did not bind")
            H.capture_backup_failure("emulator-5554", output, failure)
            self.assertTrue(output.with_suffix(".activity.log").exists())
            self.assertIn(b"PAUSED", output.with_suffix(".activity.log").read_bytes())
            self.assertEqual(screenshot, output.with_suffix(".png").read_bytes())
            self.assertIn(
                b"bootstrap started", output.with_suffix(".startup.log").read_bytes()
            )
            self.assertEqual(20, calls.call_args_list[0].kwargs["timeout"])
            self.assertTrue(
                all(0 < c.kwargs["timeout"] <= 20 for c in calls.call_args_list)
            )
            self.assertIsInstance(failure, RuntimeError)


if __name__ == "__main__":
    unittest.main()
