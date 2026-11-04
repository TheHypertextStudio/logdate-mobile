"""Keep scenario coverage and same-run build dependencies when splitting CI jobs."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]


def job(source, name):
    match = re.search(
        r"^  " + re.escape(name) + r":\n(.*?)(?=^  [\w-]+:|\Z)", source, re.M | re.S
    )
    return match.group(1) if match else ""


def step(source, name):
    match = re.search(
        r"^      - name: " + re.escape(name) + r"\n(.*?)(?=^      - |\Z)",
        source,
        re.M | re.S,
    )
    return match.group(1) if match else ""


class AcceptanceParallelismTest(unittest.TestCase):
    def setUp(self):
        self.native = (
            ROOT / ".github/workflows/native-restore-acceptance.yml"
        ).read_text()
        self.screenshots = (ROOT / ".github/workflows/screenshot-test.yml").read_text()

    def test_screenshot_shards_reuse_one_compilation_without_skipping_validation(self):
        producer = job(self.screenshots, "android-screenshot-compile")
        self.assertIn(":app:android-main:compileDebugScreenshotTestKotlin", producer)
        self.assertIn("actions/upload-artifact@", producer)
        shards = job(self.screenshots, "android-wear-screenshots")
        self.assertIn("needs: android-screenshot-compile", shards)
        self.assertIn("actions/download-artifact@", shards)
        self.assertIn("validateDebugScreenshotTest --continue", shards)
        self.assertIn("app.logdate.screenshots.audit.*", shards)
        self.assertIn("app.logdate.screenshots.components.*", shards)
        self.assertIn(
            "exclude: app.logdate.screenshots.audit.*,app.logdate.screenshots.components.*",
            shards,
        )
        self.assertNotIn("-x compile", shards)
        self.assertNotIn("continue-on-error:", producer + shards)

    def test_desktop_compilation_cannot_consume_the_rendering_budget(self):
        body = job(self.screenshots, "desktop-screenshots")
        compile_step = step(body, "Compile Desktop screenshot tests")
        validation = step(body, "Validate Desktop screenshots")
        self.assertIn(":app:compose-main:compileTestKotlinDesktop", compile_step)
        self.assertIn("./gradlew validateDesktopScreenshots --continue", validation)
        self.assertIn("LOGDATE_REQUIRE_DESKTOP_SCREENSHOTS: 'true'", validation)
        self.assertIn("runs-on: xcode-27", body)
        compile_budget = int(
            re.search(r"timeout-minutes: (\d+)", compile_step).group(1)
        )
        render_budget = int(re.search(r"timeout-minutes: (\d+)", validation).group(1))
        job_budget = int(re.search(r"timeout-minutes: (\d+)", body).group(1))
        # Reserve setup/report time even if both bounded phases use their full budgets.
        self.assertGreaterEqual(job_budget, compile_budget + render_budget + 10)
        self.assertLess(body.index(compile_step), body.index(validation))
        self.assertNotIn("continue-on-error:", body)
        self.assertNotIn("-x ", compile_step + validation)

    def test_native_compilation_is_shared_without_transferring_signing_or_device_state(
        self,
    ):
        producer = job(self.native, "native-apks")
        for task in [
            ":app:android-main:assembleDebugAndroidTest",
            ":tools:native-restore-driver:assembleDebugAndroidTest",
        ]:
            self.assertIn(task, producer)
        self.assertIn("native-acceptance-source.txt", producer)
        self.assertNotIn(".keystore", producer)
        self.assertNotIn(".avd", producer)
        for name in [
            "managed-startup",
            "native-transfer-scenarios",
            "native-transfer-api-36-1",
        ]:
            body = job(self.native, name)
            self.assertIn("needs: native-apks", body)
            self.assertIn("actions/download-artifact@", body)
            self.assertIn('/init.d"', body)
            self.assertIn("$init_dir/logdate-shared-native.gradle.kts", body)
            self.assertIn('"$GITHUB_SHA"', body)
            self.assertIn("native-acceptance-apks.sha256", body)

    def test_every_managed_scenario_remains_parallel_and_fail_closed(self):
        body = job(self.native, "native-transfer-scenarios")
        matrix = re.search(r"scenario:\s*\[([^\]]+)\]", body).group(1)
        scenarios = [item.strip() for item in matrix.split(",") if item.strip()]
        self.assertEqual(
            scenarios,
            [
                "partial",
                "archive",
                "publication",
                "contracts",
                "missing-keys",
                "credentials-only",
                "data-only",
                "privacy",
                "work-restart",
                "d2d",
                "enrollment",
                "widget",
            ],
        )
        self.assertIn("fail-fast: false", body)
        self.assertNotIn("max-parallel:", body)
        self.assertIn("managed-native-restore-acceptance.py", body)
        self.assertIn("writeAndroidHistoryHarnessClasspath", body)
        aggregate = job(self.native, "native-transfer")
        self.assertIn("if: always()", aggregate)
        self.assertIn("needs: native-transfer-scenarios", aggregate)
        self.assertIn('test "$RESULT" = success', aggregate)

    def test_managed_oracles_and_lifecycle_cannot_be_replaced_by_raw_emulators(self):
        for contract in [
            "native-key-recovery-cases.json",
            "native-recording-recovery-cases.json",
            "native-alarm-delivery-cases.json",
        ]:
            self.assertIn(contract, self.native)
        self.assertIn("managed-modern-callback-acceptance.sh", self.native)
        self.assertIn("native-managed/**", self.native)
        self.assertNotIn("continue-on-error:", self.native)
        self.assertNotIn("emu kill", self.native)
        self.assertNotIn("emulator -avd", self.native)


if __name__ == "__main__":
    unittest.main()
