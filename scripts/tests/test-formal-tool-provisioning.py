#!/usr/bin/env python3
"""Formal prerequisite transport checks; no network, JVM or compiler process."""

import hashlib
import time
import contextlib
import email.message
import urllib.request
import urllib.response
from unittest import mock
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


class ScriptedHTTPS(urllib.request.BaseHandler):
    handler_order = 100

    def __init__(self, frames):
        self.frames = list(frames)
        self.requests = []
        self.responses = []

    def https_open(self, request):
        self.requests.append(request.full_url)
        if not self.frames:
            raise AssertionError("Request budget exhausted")
        code, location, payload = self.frames.pop(0)
        headers = email.message.Message()
        if location:
            headers["Location"] = location
        headers["Content-Length"] = str(len(payload))
        response = urllib.response.addinfourl(
            io.BytesIO(payload), headers, request.full_url, code
        )
        response.msg = "OK" if code == 200 else "Transient response"
        self.responses.append(response)
        return response


class TransportRetryTests(unittest.TestCase):
    module = ProvisioningTests.module
    meta = ProvisioningTests.meta

    def attempt(self, frames):
        module = self.module()
        transport = ScriptedHTTPS(frames)
        opener = urllib.request.build_opener(transport, module.MavenRedirect())
        return module, transport, opener.open

    def test_redirect_then_direct_success_retries_only_original_url(self):
        location = "https://user:password@untrusted.example/path.jar?secret=private"
        module, transport, fetch = self.attempt(
            [(302, location, b""), (200, None, b"verified jar fixture")]
        )
        captured = io.StringIO()
        with (
            tempfile.TemporaryDirectory() as directory,
            contextlib.redirect_stderr(captured),
        ):
            root = Path(directory)
            try:
                module.ensure_input(
                    root, "tool.jar", self.meta(), url=URL, opener=fetch
                )
            except Exception as error:
                self.fail(
                    "Exact-original retry did not recover: " + type(error).__name__
                )
            self.assertEqual((root / "tool.jar").read_bytes(), b"verified jar fixture")
        self.assertEqual(transport.requests, [URL, URL])
        self.assertTrue(all(response.closed for response in transport.responses))
        self.assertIn("untrusted.example", captured.getvalue())
        self.assertNotIn("password", captured.getvalue())
        self.assertNotIn("private", captured.getvalue())

    def test_persistent_redirect_is_bounded_and_every_response_closed(self):
        frames = [
            (302, "https://untrusted.example/blocked.jar?secret=private", b"")
        ] * 3
        module, transport, fetch = self.attempt(frames)
        with (
            tempfile.TemporaryDirectory() as directory,
            contextlib.redirect_stderr(io.StringIO()),
        ):
            root = Path(directory)
            try:
                module.ensure_input(
                    root, "tool.jar", self.meta(), url=URL, opener=fetch
                )
            except Exception as error:
                self.assertIsInstance(error, ValueError)
                self.assertNotIn("private", str(error))
            else:
                self.fail("Persistent redirect must fail closed")
            self.assertFalse((root / "tool.jar").exists())
        self.assertEqual(transport.requests, [URL] * 3)
        self.assertTrue(all(response.closed for response in transport.responses))

    def test_transient_http_exhaustion_is_bounded_and_closed(self):
        module, transport, fetch = self.attempt(
            [(503, None, b"service unavailable")] * 3
        )
        with (
            tempfile.TemporaryDirectory() as directory,
            contextlib.redirect_stderr(io.StringIO()),
        ):
            try:
                module.ensure_input(
                    Path(directory), "tool.jar", self.meta(), url=URL, opener=fetch
                )
            except Exception as error:
                self.assertIsInstance(error, ValueError)
            else:
                self.fail("Persistent transient failure must exhaust its finite budget")
        self.assertEqual(transport.requests, [URL] * 3)
        self.assertTrue(all(response.closed for response in transport.responses))

    def test_elapsed_window_prevents_another_request(self):
        module, transport, fetch = self.attempt([(503, None, b"wait")])
        clock = [0.0]

        def delay(_seconds):
            clock[0] = 61.0

        with (
            tempfile.TemporaryDirectory() as directory,
            contextlib.redirect_stderr(io.StringIO()),
            mock.patch.object(time, "monotonic", lambda: clock[0]),
            mock.patch.object(time, "sleep", delay),
        ):
            try:
                module.ensure_input(
                    Path(directory), "tool.jar", self.meta(), url=URL, opener=fetch
                )
            except Exception as error:
                self.assertIsInstance(error, ValueError)
            else:
                self.fail("Elapsed transport budget must fail closed")
        self.assertEqual(transport.requests, [URL])
        self.assertTrue(transport.responses[0].closed)

    def test_not_found_is_not_retried(self):
        module, transport, fetch = self.attempt([(404, None, b"not found")])
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(Exception):
                module.ensure_input(
                    Path(directory), "tool.jar", self.meta(), url=URL, opener=fetch
                )
        self.assertEqual(transport.requests, [URL])
        self.assertTrue(transport.responses[0].closed)

    def test_digest_failure_is_not_retried(self):
        module, transport, fetch = self.attempt(
            [(200, None, b"X" * len(b"verified jar fixture"))]
        )
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                module.ensure_input(
                    Path(directory), "tool.jar", self.meta(), url=URL, opener=fetch
                )
        self.assertEqual(transport.requests, [URL])
        self.assertTrue(transport.responses[0].closed)


