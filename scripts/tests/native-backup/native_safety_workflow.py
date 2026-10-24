import re

"""Native acceptance safety contracts for workflow."""

import json
import runpy
from pathlib import Path
import tempfile
from unittest.mock import patch

from native_safety_support import D2D, ROOT


class NativeWorkflowSafety:
    def test_retained_key_acceptance_rejects_skips_despite_a_success_summary(self):
        post = runpy.run_path(
            str(ROOT / "tests/e2e/native-post-transfer-acceptance.py")
        )
        for status, ending in (
            ("0", ""),
            ("-3", ""),
            ("-4", ""),
            ("-3", "\r"),
            ("-4", "\r"),
        ):
            result = f"INSTRUMENTATION_STATUS_CODE: {status}{ending}\nOK (8 tests)\n".encode()
            with (
                self.subTest(status=status, ending=ending),
                tempfile.TemporaryDirectory() as temporary,
                patch.dict(post["NATIVE"], {"adb": lambda *args: result}),
            ):
                output = Path(temporary) / "retained-keys.log"
                if status == "0":
                    post["instrument"]("emulator-5556", "fixture.Keys", 8, output)
                else:
                    with self.assertRaisesRegex(
                        RuntimeError, "did not pass every required case"
                    ):
                        post["instrument"]("emulator-5556", "fixture.Keys", 8, output)
                self.assertEqual(result, output.read_bytes())

    def test_native_ci_retains_all_scenarios_without_manual_emulator_lifecycle(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        for forbidden in (
            "avdmanager create",
            "emulator -avd",
            "adb -s",
            "emu kill",
            "pm clear",
            "adb uninstall",
        ):
            self.assertNotIn(forbidden, workflow)
        for scenario in (
            "partial",
            "publication",
            "contracts",
            "missing-keys",
            "credentials-only",
            "privacy",
            "work-restart",
            "d2d",
            "enrollment",
        ):
            self.assertIn(f"{scenario})", workflow)
        self.assertIn("needs: native-transfer-scenarios", workflow)
        self.assertIn('test "$RESULT" = success', workflow)

    def test_native_restore_workflow_runs_separate_gms_d2d_test_mode(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        d2d_harness = (ROOT / "tests/e2e/native-d2d-acceptance.py").read_text()
        self.assertIn("system-images;android-34;google_apis_playstore;x86_64", workflow)
        self.assertIn("--mode d2d", workflow)
        controller = (
            ROOT / "tests/e2e/managed-native-restore-acceptance.py"
        ).read_text()
        self.assertIn('"d2d": "native-d2d-acceptance.py"', controller)
        self.assertIn("single-device D2D test-mode simulation", d2d_harness)
        self.assertIn("does not prove setup-wizard transfer", d2d_harness)
        self.assertIn(
            "https://developer.android.com/identity/data/testingbackup", d2d_harness
        )
        self.assertIn("backup_enable_d2d_test_mode", d2d_harness)
        self.assertIn(D2D.D2D_TRANSPORT, d2d_harness)
        self.assertIn('"native-restore-status"', d2d_harness)
        self.assertIn('"verify-in-place"', d2d_harness)
        probe = (
            ROOT
            / "app/android-main/src/androidTest/kotlin/app/logdate/client/e2e/NativeBackupTransferProbeTest.kt"
        ).read_text()
        self.assertIn("PostRestoreType.CONTENT_RESTORED", probe)
        self.assertIn("native-transfer-api-36-1:", workflow)
        self.assertIn("platforms;android-36.1", workflow)
        self.assertIn(
            "NativeBackgroundCredentialCallbackTest",
            workflow,
        )

    def test_api_36_1_uses_managed_lifecycle_and_requires_the_exact_minor_sdk(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        scenario = workflow.split("  native-transfer-api-36-1:", 1)[1]
        self.assertNotIn("avdmanager create", scenario)
        self.assertNotIn("emulator -avd", scenario)
        self.assertNotIn("adb -s", scenario)
        self.assertIn("managed-modern-callback-acceptance.sh", scenario)
        runner = (ROOT / "tests/e2e/managed-modern-callback-acceptance.sh").read_text()
        self.assertIn(
            ":tools:native-restore-driver:restoreCallbackApi36_1DebugAndroidTest",
            runner,
        )
        driver = (
            ROOT
            / "tools/native-restore-driver/src/androidTest/kotlin/app/logdate/tools/restoredriver/NativeModernRestoreCallbackTest.kt"
        ).read_text()
        self.assertIn("nativeRestoreRequiredSdkFull 3600001", driver)
        self.assertIn("--no-hidden-api-checks", driver)
        self.assertIn("requireAttestedApks()", driver)
        self.assertIn("native-modern-callback-driver-cases.json", runner)
        self.assertIn("native-modern-callback-cases.json", runner)
        self.assertIn("--managed-results", runner)
        self.assertNotIn("am instrument", runner)

    def test_api_36_restore_probe_fails_if_full_restore_callback_is_missing(self):
        probe = (
            ROOT
            / "app/android-main/src/androidTest/kotlin/app/logdate/client/e2e/NativeBackgroundCredentialCallbackTest.kt"
        ).read_text()
        support = (
            ROOT
            / "app/android-main/src/androidTest/kotlin/app/logdate/client/e2e/NativeBackgroundRestorePreferences.kt"
        ).read_text()
        self.assertIn(
            "if (!transferredPreferences.supportsFullRestoreInput()) return", probe
        )
        self.assertIn(
            "private val transferredPreferences by lazy { NativeBackgroundRestorePreferences(context, folder) }",
            probe,
        )
        self.assertIn(
            "Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1",
            support,
        )
        self.assertIn("assertNotNull(", probe)
        self.assertIn("android.app.backup.FullRestoreDataInput", probe)

    def test_background_restore_callback_checks_do_not_launch_an_activity(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        self.assertIn("--mode work-restart", workflow)
        post = (ROOT / "tests/e2e/native-post-transfer-acceptance.py").read_text()
        self.assertNotIn("am start", post)
        contract = json.loads(
            (ROOT / "tests/e2e/native-modern-callback-cases.json").read_text()
        )
        for classname in contract:
            callback_test = (
                ROOT
                / "app/android-main/src/androidTest/kotlin"
                / (classname.replace(".", "/") + ".kt")
            ).read_text()
            self.assertIn(
                'getDir("native-restore-probe", Context.MODE_PRIVATE)', callback_test
            )
            self.assertNotIn("application.noBackupFilesDir", callback_test)

    def test_native_restore_ci_exercises_the_real_cloud_quota_rejection_callback(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        self.assertIn("--exercise-quota", workflow)

    def test_native_restore_ci_checks_a_populated_signed_out_destination(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        self.assertIn("--mode publication", workflow)
        self.assertIn("managed-native-restore-acceptance.py", workflow)
        publication = (ROOT / "tests/e2e/native-publication-acceptance.py").read_text()
        self.assertIn("exercise_data_only=True", publication)

    def test_native_restore_ci_runs_missing_media_and_network_recovery(self):
        workflow = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        self.assertIn("--after-transfer missing-media", workflow)
        controller = (
            ROOT / "tests/e2e/managed-native-restore-acceptance.py"
        ).read_text()
        self.assertIn(
            '"missing-media": "native-missing-media-acceptance.py"', controller
        )
        self.assertIn('"--synthetic-data"', controller)
        self.assertIn("ArchiveMediaRecoveryE2ETest.kt", workflow)
        self.assertIn("CloudArchiveWorkerFixture.kt", workflow)
        self.assertIn("app/compose-main/src/androidMain/", workflow)
        self.assertIn("client/**", workflow)
        self.assertIn("shared/model/**", workflow)

    def test_modern_callback_contract_covers_every_source_case_without_a_stale_driver_count(
        self,
    ):
        contract = json.loads(
            (ROOT / "tests/e2e/native-modern-callback-cases.json").read_text()
        )
        for classname, expected in contract.items():
            source = (
                ROOT
                / "app/android-main/src/androidTest/kotlin"
                / (classname.replace(".", "/") + ".kt")
            ).read_text()
            methods = [
                quoted or plain
                for quoted, plain in re.findall(
                    r"@Test\s+fun\s+(?:`([^`]+)`|(\w+))", source
                )
            ]
            self.assertCountEqual(expected, methods)
        driver = (
            ROOT
            / "tools/native-restore-driver/src/androidTest/kotlin/app/logdate/tools/restoredriver/NativeModernRestoreCallbackTest.kt"
        ).read_text()
        declared_counts = [
            int(value)
            for value in re.findall(
                r"assertEquals\(\s*(\d+),\s*cases\.size\s*\)", driver
            )
        ]
        self.assertTrue(declared_counts, "Keep the driver's explicit cardinality gate")
        self.assertEqual(
            {sum(len(methods) for methods in contract.values())},
            set(declared_counts),
            "Every driver count must match the complete current source contract",
        )
