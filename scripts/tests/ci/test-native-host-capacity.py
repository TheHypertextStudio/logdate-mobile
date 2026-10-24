"""Constrain disposable-host cleanup and reject insufficient native capacity."""

from pathlib import Path
import importlib.util
import json
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "native_host", ROOT / "scripts/ci/prepare-native-host.py"
)
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
KEEP = "system-images;android-34;google_apis;x86_64"


class NativeHostCapacityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.sdk = self.root / "sdk"
        self.sdk.mkdir()
        self.dotnet = self.root / "dotnet"
        self.dotnet.mkdir()
        self.env = {
            "GITHUB_ACTIONS": "true",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "Linux",
            "GITHUB_RUN_ID": "123",
            "GITHUB_RUN_ATTEMPT": "1",
            "ANDROID_HOME": str(self.sdk),
            "ANDROID_SDK_ROOT": str(self.sdk),
        }
        self.addCleanup(patch.stopall)
        patch.object(HOST, "SDK", self.sdk).start()
        patch.object(HOST, "DOTNET", self.dotnet).start()

    def image(self, name):
        directory = self.sdk / name.replace(";", "/")
        directory.mkdir(parents=True)
        (directory / "package.xml").write_text("installed image")
        return directory

    def test_cleanup_only_selects_unused_images_and_dotnet(self):
        kept = self.image(KEEP)
        unused_name = "system-images;android-37;google_apis_playstore;x86_64"
        self.image(unused_name)
        for name in ["emulator", "platforms", "build-tools", "ndk"]:
            (self.sdk / name).mkdir()
        run = Mock()
        report = HOST.reclaim(self.env, KEEP, run=run)
        self.assertEqual([unused_name], report["removedImages"])
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(2, len(commands))
        self.assertEqual(
            ["sudo", "--", "rm", "-rf", "--", str(self.dotnet)], commands[0]
        )
        self.assertEqual(
            [
                str(self.sdk / "cmdline-tools/latest/bin/sdkmanager"),
                "--uninstall",
                unused_name,
            ],
            commands[1],
        )
        self.assertTrue(kept.exists())
        self.assertTrue(
            all(
                (self.sdk / n).exists()
                for n in ["emulator", "platforms", "build-tools", "ndk"]
            )
        )
        self.assertTrue(
            all(
                call.kwargs["check"] and call.kwargs["timeout"] <= 120
                for call in run.call_args_list
            )
        )

    def test_local_self_hosted_and_non_linux_invocations_never_clean(self):
        for change in [
            {"GITHUB_ACTIONS": "false"},
            {"RUNNER_ENVIRONMENT": "self-hosted"},
            {"RUNNER_OS": "macOS"},
            {"GITHUB_RUN_ID": ""},
        ]:
            with self.subTest(change=change):
                run = Mock()
                with self.assertRaisesRegex(RuntimeError, "hosted Linux"):
                    HOST.reclaim({**self.env, **change}, KEEP, run=run)
                run.assert_not_called()

    def test_unknown_sdk_root_and_mismatched_alias_never_clean(self):
        for name in ["ANDROID_HOME", "ANDROID_SDK_ROOT"]:
            run = Mock()
            with self.assertRaisesRegex(RuntimeError, "SDK"):
                HOST.reclaim({**self.env, name: str(self.root)}, KEEP, run=run)
            run.assert_not_called()

    def test_cleanup_rejects_symlinked_roots_before_any_command(self):
        self.dotnet.rmdir()
        self.dotnet.symlink_to(self.sdk, target_is_directory=True)
        run = Mock()
        with self.assertRaisesRegex(RuntimeError, "symlink"):
            HOST.reclaim(self.env, KEEP, run=run)
        run.assert_not_called()

    def test_image_path_escape_and_symlink_are_rejected(self):
        run = Mock()
        with self.assertRaises(ValueError):
            HOST.reclaim(
                self.env, "system-images;../../home;google_apis;x86_64", run=run
            )
        run.assert_not_called()
        linked = self.sdk / "system-images/android-35/google_apis"
        linked.mkdir(parents=True)
        (linked / "x86_64").symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, "symlink"):
            HOST.reclaim(self.env, KEEP, run=run)
        run.assert_not_called()

    def test_dangling_image_root_is_rejected_before_cleanup(self):
        (self.sdk / "system-images").symlink_to(
            self.root / "missing", target_is_directory=True
        )
        run = Mock()
        with self.assertRaisesRegex(RuntimeError, "symlink"):
            HOST.reclaim(self.env, KEEP, run=run)
        run.assert_not_called()

    def test_failed_cli_preflight_retains_private_evidence_and_exits_unsuccessfully(
        self,
    ):
        env = {
            **self.env,
            "RUNNER_TEMP": str(self.root / "temp"),
            "GITHUB_WORKSPACE": str(self.root),
        }
        result = {"ready": False, "minimumFreeBytes": HOST.MIN_FREE, "filesystems": []}
        with (
            patch.dict(HOST.os.environ, env, clear=True),
            patch.object(
                HOST.sys,
                "argv",
                ["prepare-native-host.py", "check", "--path", str(self.root)],
            ),
            patch.object(HOST, "check_capacity", return_value=result),
        ):
            with self.assertRaisesRegex(SystemExit, "12 GiB"):
                HOST.main()
        proof = self.root / "temp/native-inputs/native-host-capacity-check.json"
        self.assertEqual(result, json.loads(proof.read_text()))
        self.assertEqual(0o600, proof.stat().st_mode & 0o777)

    def test_capacity_uses_the_actual_filesystems_without_creating_directories(self):
        future = self.root / "future/avd"
        usage = Mock(return_value=SimpleNamespace(free=HOST.MIN_FREE))
        report = HOST.check_capacity([future], disk_usage=usage)
        self.assertTrue(report["ready"])
        usage.assert_called_once_with(self.root)
        self.assertFalse(future.exists())

    def test_one_insufficient_filesystem_keeps_preflight_failed(self):
        other = self.root / "other"
        other.mkdir()
        usage = Mock(
            side_effect=[
                SimpleNamespace(free=HOST.MIN_FREE + 1),
                SimpleNamespace(free=HOST.MIN_FREE - 1),
            ]
        )
        report = HOST.check_capacity([self.root, other], disk_usage=usage)
        self.assertFalse(report["ready"])
        self.assertEqual(HOST.MIN_FREE, report["minimumFreeBytes"])
        self.assertEqual(HOST.MIN_FREE - 1, report["filesystems"][1]["freeBytes"])


if __name__ == "__main__":
    unittest.main()
