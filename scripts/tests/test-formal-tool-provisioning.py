#!/usr/bin/env python3
"""Formal prerequisite transport checks; no network, JVM or compiler process."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VENDOR = ".hypertext/formatter-tools/vendor/tla2tools-2ef2af8.jar"
URL = "https://repo.maven.apache.org/maven2/example/tool/1/tool-1.jar"


class Response(io.BytesIO):
    def __init__(self, payload, url=URL):
        super().__init__(payload)
        self.url = url

    def geturl(self):
        return self.url


class ProvisioningTests(unittest.TestCase):
    def module(self):
        path = ROOT / "scripts/ci/provision-formal-tools.py"
        self.assertTrue(
            path.is_file(), "Missing explicit verified formal-input provisioning"
        )
        spec = importlib.util.spec_from_file_location("formal_provision", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def meta(self, data=b"verified jar fixture"):
        return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    def test_workflow_provisions_exact_jdk_and_inputs_before_offline_setup(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        scripts = workflow.split("  scripts:\n", 1)[1].split("  android-release:\n", 1)[
            0
        ]
        self.assertIn("distribution: jetbrains", scripts)
        self.assertRegex(scripts, r"""java-version: ['"]21\.0\.10\+1163\.108['"]""")
        self.assertIn("uses: actions/setup-java@v6.0.1", scripts)
        self.assertLess(
            scripts.index("provision-formal-tools.py"), scripts.index("./run setup")
        )
        self.assertIn("./scripts/tests/run-all.sh", scripts)

    def test_same_vendored_tlc_and_embedded_licenses_are_preserved(self):
        path = ROOT / VENDOR
        self.assertTrue(
            path.is_file(), "Pinned TLC must be available on a fresh runner"
        )
        self.assertEqual(path.stat().st_size, 4502259)
        self.assertEqual(
            hashlib.sha256(path.read_bytes()).hexdigest(),
            "d15655eeaff4b3f7b3d65c46b838bc0f35d131b2ef4b1e473689b62c98d4726f",
        )
        with zipfile.ZipFile(path) as jar:
            for name in (
                "License.txt",
                "META-INF/LICENSE.md",
                "CommonsMath-LICENSE.txt",
                "CommonsMath-NOTICE.txt",
                "jline-LICENSE.txt",
            ):
                self.assertIn(name, jar.namelist())

    def test_binary_classification_is_exact(self):
        profile = json.loads((ROOT / ".hypertext/formatters.json").read_text())
        matches = [x for x in profile["exclude"] if VENDOR in x["include"]]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0]["include"], [VENDOR])
        self.assertEqual(matches[0]["kind"], "binary")

    def test_seven_maven_inputs_use_existing_recipe_coordinates_and_pins(self):
        module = self.module()
        recipe = json.loads(
            (ROOT / ".hypertext/formatter-tools/formal-tools.json").read_text()
        )
        inputs = module.maven_inputs(recipe)
        self.assertEqual(len(inputs), 7)
        for actual, expected in zip(inputs, recipe["kotlin_modules"]):
            group, artifact, version = expected["module"].split(":")
            self.assertEqual(
                actual["url"],
                f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar",
            )
            self.assertEqual(actual["sha256"], expected["sha256"])
            self.assertEqual(actual["bytes"], expected["bytes"])
            self.assertEqual(
                actual["relative"],
                "caches/modules-2/files-2.1/" + expected["cache_path"],
            )

    def test_fresh_verified_bytes_are_published(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module.ensure_input(
                root,
                "tool.jar",
                self.meta(),
                url=URL,
                opener=lambda *_a, **_k: Response(b"verified jar fixture"),
            )
            self.assertEqual((root / "tool.jar").read_bytes(), b"verified jar fixture")
            self.assertEqual((root / "tool.jar").stat().st_nlink, 1)

    def test_wrong_digest_never_publishes_bytes(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                module.ensure_input(
                    root,
                    "tool.jar",
                    self.meta(),
                    url=URL,
                    opener=lambda *_a, **_k: Response(b"corrupted jar bytes!"),
                )
            self.assertFalse((root / "tool.jar").exists())

    def test_oversized_download_stops_without_publication(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                module.ensure_input(
                    root,
                    "tool.jar",
                    self.meta(),
                    url=URL,
                    opener=lambda *_a, **_k: Response(b"x" * 10000),
                )
            self.assertFalse((root / "tool.jar").exists())

    def test_short_download_stops_without_publication(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                module.ensure_input(
                    root,
                    "tool.jar",
                    self.meta(),
                    url=URL,
                    opener=lambda *_a, **_k: Response(b"short"),
                )
            self.assertFalse((root / "tool.jar").exists())

    def test_good_cache_is_reused_without_network(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tool.jar").write_bytes(b"verified jar fixture")

            def forbidden(*_a, **_k):
                self.fail("A matching verified input must not be downloaded")

            module.ensure_input(
                root, "tool.jar", self.meta(), url=URL, opener=forbidden
            )

    def test_corrupt_existing_cache_is_never_overwritten(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tool.jar").write_bytes(b"keep corruption for inspection")
            with self.assertRaises(ValueError):
                module.ensure_input(root, "tool.jar", self.meta(), url=URL)
            self.assertEqual(
                (root / "tool.jar").read_bytes(), b"keep corruption for inspection"
            )

    def test_symlinked_cache_parent_is_refused(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "outside").mkdir()
            (root / "alias").symlink_to(root / "outside", target_is_directory=True)
            with self.assertRaises(ValueError):
                module.ensure_input(root, "alias/tool.jar", self.meta(), url=URL)
            self.assertFalse((root / "outside/tool.jar").exists())

    def test_symlinked_cache_leaf_is_refused(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "outside").write_bytes(b"untouched")
            (root / "tool.jar").symlink_to(root / "outside")
            with self.assertRaises(ValueError):
                module.ensure_input(root, "tool.jar", self.meta(), url=URL)
            self.assertEqual((root / "outside").read_bytes(), b"untouched")

    def test_cache_path_escape_is_refused(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                module.ensure_input(
                    Path(directory), "../outside.jar", self.meta(), url=URL
                )

    def test_unexpected_download_host_is_refused(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                module.ensure_input(
                    Path(directory),
                    "tool.jar",
                    self.meta(),
                    url="https://example.com/other.jar",
                )

    def test_rejected_response_is_closed_without_publication(self):
        module = self.module()
        response = Response(
            b"verified jar fixture", url="https://example.com/redirect.jar"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                module.ensure_input(
                    root,
                    "tool.jar",
                    self.meta(),
                    url=URL,
                    opener=lambda *_a, **_k: response,
                )
            self.assertTrue(response.closed)
            self.assertFalse((root / "tool.jar").exists())

    def test_changed_vendor_is_rejected_even_if_cache_matches(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tool.jar").write_bytes(b"verified jar fixture")
            vendor = root / "vendor.jar"
            vendor.write_bytes(b"changed vendor")
            with self.assertRaises(ValueError):
                module.ensure_input(root, "tool.jar", self.meta(), source=vendor)


if __name__ == "__main__":
    unittest.main()
