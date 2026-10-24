from pathlib import Path
import json
import runpy
import subprocess
import os
import shlex
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

MODULE = runpy.run_path(
    str(Path(__file__).with_name("native-missing-media-acceptance.py"))
)


class NetworkHandoffTest(unittest.TestCase):
    def full_profile(self, *, fail_corrupt_original=False, fail_partial_identity=False):
        action = MODULE["run"]
        selectors = []
        commands = []

        def adb(serial, *args, **kwargs):
            commands.append(args)
            if args[-1] == "wifi-networks":
                value = b"AndroidWifi;none\n"
            elif args[-1] == "restrict-background":
                value = b"disabled"
            elif args[-1] in ("wifi_on", "mobile_data"):
                value = b"1"
            else:
                value = b""
            return subprocess.CompletedProcess(args, 0, value)

        def instrument(serial, selector, *args, **kwargs):
            selectors.append(selector)
            if selector.endswith(
                "#partialNativeMediaRecognizesMissingOriginalAcrossReferenceSpellings"
            ):
                self.assertEqual(
                    "partialNativeMediaRecognizesMissingOriginalAcrossReferenceSpellings.log",
                    args[0].name,
                )
                self.assertEqual(1, kwargs.get("expected_tests", 1))
            if fail_corrupt_original and selector.endswith(
                "#eCorruptCanonicalOriginalsAreRepairedOnce"
            ):
                raise RuntimeError("Corrupt original was accepted")
            if fail_partial_identity and selector.endswith(
                "#partialNativeMediaRecognizesMissingOriginalAcrossReferenceSpellings"
            ):
                raise RuntimeError("Missing original identity was misclassified")

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.dict(
                action.__globals__,
                adb=adb,
                safe_target=lambda *args: "managed",
                instrument=instrument,
                run_network_cases=lambda *args: [
                    "metered-recovery",
                    "data-saver-recovery",
                ],
                restore_environment=lambda *args: None,
            ),
        ):
            options = SimpleNamespace(
                output=Path(folder),
                app_apk="app.apk",
                test_apk="tests.apk",
                wifi_network="AndroidWifi",
                network_only=False,
            )
            try:
                receipt = action(options, "emulator-5556")
                error = None
            except RuntimeError as caught:
                receipt, error = None, caught
        return selectors, receipt, error, commands

    def test_full_profile_requires_corrupt_original_recovery_before_success(self):
        selectors, receipt, error, _ = self.full_profile()
        self.assertIsNone(error)
        self.assertIn(
            MODULE["TEST"] + "#eCorruptCanonicalOriginalsAreRepairedOnce", selectors
        )
        self.assertEqual(10, receipt["tests"])
        self.assertTrue(receipt["corruptOriginalRecoveryVerified"])

    def test_full_profile_requires_partial_identity_regression_and_records_completed_cases(
        self,
    ):
        selectors, receipt, error, _ = self.full_profile()
        method = "partialNativeMediaRecognizesMissingOriginalAcrossReferenceSpellings"
        self.assertIsNone(error)
        self.assertEqual(1, selectors.count(MODULE["TEST"] + "#" + method))
        expected = [selector.rsplit("#", 1)[1] for selector in selectors[:5]]
        expected += [
            "metered-recovery",
            "data-saver-recovery",
            "archive-seed",
            "archive-automatic",
            "archive-verify",
        ]
        self.assertEqual(expected, receipt["cases"])
        self.assertIn(method, receipt["cases"])
        self.assertEqual(len(receipt["cases"]), receipt["tests"])

    def test_partial_identity_failure_prevents_success_and_restores_cellular(self):
        _, receipt, error, commands = self.full_profile(fail_partial_identity=True)
        self.assertIsNone(receipt)
        self.assertRegex(str(error), "Missing original identity")
        self.assertIn(("shell", "svc", "data", "enable"), commands)
        self.assertEqual(
            ("shell", "settings", "get", "global", "mobile_data"), commands[-1]
        )

    def test_corrupt_original_failure_prevents_success_and_restores_cellular(self):
        _, receipt, error, commands = self.full_profile(fail_corrupt_original=True)
        self.assertIsNone(receipt)
        self.assertIsNotNone(error)
        self.assertIn(("shell", "svc", "data", "enable"), commands)
        self.assertEqual(
            ("shell", "settings", "get", "global", "mobile_data"), commands[-1]
        )

    def test_environment_helpers_preserve_patched_caller_dependencies_and_readback(
        self,
    ):
        action = MODULE["restore_environment"]
        commands = []

        def adb(serial, *args, **kwargs):
            commands.append(args)
            if args[-1] == "wifi-networks":
                value = b"AndroidWifi;none\n"
            elif args[-1] == "restrict-background":
                value = b"disabled"
            elif args[-1] == "wifi_on":
                value = b"1"
            elif args[-1] == "airplane_mode_on":
                value = b"0"
            else:
                value = b""
            return subprocess.CompletedProcess(args, 0, value, b"")

        with patch.dict(
            action.__globals__,
            adb=adb,
            safe_target=lambda serial: commands.append(("owned", serial)),
        ):
            action("emulator-5556", "AndroidWifi", "none", False, False, b"1", {})
        self.assertEqual(("owned", "emulator-5556"), commands[0])
        self.assertIn(
            (
                "shell",
                "cmd",
                "netpolicy",
                "set",
                "metered-network",
                "AndroidWifi",
                "undefined",
            ),
            commands,
        )
        self.assertIn(("shell", "cmd", "netpolicy", "list", "wifi-networks"), commands)
        self.assertEqual(
            ("shell", "dumpsys", "package", MODULE["PACKAGE"]), commands[-1]
        )

    def test_host_verifies_the_ack_before_the_device_can_consume_the_published_file(
        self,
    ):
        action = MODULE["release_network_handoff"]
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "no_backup").mkdir()
            binary = root / "bin"
            binary.mkdir()
            consume = binary / "mv"
            consume.write_text('#!/bin/sh\n/bin/mv "$@" && /bin/rm "$2"\n')
            consume.chmod(0o700)

            def adb(serial, *args, **kwargs):
                if kwargs.get("input"):
                    script = shlex.split(args[-1])[-1]
                    return subprocess.run(
                        ["/bin/sh", "-c", script],
                        input=kwargs["input"],
                        cwd=root,
                        env={
                            **os.environ,
                            "PATH": str(binary) + os.pathsep + os.environ["PATH"],
                        },
                        capture_output=True,
                        check=True,
                    )
                return subprocess.CompletedProcess(args, 0, b"disabled")

            with patch.dict(
                action.__globals__, adb=adb, set_metered=lambda *args: None
            ):
                action("emulator-5556", "AndroidWifi", "a" * 32, root / "handoff.json")
            self.assertTrue(
                json.loads((root / "handoff.json").read_text())["acknowledged"]
            )
            self.assertEqual([], list((root / "no_backup").iterdir()))

    def test_missing_media_network_profile_isolates_and_restores_cellular_on_failure(
        self,
    ):
        action = MODULE["run"]
        commands = []

        def adb(serial, *args, **kwargs):
            commands.append(args)
            value = b""
            if args[-1] == "wifi-networks":
                value = b"AndroidWifi;none\n"
            elif args[-1] == "restrict-background":
                value = b"disabled"
            elif args[-1] == "airplane_mode_on":
                value = b"0"
            elif args[-1] in ("wifi_on", "mobile_data"):
                value = b"1"
            return subprocess.CompletedProcess(args, 0, value)

        def network_cases(*args):
            self.assertIn(("shell", "svc", "data", "disable"), commands)
            self.assertIn(("shell", "svc", "wifi", "disable"), commands)
            self.assertLess(
                commands.index(("shell", "svc", "wifi", "disable")),
                commands.index(("shell", "svc", "wifi", "enable")),
            )
            self.assertLess(
                commands.index(
                    ("shell", "cmd", "connectivity", "airplane-mode", "enable")
                ),
                commands.index(
                    ("shell", "cmd", "connectivity", "airplane-mode", "disable")
                ),
            )
            self.assertIn(
                ("shell", "cmd", "wifi", "connect-network", "AndroidWifi", "open"),
                commands,
            )
            raise RuntimeError("Network acceptance failed")

        with (
            tempfile.TemporaryDirectory() as folder,
            patch.dict(
                action.__globals__,
                adb=adb,
                safe_target=lambda *args: "managed",
                run_network_cases=network_cases,
                restore_environment=lambda *args: None,
            ),
        ):
            options = SimpleNamespace(
                output=Path(folder),
                app_apk="app.apk",
                test_apk="tests.apk",
                wifi_network="AndroidWifi",
                network_only=True,
            )
            with self.assertRaisesRegex(RuntimeError, "Network acceptance failed"):
                action(options, "emulator-5556")
            self.assertIn(("shell", "svc", "data", "enable"), commands)
            self.assertEqual(
                ("shell", "settings", "get", "global", "mobile_data"), commands[-1]
            )

    def exercise(self, directory, *, saver_enabled=False):
        events = []

        def adb(serial, *args, **kwargs):
            events.append((args, kwargs.get("input")))
            if args == ("shell", "cmd", "netpolicy", "get", "restrict-background"):
                return subprocess.CompletedProcess(
                    args, 0, b"enabled" if saver_enabled else b"disabled"
                )
            if kwargs.get("input"):
                return subprocess.CompletedProcess(args, 0, kwargs["input"])
            return subprocess.CompletedProcess(args, 0, b"")

        def metered(*args):
            events.append(("metered-verified", args))

        action = MODULE.get("release_network_handoff")
        self.assertIsNotNone(
            action, "A host acknowledgment must follow verified policy changes"
        )
        with patch.dict(action.__globals__, adb=adb, set_metered=metered):
            try:
                action(
                    "emulator-5556", "AndroidWifi", "a" * 32, directory / "handoff.json"
                )
            except Exception as error:
                error.events = events
                raise
        return events

    def test_acknowledgment_follows_both_verified_policy_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            events = self.exercise(directory)
            writes = [
                index for index, (_, raw) in enumerate(events) if raw == b"applied"
            ]
            self.assertEqual(1, len(writes))
            verified = events.index(
                ("metered-verified", ("emulator-5556", "AndroidWifi", "false"))
            )
            self.assertLess(verified, writes[0])
            report = json.loads((directory / "handoff.json").read_text())
            self.assertTrue(report["acknowledged"])
            self.assertEqual(
                [
                    "dataSaverSet",
                    "dataSaverVerified",
                    "meteredVerified",
                    "acknowledged",
                ],
                [step["stage"] for step in report["steps"]],
            )

    def test_failed_policy_readback_never_releases_the_device_callback_window(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, "Data Saver") as failure:
                self.exercise(Path(folder), saver_enabled=True)
            self.assertFalse(
                any(raw == b"applied" for _, raw in failure.exception.events)
            )
            report = json.loads((Path(folder) / "handoff.json").read_text())
            self.assertFalse(report["acknowledged"])

    def test_each_network_case_uses_a_distinct_handoff_and_keeps_callback_timeout_separate(
        self,
    ):
        events = []

        def instrument(serial, selector, log, *, extras, release_network, timeout):
            events.append((extras, timeout))

        action = MODULE.get("run_network_cases")
        self.assertIsNotNone(
            action, "Network policies need a focused managed-device acceptance route"
        )
        with (
            tempfile.TemporaryDirectory() as folder,
            patch.dict(
                action.__globals__,
                adb=lambda *args, **kwargs: None,
                set_metered=lambda *args: None,
                instrument=instrument,
            ),
        ):
            completed = action("emulator-5556", "AndroidWifi", Path(folder))
        self.assertEqual(["metered-recovery", "data-saver-recovery"], completed)
        self.assertEqual(2, len(events))
        nonces = [
            extras[extras.index("restoreNetworkHandoff") + 1] for extras, _ in events
        ]
        self.assertNotEqual(*nonces)
        self.assertTrue(all(len(nonce) == 32 for nonce in nonces))
        self.assertTrue(all(timeout == 180 for _, timeout in events))


if __name__ == "__main__":
    unittest.main()
