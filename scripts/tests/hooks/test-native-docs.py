import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


class SecretDocumentationTests(unittest.TestCase):
    def check_hook(self, path, content, tracked):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            common = root / "scripts/validation/hook-common.sh"
            common.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / "scripts/validation/hook-common.sh", common)
            shutil.copyfile(
                ROOT / "scripts/validation/sensitive-content.sh",
                common.with_name("sensitive-content.sh"),
            )
            # Exercise the complete tracked formatter path along with the native secret guards.
            shutil.copytree(
                ROOT / ".hypertext/formatting", root / ".hypertext/formatting"
            )
            shutil.copytree(
                ROOT / ".hypertext/formatter-tools",
                root / ".hypertext/formatter-tools",
                ignore=shutil.ignore_patterns("__pycache__", "bin"),
            )
            shutil.copyfile(
                ROOT / ".hypertext/formatters.json", root / ".hypertext/formatters.json"
            )

            def stage(file):
                name = str(file.relative_to(root))
                oid = (
                    subprocess.check_output(
                        ["git", "hash-object", "-w", name], cwd=root
                    )
                    .decode()
                    .strip()
                )
                subprocess.run(
                    [
                        "git",
                        "update-index",
                        "--add",
                        "--cacheinfo",
                        "100644",
                        oid,
                        name,
                    ],
                    cwd=root,
                    check=True,
                )

            for file in (root / ".hypertext").rglob("*"):
                if file.is_file():
                    stage(file)
            document = root / path
            document.parent.mkdir(parents=True, exist_ok=True)
            document.write_text("Company signing instructions.\n")
            if tracked:
                stage(document)
            message = root / ".git/fixture-message"
            message.write_text("chore: create fixture\n")
            subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.com",
                    "commit",
                    "-q",
                    "-F",
                    str(message),
                ],
                cwd=root,
                check=True,
            )
            document.write_text(content)
            stage(document)
            # Test the real repository-specific stage, not a workstation-bound
            # canonical dispatcher. Real repository commits still run both.
            return subprocess.run(
                ["bash", str(ROOT / ".hypertext/git-product/pre-commit")],
                cwd=root,
                capture_output=True,
                text=True,
            )

    def test_tracked_secret_runbook_accepts_company_team_correction(self):
        result = self.check_hook(
            "docs/runbook/release-secrets.md",
            "Company signing team T95VDD3A4W.\n",
            True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_new_secret_file_stays_blocked(self):
        result = self.check_hook("secret.json", '{"value": "unknown"}\n', False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SENSITIVE FILES", result.stdout + result.stderr)

    def test_private_key_in_tracked_document_stays_blocked(self):
        result = self.check_hook(
            "docs/runbook/release-secrets.md",
            "-----BEGIN " + "PRIVATE KEY-----\n",
            True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SENSITIVE CONTENT", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
