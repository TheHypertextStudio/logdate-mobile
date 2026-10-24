"""Exercise the tracked hook entrypoints without a plugin install or app build.

Only excluded .gitignore content changes after fixture bootstrap, so the real
staged formatter runs without downloading tools. Product push checks execute a
recording gradlew fixture; the product wrappers and their pinned hashes are real.
"""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
COAUTHOR = "Co-authored-by: Codex <noreply@openai.com>"
VALID_MESSAGE = (
    "chore(dx): keep managed hooks available in each checkout\n\n"
    "Use repository-owned checks so commits do not depend on a plugin cache.\n\n"
    + COAUTHOR
    + "\n"
)


class ManagedHookEntrypointTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="managed-hook-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("GIT_", "HYPERTEXT_"))
        }
        self.environment.update(
            {
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_TERMINAL_PROMPT": "0",
                "PYTHONDONTWRITEBYTECODE": "1",
                "HYPERTEXT_AGENT_COAUTHOR": "Codex <noreply@openai.com>",
                "PATH": str(Path(sys.executable).parent)
                + os.pathsep
                + os.environ["PATH"],
            }
        )
        self.run_command("git", "init", "-q", "--initial-branch=main", ".")
        self.run_command("git", "config", "user.name", "Hook Fixture")
        self.run_command("git", "config", "user.email", "fixture@hypertext.studio")
        self.run_command("git", "config", "commit.gpgsign", "false")
        self.message = self.repo / ".git/fixture-message"
        self.message.write_text(VALID_MESSAGE)
        # Seed one tracked path before installing hooks: restore --staged .
        # rejects an empty tree. All tested staging uses the atomic chain below.
        seed = "# Fixture bootstrap\n"
        bootstrap = (
            "commit refs/heads/main\n"
            "committer Hook Fixture <fixture@hypertext.studio> 0 +0000\n"
            f"data {len(VALID_MESSAGE.encode())}\n{VALID_MESSAGE}\n"
            f"M 100644 inline .gitignore\ndata {len(seed.encode())}\n{seed}\n\n"
        )
        self.run_command("git", "fast-import", "--quiet", input=bootstrap)
        source_directories = (
            ".githooks",
            ".hypertext/formatting",
            ".hypertext/formatter-tools",
            ".hypertext/git-product",
            "scripts/validation",
        )
        tracked = subprocess.check_output(
            ["git", "-C", str(ROOT), "ls-files", "-z", "--", *source_directories],
            text=True,
        ).split("\0")
        for relative in filter(None, tracked):
            if Path(relative).suffix in {".jar", ".class", ".pyc"}:
                continue
            source = ROOT / relative
            if not source.is_file():
                continue
            destination = self.repo / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        for name in (
            ".hypertext/commits.json",
            ".hypertext/git-hooks.json",
            ".hypertext/formatters.json",
            ".gitignore",
        ):
            shutil.copy2(ROOT / name, self.repo / name)
        driver = ROOT / ".hypertext/git-hook.py"
        if driver.exists():
            shutil.copy2(driver, self.repo / ".hypertext/git-hook.py")
        (self.repo / "gradlew").write_text(
            "#!/bin/sh\n"
            'printf "%s|%s\\n" "$JAVA_TOOL_OPTIONS" "$*" >> .git/gradle-invocations\n'
            "if [ -f .git/fail-product-check ]; then exit 1; fi\n"
        )
        (self.repo / "gradlew").chmod(0o755)
        paths = sorted(
            str(path.relative_to(self.repo))
            for path in self.repo.rglob("*")
            if path.is_file() and ".git" not in path.relative_to(self.repo).parts
        )
        bootstrap = self.commit(paths, VALID_MESSAGE)
        self.assertEqual(bootstrap.returncode, 0, bootstrap.stdout + bootstrap.stderr)
        self.base = self.run_command("git", "rev-parse", "HEAD").stdout.strip()
        self.run_command("git", "update-ref", "refs/remotes/origin/main", self.base)
        self.run_command("git", "config", "core.hooksPath", ".githooks")

    def run_command(self, *arguments, check=True, input=None):
        return subprocess.run(
            arguments,
            cwd=self.repo,
            env=self.environment,
            input=input,
            text=True,
            capture_output=True,
            check=check,
            timeout=60,
        )

    def commit(self, paths, message, check=False):
        self.message.write_text(message)
        # Keep reset, explicit staging and commit in the same atomic shell chain.
        command = (
            "git restore --staged . && git add -- "
            + " ".join(shlex.quote(path) for path in paths)
            + " && git commit -q -F "
            + shlex.quote(str(self.message))
        )
        return self.run_command("sh", "-c", command, check=check)

    def change_ignore(self):
        with (self.repo / ".gitignore").open("a") as stream:
            stream.write("\nmanaged-hook-fixture-output/\n")

    def valid_commit(self):
        self.change_ignore()
        result = self.commit([".gitignore"], VALID_MESSAGE)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.run_command("git", "rev-parse", "HEAD").stdout.strip()

    def pre_push(self, transaction):
        return self.run_command(
            str(self.repo / ".githooks/pre-push"),
            "origin",
            str(self.repo / ".git/unused-destination"),
            input=transaction,
            check=False,
        )

    def transaction(self, head):
        return f"refs/heads/main {head} refs/heads/main {self.base}\n"

    def test_native_commit_accepts_studio_identity_and_preserves_codex_attribution(
        self,
    ):
        self.valid_commit()
        message = self.run_command("git", "log", "-1", "--format=%B").stdout
        self.assertIn(COAUTHOR, message)
        self.assertEqual(message.strip(), VALID_MESSAGE.strip())
        self.assertEqual(self.run_command("git", "diff", "--cached").stdout, "")

    def test_native_commit_rejects_non_studio_identity(self):
        self.run_command("git", "config", "user.email", "fixture@example.com")
        self.change_ignore()
        result = self.commit([".gitignore"], VALID_MESSAGE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside @hypertext.studio", result.stdout + result.stderr)
        self.assertEqual(
            self.run_command("git", "rev-parse", "HEAD").stdout.strip(), self.base
        )

    def test_native_commit_rejects_maintenance_without_informative_body(self):
        self.change_ignore()
        result = self.commit(
            [".gitignore"],
            "chore(dx): keep managed hooks portable\n\n" + COAUTHOR + "\n",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("chore needs a body", result.stdout + result.stderr)
        self.assertEqual(
            self.run_command("git", "rev-parse", "HEAD").stdout.strip(), self.base
        )

    def test_native_product_secret_guard_still_runs(self):
        with (self.repo / ".gitignore").open("a") as stream:
            stream.write("\n-----BEGIN " + "PRIVATE KEY-----\n")
        result = self.commit([".gitignore"], VALID_MESSAGE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SENSITIVE CONTENT", result.stdout + result.stderr)

    def test_push_transaction_reaches_shared_validator_and_product_checks(self):
        head = self.valid_commit()
        malformed = self.pre_push("malformed transaction\n")
        self.assertNotEqual(malformed.returncode, 0)
        self.assertIn("Malformed native pre-push", malformed.stdout + malformed.stderr)
        log = self.repo / ".git/gradle-invocations"
        self.assertFalse(log.exists(), "Product checks must follow shared validation")
        result = self.pre_push(self.transaction(head))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = log.read_text().splitlines()
        self.assertEqual(len(calls), 2, calls)
        self.assertTrue(
            all("-Djava.awt.headless=true|--no-daemon " in call for call in calls),
            calls,
        )
        self.assertIn(":app:android-main:assembleDebug", calls[0])
        self.assertIn("checkOsIntegrationScreenshotCoverage", calls[1])

    def test_product_push_failure_is_not_swallowed(self):
        head = self.valid_commit()
        (self.repo / ".git/fail-product-check").touch()
        result = self.pre_push(self.transaction(head))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "COMPILE: Android app failed to compile", result.stdout + result.stderr
        )
        self.assertIn("SCREENSHOTS:", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
