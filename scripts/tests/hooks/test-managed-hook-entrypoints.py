"""Exercise the tracked hook entrypoints in a fixture checkout, without an app build.

The entrypoints run LogDate's own checks in .hypertext/git-product and the Studio's
shared validator, `hypertext verify`, which comes from the Studio command on the
PATH rather than from this repository. A recording stand-in takes its place here,
so these tests cover what the repository owns: the order of the stages, that each
failure stops the hook, and that both push checks receive the same ref transaction.
The product stages and their scripts are the real ones. Product push checks run a
recording gradlew fixture.
"""

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
VALID_MESSAGE = (
    "chore(dx): keep managed hooks available in each checkout\n\n"
    "Use repository-owned checks so commits do not depend on a plugin cache.\n"
)
SHARED_VALIDATOR = """#!{python}
import json, os, sys
calls = os.path.join(os.environ["FIXTURE_STATE"], "hypertext-calls")
call = {{"arguments": sys.argv[1:]}}
if "--git-prepush" in sys.argv:
    call["input"] = sys.stdin.buffer.read().hex()
with open(calls, "a") as stream:
    stream.write(json.dumps(call) + "\\n")
failing = os.environ.get("FIXTURE_FAIL_SHARED_CHECK")
if failing and failing in sys.argv:
    print("shared validator rejected " + failing, file=sys.stderr)
    sys.exit(1)
"""


class ManagedHookEntrypointTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="managed-hook-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / "checkout"
        self.state = Path(self.temporary.name) / "state"
        self.repo.mkdir()
        self.state.mkdir()
        tools = Path(self.temporary.name) / "bin"
        tools.mkdir()
        validator = tools / "hypertext"
        validator.write_text(SHARED_VALIDATOR.format(python=sys.executable))
        validator.chmod(0o755)
        self.environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("GIT_", "HYPERTEXT_", "FIXTURE_"))
        }
        self.environment.update(
            {
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_TERMINAL_PROMPT": "0",
                "PYTHONDONTWRITEBYTECODE": "1",
                "FIXTURE_STATE": str(self.state),
                "PATH": os.pathsep.join(
                    [str(tools), str(Path(sys.executable).parent), os.environ["PATH"]]
                ),
            }
        )
        self.run_command("git", "init", "-q", "--initial-branch=main", ".")
        self.run_command("git", "config", "user.name", "Hook Fixture")
        self.run_command("git", "config", "user.email", "fixture@hypertext.studio")
        self.run_command("git", "config", "commit.gpgsign", "false")
        self.message = self.repo / ".git/fixture-message"
        tracked = subprocess.check_output(
            [
                "git",
                "-C",
                str(ROOT),
                "ls-files",
                "-z",
                "--",
                ".githooks",
                ".hypertext",
                "scripts/validation",
                "run",
                ".gitignore",
            ],
            text=True,
        ).split("\0")
        for relative in filter(None, tracked):
            destination = self.repo / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
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
        # The checkout is committed before the hooks are installed. Its first commit stages
        # directly: restore --staged . rejects a repository with no commits yet.
        self.message.write_text(VALID_MESSAGE)
        self.run_command("git", "add", "--", *paths)
        self.run_command("git", "commit", "-q", "-F", str(self.message))
        self.base = self.head()
        self.run_command("git", "update-ref", "refs/remotes/origin/main", self.base)
        self.run_command("git", "config", "core.hooksPath", ".githooks")

    def run_command(self, *arguments, check=True, input=None, failing=None):
        environment = dict(self.environment)
        if failing:
            environment["FIXTURE_FAIL_SHARED_CHECK"] = failing
        return subprocess.run(
            arguments,
            cwd=self.repo,
            env=environment,
            input=input,
            text=not isinstance(input, bytes),
            capture_output=True,
            check=check,
            timeout=60,
        )

    def commit(self, paths, message, failing=None):
        self.message.write_text(message)
        # Keep reset, explicit staging and commit in the same atomic shell chain.
        command = (
            "git restore --staged . && git add -- "
            + " ".join(shlex.quote(path) for path in paths)
            + " && git commit -q -F "
            + shlex.quote(str(self.message))
        )
        return self.run_command("sh", "-c", command, check=False, failing=failing)

    def head(self):
        return self.run_command("git", "rev-parse", "HEAD").stdout.strip()

    def shared_calls(self):
        calls = self.state / "hypertext-calls"
        if not calls.exists():
            return []
        return [json.loads(line) for line in calls.read_text().splitlines()]

    def shared_modes(self):
        return [
            argument
            for call in self.shared_calls()
            for argument in call["arguments"]
            if argument in ("--git-precommit", "--git-message", "--git-prepush")
        ]

    def gradle_calls(self):
        log = self.repo / ".git/gradle-invocations"
        return log.read_text().splitlines() if log.exists() else []

    def change_ignore(self, line="managed-hook-fixture-output/"):
        with (self.repo / ".gitignore").open("a") as stream:
            stream.write("\n" + line + "\n")

    def valid_commit(self):
        self.change_ignore()
        result = self.commit([".gitignore"], VALID_MESSAGE)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.head()

    def replace_product_stage(self, hook, body):
        stage = self.repo / ".hypertext/git-product" / hook
        stage.write_text("#!/bin/sh\n" + body + "\n")
        stage.chmod(0o755)

    def pre_push(self, transaction, failing=None):
        return self.run_command(
            str(self.repo / ".githooks/pre-push"),
            "origin",
            str(self.repo / ".git/unused-destination"),
            input=transaction,
            check=False,
            failing=failing,
        )

    def transaction(self, head):
        return f"refs/heads/main {head} refs/heads/main {self.base}\n"

    def test_commit_runs_product_checks_then_the_shared_validator(self):
        self.valid_commit()
        self.assertEqual(self.shared_modes(), ["--git-precommit", "--git-message"])
        for call in self.shared_calls():
            self.assertEqual(call["arguments"][:2], ["verify", "--repo"])
            self.assertEqual(Path(call["arguments"][2]).resolve(), self.repo.resolve())
        message = self.run_command("git", "log", "-1", "--format=%B").stdout
        self.assertEqual(message.strip(), VALID_MESSAGE.strip())
        self.assertEqual(self.run_command("git", "diff", "--cached").stdout, "")

    def test_shared_validator_rejecting_the_message_stops_the_commit(self):
        self.change_ignore()
        result = self.commit([".gitignore"], VALID_MESSAGE, failing="--git-message")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("shared validator rejected --git-message", result.stderr)
        self.assertEqual(self.head(), self.base)

    def test_shared_validator_rejecting_the_staged_files_stops_the_commit(self):
        self.change_ignore()
        result = self.commit([".gitignore"], VALID_MESSAGE, failing="--git-precommit")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("shared validator rejected --git-precommit", result.stderr)
        self.assertEqual(self.shared_modes(), ["--git-precommit"])
        self.assertEqual(self.head(), self.base)

    def test_product_secret_guard_runs_before_the_shared_validator(self):
        self.change_ignore("-----BEGIN " + "PRIVATE KEY-----")
        result = self.commit([".gitignore"], VALID_MESSAGE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SENSITIVE CONTENT", result.stdout + result.stderr)
        self.assertEqual(self.shared_modes(), [])
        self.assertEqual(self.head(), self.base)

    def test_failing_product_stage_keeps_its_exit_status(self):
        self.replace_product_stage(
            "pre-commit", "echo product-check-failed >&2; exit 23"
        )
        result = self.run_command(
            "sh", str(self.repo / ".githooks/pre-commit"), check=False
        )
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertIn("product-check-failed", result.stderr)
        self.assertEqual(self.shared_modes(), [])

    def test_push_runs_shared_validation_then_product_checks(self):
        head = self.valid_commit()
        transaction = self.transaction(head)
        result = self.pre_push(transaction)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        push = [c for c in self.shared_calls() if "--git-prepush" in c["arguments"]]
        self.assertEqual(len(push), 1)
        arguments = push[0]["arguments"]
        self.assertEqual(
            arguments[arguments.index("--git-remote") :],
            [
                "--git-remote",
                "origin",
                "--git-remote-url",
                str(self.repo / ".git/unused-destination"),
            ],
        )
        self.assertEqual(bytes.fromhex(push[0]["input"]), transaction.encode())
        calls = self.gradle_calls()
        self.assertEqual(len(calls), 2, calls)
        self.assertTrue(
            all("-Djava.awt.headless=true|--no-daemon " in call for call in calls),
            calls,
        )
        self.assertIn(":app:android-main:assembleDebug", calls[0])
        self.assertIn("checkOsIntegrationScreenshotCoverage", calls[1])

    def test_push_rejected_by_shared_validation_skips_product_checks(self):
        head = self.valid_commit()
        result = self.pre_push(self.transaction(head), failing="--git-prepush")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("shared validator rejected --git-prepush", result.stderr)
        self.assertEqual(self.gradle_calls(), [])

    def test_push_checks_receive_the_exact_native_transaction(self):
        self.replace_product_stage("pre-push", "cat > .git/product-refs")
        transaction = (
            b"(delete) "
            + b"0" * 40
            + b" refs/heads/obsolete-\xff "
            + b"1" * 40
            + b"\r\n"
        )
        result = self.pre_push(transaction)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.repo / ".git/product-refs").read_bytes(), transaction)
        push = [c for c in self.shared_calls() if "--git-prepush" in c["arguments"]]
        self.assertEqual(bytes.fromhex(push[0]["input"]), transaction)

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
