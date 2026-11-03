"""Preparation retirement must await exit without signaling an unknown representation."""

from pathlib import Path
import runpy
import shlex
import unittest

SUBJECT = Path(__file__).with_name("managed-native-preparation.py")
PREPARATION = runpy.run_path(str(SUBJECT))
NONCE = "928e8cd1-bb47-4ef7-bc79-81854a2ef554"
AVD = "dev34_google_apis_playstore_x86_64_Pixel_9_Pro"
MANAGED_ID = "b67edde4-4f8e-41ad-a826-a385417a685b_GradleManagedDevice"
BIRTH = "Tue Nov 3 01:55:48 2026"
BRACKET_COMMAND = "[qemu-system-x86]"


class PreparationExitTransitionTest(unittest.TestCase):
    def setUp(self):
        self.now = 0.0
        self.signals = []
        self.arguments = [
            "@" + AVD,
            "-force-snapshot-load",
            "-read-only",
            "-no-snapshot-save",
            "-id",
            MANAGED_ID,
        ]
        self.record = {
            "nonce": NONCE,
            "purpose": "preparation",
            "launcherPid": 3806,
            "birth": BIRTH,
            "arguments": self.arguments,
        }
        self.valid = {
            "birth": BIRTH,
            "command": "/sdk/qemu-system-x86_64-headless " + shlex.join(self.arguments),
        }
        self.current = self.valid
        self.transition_at = None

    def sleep(self, seconds):
        self.now += seconds

    def retire(self, reader=None, on_signal=None):
        def signal_process(pid, action):
            self.assertEqual(3806, pid)
            self.signals.append(action.name)
            if on_signal is not None:
                on_signal(action.name)

        return PREPARATION["retire_preparation"](
            [self.record],
            NONCE,
            AVD,
            identity=lambda pid: reader() if reader is not None else self.current,
            signal_process=signal_process,
            sleep=self.sleep,
            monotonic=lambda: self.now,
            grace=1,
        )

    def retiring_reader(self):
        if self.transition_at is None:
            return self.valid
        if self.now >= self.transition_at + 0.1:
            return None
        return {"birth": BIRTH, "command": BRACKET_COMMAND}

    def test_same_birth_bracket_after_term_waits_for_confirmed_absence(self):
        def stop(action):
            self.transition_at = self.now

        result = self.retire(self.retiring_reader, stop)
        self.assertEqual(["SIGTERM"], self.signals)
        self.assertTrue(result[0]["retired"])
        self.assertIsNone(self.retiring_reader())
        self.assertLessEqual(self.now, 1)

    def test_same_birth_bracket_after_kill_waits_for_confirmed_absence(self):
        def stop(action):
            if action == "SIGKILL":
                self.transition_at = self.now

        result = self.retire(self.retiring_reader, stop)
        self.assertEqual(["SIGTERM", "SIGKILL"], self.signals)
        self.assertTrue(result[0]["retired"])
        self.assertIsNone(self.retiring_reader())
        self.assertLessEqual(self.now, 1)

    def test_persistent_same_birth_bracket_is_not_retired_or_killed_again(self):
        def stop(action):
            self.current = {"birth": BIRTH, "command": BRACKET_COMMAND}

        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.retire(on_signal=stop)
        self.assertEqual(["SIGTERM"], self.signals)

    def test_unknown_command_before_signal_remains_refusal(self):
        self.current = {"birth": BIRTH, "command": BRACKET_COMMAND}
        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.retire()
        self.assertEqual([], self.signals)

    def test_reused_pid_before_signal_remains_refusal(self):
        self.current = {**self.valid, "birth": "new birth"}
        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.retire()
        self.assertEqual([], self.signals)

    def test_reused_pid_after_term_receives_no_kill(self):
        def stop(action):
            self.current = {"birth": "new birth", "command": BRACKET_COMMAND}

        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.retire(on_signal=stop)
        self.assertEqual(["SIGTERM"], self.signals)

    def test_live_foreign_command_after_term_receives_no_kill(self):
        def stop(action):
            self.current = {"birth": BIRTH, "command": "/foreign/process --live"}

        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.retire(on_signal=stop)
        self.assertEqual(["SIGTERM"], self.signals)

    def test_changed_managed_id_after_term_receives_no_kill(self):
        def stop(action):
            self.current = {
                **self.valid,
                "command": self.valid["command"].replace(
                    MANAGED_ID,
                    "d67edde4-4f8e-41ad-a826-a385417a685b_GradleManagedDevice",
                ),
            }

        with self.assertRaisesRegex(RuntimeError, "identity changed"):
            self.retire(on_signal=stop)
        self.assertEqual(["SIGTERM"], self.signals)

    def test_writable_or_wrong_nonce_or_wrong_purpose_is_not_signaled(self):
        for key, value in [
            ("arguments", [x for x in self.arguments if x != "-read-only"]),
            ("nonce", "foreign"),
            ("purpose", "drivers"),
        ]:
            with self.subTest(key=key):
                original = self.record.copy()
                self.record[key] = value
                with self.assertRaises(RuntimeError):
                    self.retire()
                self.assertEqual([], self.signals)
                self.record = original

    def test_verified_live_term_resistance_still_requires_kill_and_absence(self):
        def stop(action):
            if action == "SIGKILL":
                self.current = None

        result = self.retire(on_signal=stop)
        self.assertEqual(["SIGTERM", "SIGKILL"], self.signals)
        self.assertTrue(result[0]["retired"])
        self.assertIsNone(self.current)

    def test_live_process_after_both_signals_is_not_retired(self):
        with self.assertRaisesRegex(RuntimeError, "did not retire"):
            self.retire()
        self.assertEqual(["SIGTERM", "SIGKILL"], self.signals)
        self.assertIsNotNone(self.current)


if __name__ == "__main__":
    unittest.main()
