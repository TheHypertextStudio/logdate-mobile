import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class GitHookDispatchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Hook fixture")
        self.git("config", "user.email", "fixture@hypertext.studio")
        (self.repo / ".githooks").mkdir()
        (self.repo / "scripts/validation").mkdir(parents=True)
        (self.repo / ".hypertext/git-product").mkdir(parents=True)
        shutil.copyfile(
            ROOT / ".hypertext/commits.json", self.repo / ".hypertext/commits.json"
        )
        for hook in ("pre-commit", "commit-msg", "pre-push"):
            shutil.copyfile(ROOT / ".githooks" / hook, self.repo / ".githooks" / hook)
        helper = ROOT / "scripts/validation/run-git-hook.py"
        if helper.exists():
            shutil.copyfile(helper, self.repo / "scripts/validation" / helper.name)
        oid = self.git("hash-object", "-w", ".hypertext/commits.json").strip()
        self.git(
            "update-index",
            "--add",
            "--cacheinfo",
            "100644",
            oid,
            ".hypertext/commits.json",
        )
        self.hooks = {
            name: {"before": [], "after": []}
            for name in ("pre-commit", "commit-msg", "pre-push")
        }
        self.save_metadata()
        self.message = self.repo / ".git/message"
        self.message.write_text(
            "fix(settings): Open Android app preferences\n\nApp preferences open Settings.\n"
        )

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True)

    def save_metadata(self):
        (self.repo / ".hypertext/git-hooks.json").write_text(
            json.dumps({"schema_version": 1, "hooks": self.hooks})
        )

    def stage(self, hook, when, body):
        relative = ".hypertext/git-product/" + hook
        path = self.repo / relative
        path.write_text("#!/bin/sh\nset -eu\n" + body + "\n")
        path.chmod(0o755)
        self.hooks[hook][when].append(
            {"path": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        )
        self.save_metadata()
        return path

    def invoke(self, hook, *args, data=None):
        env = dict(os.environ)
        env.pop("HYPERTEXT_AGENT_COAUTHOR", None)
        return subprocess.run(
            ["sh", str(self.repo / ".githooks" / hook), *args],
            cwd=self.repo,
            env=env,
            input=data,
            capture_output=True,
        )

    def test_valid_message_runs_preserved_stage_and_shared_gate(self):
        self.stage("commit-msg", "before", "printf invoked > before-ran")
        result = self.invoke("commit-msg", str(self.message))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.repo / "before-ran").read_text(), "invoked")

    def test_invalid_message_is_rejected_by_shared_gate(self):
        self.message.write_text("fix(unknown-domain): Invalid scope\n")
        result = self.invoke("commit-msg", str(self.message))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"scope", result.stderr.lower())

    def test_preserved_precommit_failure_stops_shared_gate(self):
        self.stage("pre-commit", "before", "echo preserved-check-failed >&2; exit 23")
        result = self.invoke("pre-commit")
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertIn(b"preserved-check-failed", result.stderr)

    def test_non_studio_identity_is_still_rejected(self):
        self.git("config", "user.email", "fixture@example.com")
        result = self.invoke("pre-commit")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"outside @hypertext.studio", result.stderr)

    def test_prepush_preserves_exact_native_input_for_product_gate(self):
        self.stage("pre-push", "after", "cat > received-refs")
        transaction = (
            b"(delete) " + b"0" * 40 + b" refs/heads/obsolete " + b"1" * 40 + b"\n"
        )
        result = self.invoke("pre-push", "origin", str(self.repo), data=transaction)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.repo / "received-refs").read_bytes(), transaction)

    def test_modified_product_gate_is_rejected_before_execution(self):
        path = self.stage("commit-msg", "before", "printf ran > unexpected")
        path.write_text(path.read_text() + "echo modified\n")
        result = self.invoke("commit-msg", str(self.message))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"digest", result.stderr.lower())
        self.assertFalse((self.repo / "unexpected").exists())


if __name__ == "__main__":
    unittest.main()