class PublisherRouteTests(unittest.TestCase):
    module = ProvisioningTests.module
    meta = ProvisioningTests.meta
    attempt = TransportRetryTests.attempt
    origin = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.21/kotlin-compiler-embeddable-2.3.21.jar"
    publisher = "https://github.com/JetBrains/kotlin/releases/download/v2.3.21/kotlin-compiler-embeddable-2.3.21.jar"
    asset = "https://release-assets.githubusercontent.com/github-production-release-asset/3432266/8c5a5b7a-09b2-47cf-9427-cdcb63c3596b"
    coordinate = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21"

    def pinned_fixture(self):
        data = b"verified jar fixture"
        return {
            **self.meta(data),
            "module": self.coordinate,
            "sha1": hashlib.sha1(data).hexdigest(),
        }

    def test_exact_chain_publishes_only_after_both_full_hashes(self):
        signed = self.asset + "?sig=private&token=secret"
        module, transport, fetch = self.attempt(
            [
                (301, self.publisher, b""),
                (302, signed, b""),
                (200, None, b"verified jar fixture"),
            ]
        )
        with tempfile.TemporaryDirectory() as directory:
            target = module.ensure_input(
                Path(directory),
                "compiler.jar",
                self.pinned_fixture(),
                url=self.origin,
                opener=fetch,
            )
            self.assertEqual(target.read_bytes(), b"verified jar fixture")
        self.assertEqual(transport.requests, [self.origin, self.publisher, signed])
        self.assertTrue(all(r.closed for r in transport.responses))

    def rejected_chain(self, locations, *, meta=None):
        frames = [
            (301 if index == 0 else 302, location, b"")
            for index, location in enumerate(locations)
        ] * 3
        module, transport, fetch = self.attempt(frames)
        captured = io.StringIO()
        with (
            tempfile.TemporaryDirectory() as directory,
            contextlib.redirect_stderr(captured),
        ):
            with self.assertRaises(ValueError) as failure:
                module.ensure_input(
                    Path(directory),
                    "compiler.jar",
                    meta or self.pinned_fixture(),
                    url=self.origin,
                    opener=fetch,
                )
            self.assertFalse((Path(directory) / "compiler.jar").exists())
        self.assertTrue(all(r.closed for r in transport.responses))
        text = str(failure.exception) + captured.getvalue()
        self.assertNotIn("private", text)
        self.assertNotIn("password", text)
        return transport.requests

    def test_other_module_cannot_follow_publisher(self):
        requests = self.rejected_chain(
            [self.publisher], meta={**self.pinned_fixture(), "module": "other:module:1"}
        )
        self.assertEqual(requests, [self.origin] * 3)

    def test_publisher_and_asset_path_host_and_authority_are_exact(self):
        for bad in [
            self.publisher + "?secret=private",
            self.publisher + "#private",
            self.publisher.replace("github.com", "github.com:443"),
            self.publisher.replace("github.com", "user:password@github.com"),
            self.publisher.replace("2.3.21.jar", "2.3.22.jar"),
            self.publisher.replace("github.com", "untrusted.example"),
        ]:
            with self.subTest(bad=bad):
                self.assertEqual(self.rejected_chain([bad]), [self.origin] * 3)
        for bad in [
            self.asset + "/wrong?token=private",
            self.asset.replace("https:", "http:"),
            self.asset.replace(
                "release-assets.githubusercontent.com", "untrusted.example"
            ),
            self.asset + "?token=private#private",
            self.asset.replace(
                "release-assets.githubusercontent.com",
                "user:password@release-assets.githubusercontent.com",
            ),
            self.asset.replace(
                "release-assets.githubusercontent.com",
                "release-assets.githubusercontent.com:443",
            ),
        ]:
            with self.subTest(bad=bad):
                self.assertEqual(
                    self.rejected_chain([self.publisher, bad]),
                    [self.origin, self.publisher] * 3,
                )

    def test_skip_hop_and_loop_are_not_followed(self):
        self.assertEqual(
            self.rejected_chain([self.asset + "?token=private"]), [self.origin] * 3
        )
        self.assertEqual(
            self.rejected_chain([self.publisher, self.origin]),
            [self.origin, self.publisher] * 3,
        )
        # No third redirect may be followed, even back to the known publisher.
        requests = self.rejected_chain(
            [self.publisher, self.asset + "?token=private", self.publisher]
        )
        self.assertEqual(
            requests, [self.origin, self.publisher, self.asset + "?token=private"] * 3
        )

    def test_chain_digest_failure_is_not_retried_or_published(self):
        for key in ["sha256", "sha1"]:
            module, transport, fetch = self.attempt(
                [
                    (301, self.publisher, b""),
                    (302, self.asset + "?token=private", b""),
                    (200, None, b"verified jar fixture"),
                ]
            )
            meta = self.pinned_fixture()
            meta[key] = "0" * len(meta[key])
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ValueError, "digest changed"):
                    module.ensure_input(
                        Path(directory),
                        "compiler.jar",
                        meta,
                        url=self.origin,
                        opener=fetch,
                    )
                self.assertFalse((Path(directory) / "compiler.jar").exists())
            self.assertEqual(len(transport.requests), 3)
            self.assertTrue(all(r.closed for r in transport.responses))

    def test_asset_error_logs_never_expose_signed_query(self):
        module, transport, fetch = self.attempt(
            [
                (301, self.publisher, b""),
                (302, self.asset + "?token=private", b""),
                (503, None, b""),
            ]
            * 3
        )
        captured = io.StringIO()
        with (
            tempfile.TemporaryDirectory() as directory,
            contextlib.redirect_stderr(captured),
        ):
            with self.assertRaises(ValueError) as failure:
                module.ensure_input(
                    Path(directory),
                    "compiler.jar",
                    self.pinned_fixture(),
                    url=self.origin,
                    opener=fetch,
                )
        self.assertNotIn("private", captured.getvalue() + str(failure.exception))
        self.assertEqual(len(transport.requests), 9)
        self.assertTrue(all(r.closed for r in transport.responses))


if __name__ == "__main__":
    unittest.main()
