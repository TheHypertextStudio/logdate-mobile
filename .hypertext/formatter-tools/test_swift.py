"""Verify pinned SwiftFormat stdin behavior and native configuration ownership."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

DIRECTORY = Path(__file__).resolve().parent
HELPER = DIRECTORY / "native.py"
if "SWIFT_VERSION" not in HELPER.read_text():
    HELPER = DIRECTORY / "native-addons.py"


def format_source(source, helper=HELPER, environment=None, cwd=None):
    return subprocess.run(
        [sys.executable, str(helper), "swift", "--path", "src/Sample.swift"],
        input=source,
        capture_output=True,
        env=environment,
        cwd=cwd,
    )


class SwiftFormatterTests(unittest.TestCase):
    def test_stdin_formats_and_is_idempotent(self):
        first = format_source(b"let value=1\n")
        self.assertEqual(first.returncode, 0, first.stderr.decode())
        self.assertEqual(first.stdout, b"let value = 1\n")
        second = format_source(first.stdout)
        self.assertEqual(second.returncode, 0, second.stderr.decode())
        self.assertEqual(second.stdout, first.stdout)

    def test_invalid_swift_does_not_return_replacement_source(self):
        result = format_source(b'let broken = "')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")
        self.assertTrue(result.stderr)

    def test_missing_cache_blocks_without_network_or_cache_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            result = format_source(
                b"let value=1\n",
                environment=dict(os.environ, XDG_CACHE_HOME=directory),
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, b"")
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_existing_config_controls_style_without_changing_working_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / ".hypertext/formatter-tools" / HELPER.name
            helper.parent.mkdir(parents=True)
            shutil.copyfile(HELPER, helper)
            (root / ".swiftformat").write_text("--indent 2\n")
            source = root / "src/Sample.swift"
            source.parent.mkdir()
            before = b"private draft must remain unchanged\n"
            source.write_bytes(before)
            result = format_source(
                b"func sample(){\nprint(1)\n}\n", helper, cwd=root.parent
            )
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            self.assertIn(b"\n  print(1)\n", result.stdout)
            self.assertEqual(source.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
