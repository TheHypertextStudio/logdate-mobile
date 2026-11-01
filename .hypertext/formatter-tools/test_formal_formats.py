"""Contract-first tests for a real TLC configuration/module formatter adapter."""

import importlib.util
import json
import hashlib
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent
FIXTURES = Path(
    os.environ.get(
        "FORMAL_TEST_FIXTURES", str(ROOT.parents[1] / "docs/verification/backup-sync")
    )
)
NATIVE_TOOLS = Path(
    os.environ.get("FORMAL_TEST_NATIVE_TOOLS", str(ROOT / "tools.json"))
)
ADAPTER = ROOT / "formal_formats.py"


def cancelled(*args):
    # unittest propagates KeyboardInterrupt instead of swallowing it as a case
    # error and starting another adapter after the owner's cancellation.
    raise KeyboardInterrupt("Owned formal test driver cancelled")


signal.signal(signal.SIGTERM, cancelled)


class FormalFormatTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # The owner prepares ROOT once before this suite. Use real pinned compiled
        # artifacts, and isolated native setup inputs, without touching a live repo.
        cls.temporary = tempfile.TemporaryDirectory(prefix="formal-prepared-fixture-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.prepared = Path(cls.temporary.name)
        for name in (
            "formal_formats.py",
            "formal_binding.py",
            "formal-tools.json",
            "TlcConfigVerifier.kt",
        ):
            shutil.copy2(ROOT / name, cls.prepared / name)
        recipe = json.loads((ROOT / "formal-tools.json").read_text())
        for name, expected in recipe["verifier"]["artifacts"].items():
            path = ROOT / "build/tlc-verifier" / name
            if (
                path.is_symlink()
                or hashlib.sha256(path.read_bytes()).hexdigest() != expected
            ):
                raise ValueError(
                    "Test baseline requires actual pinned compiled verifier bytes"
                )
        shutil.copytree(
            ROOT / "build/tlc-verifier", cls.prepared / "build/tlc-verifier"
        )
        shutil.copy2(
            ROOT / "build/formal-runtime.json",
            cls.prepared / "build/formal-runtime.json",
        )
        native_bytes = NATIVE_TOOLS.read_bytes()
        cls.native_fixture = json.loads(native_bytes)
        (cls.prepared / "tools.json").write_bytes(native_bytes)
        (cls.prepared / "runtime.json").write_text(
            json.dumps({"spec": cls.native_fixture})
        )
        sys.path.insert(0, str(cls.prepared))
        cls.addClassCleanup(sys.path.remove, str(cls.prepared))

    def adapter(self):
        self.assertTrue(
            (self.prepared / "formal_formats.py").is_file(),
            "Formal-language formatter capability is missing",
        )
        spec = importlib.util.spec_from_file_location(
            "formal_formats", self.prepared / "formal_formats.py"
        )
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        return module

    def run_adapter(self, mode, source, other=None, suffix=".cfg"):
        self.adapter()
        with tempfile.TemporaryDirectory(prefix="formal-contract-") as folder:
            folder = Path(folder)
            path = folder / ("BackupSync" + suffix)
            path.write_bytes(source)
            argv = [
                sys.executable,
                str(self.prepared / "formal_formats.py"),
                mode,
                str(path),
            ]
            if other is not None:
                candidate = folder / ("candidate" + suffix)
                candidate.write_bytes(other)
                argv.append(str(candidate))
            env = os.environ.copy()
            for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                env.pop(name, None)
            child = subprocess.Popen(
                argv,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
                start_new_session=True,
            )
            try:
                stdout, stderr = child.communicate(input=source, timeout=45)
            finally:
                deadline = time.monotonic() + 10
                previous = signal.signal(signal.SIGTERM, signal.SIG_IGN)
                previous_int = signal.signal(signal.SIGINT, signal.SIG_IGN)
                try:
                    try:
                        os.killpg(child.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    while time.monotonic() < deadline - 3:
                        child.poll()
                        try:
                            os.killpg(child.pid, 0)
                        except ProcessLookupError:
                            break
                        time.sleep(0.05)
                    else:
                        os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=max(0.01, deadline - time.monotonic()))
                    while time.monotonic() < deadline:
                        try:
                            os.killpg(child.pid, 0)
                        except ProcessLookupError:
                            break
                        time.sleep(0.05)
                    else:
                        raise RuntimeError("Owned adapter/JVM group remains")
                finally:
                    signal.signal(signal.SIGTERM, previous)
                    signal.signal(signal.SIGINT, previous_int)
            result = subprocess.CompletedProcess(argv, child.returncode, stdout, stderr)
            proof = os.environ.get("FORMAL_TEST_PROOF")
            if proof:
                record = {
                    "case": self.id(),
                    "command": argv,
                    "returncode": result.returncode,
                    "stdout_sha256": hashlib.sha256(stdout).hexdigest(),
                    "stdout_bytes": len(stdout),
                    "stderr": stderr.decode(errors="replace"),
                }
                with Path(proof).open("a") as log:
                    log.write(json.dumps(record) + "\n")
            self.assertEqual(path.read_bytes(), source, "Adapter wrote the input")
            return result

    def assert_formatted(self, source, expected=None):
        result = self.run_adapter("cfg", source)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        self.assertIn(b"TLC-PARSER-ROUNDTRIP", result.stderr)
        if expected is not None:
            self.assertEqual(result.stdout, expected)
        again = self.run_adapter("cfg", result.stdout)
        self.assertEqual(again.returncode, 0, again.stderr.decode(errors="replace"))
        self.assertEqual(again.stdout, result.stdout)
        return result.stdout

    def test_all_five_actual_configs_are_formatted_and_idempotent(self):
        self.adapter()
        for path in sorted(FIXTURES.glob("*.cfg")):
            with self.subTest(path=path.name):
                self.assert_formatted(path.read_bytes())
        self.assertEqual(len(list(FIXTURES.glob("*.cfg"))), 5)

    def test_canonical_sections_and_constants_change_layout(self):
        self.assert_formatted(
            b"CONSTANTS N=2 Enabled=FALSE SPECIFICATION Spec INVARIANTS Safe TypeOK PROPERTIES Live\n",
            b"CONSTANTS\n    N = 2\n    Enabled = FALSE\nSPECIFICATION Spec\nINVARIANTS Safe TypeOK\nPROPERTIES Live\n",
        )

    def test_nested_sets_model_values_strings_and_argument_constants(self):
        self.assert_formatted(
            b'CONSTANTS S={a,b,{c}} Typed=t_One Text="a \\"quoted\\" token" K(a,2)=FALSE\nSPECIFICATION Spec\n'
        )

    def test_module_assignments_substitutions_and_qualified_lhs(self):
        self.assert_formatted(
            b"CONSTANTS A!B= [M] {v1,v2} Op <- Replacement Other <- [M] Definition\nSPECIFICATION Spec\n"
        )

    def test_control_clauses_and_defined_deadlock_false_are_preserved(self):
        self.assert_formatted(
            b"INIT Init NEXT Next VIEW View SYMMETRY Sym CHECK_DEADLOCK FALSE\n"
            b"CONSTRAINTS Bound Other ACTION_CONSTRAINT Step INVARIANT Safe PROPERTY Live\n"
        )

    def test_repeated_lists_and_overrides_are_not_sorted_or_deduplicated(self):
        output = self.assert_formatted(
            b"CONSTANTS Op <- First Op <- Second INVARIANTS Z A INVARIANT Z PROPERTY Live\n"
        )
        self.assertLess(output.index(b"First"), output.index(b"Second"))
        self.assertIn(b"INVARIANTS Z A\nINVARIANT Z\n", output)

    def test_line_and_nested_block_comments_keep_exact_contents_and_order(self):
        source = (
            b"\\* leading  comment\nCONSTANTS N=2 (* outer (* inner *) end *) Flag=TRUE\n"
            b'S="(* string is not comment *)"\nSPECIFICATION Spec \\* EOF comment'
        )
        output = self.assert_formatted(source)
        for comment in (
            b"\\* leading  comment",
            b"(* outer (* inner *) end *)",
            b"\\* EOF comment",
        ):
            self.assertIn(comment, output)

    def test_comment_only_input_is_preserved(self):
        self.assert_formatted(
            b"\\* no directives\n(* nested (* comment *) remains *)\n"
        )

    def test_unicode_comment_and_crlf_input_are_preserved(self):
        self.assert_formatted(
            "\\* café\r\nCONSTANTS N=2\r\nSPECIFICATION Spec\r\n".encode()
        )

    def test_malformed_inputs_refuse_without_partial_output(self):
        for source in (
            b"CONSTANTS N = {a,b\n",
            b'CONSTANTS S = "unfinished\n',
            b"SPECIFICATION Spec (* unfinished\n",
            b"SPECIFICATION Spec (* nested (* still open *)\n",
            b"CHECK_DEADLOCK MAYBE\n",
            b"SPECIFICATION Spec SPECIFICATION Again\n",
            b"CONSTANTS N = 2 UNKNOWN clause\n",
            b"CONSTANTS N = 2 @ bad\nSPECIFICATION Spec\n",
            b"\xff invalid encoding\n",
            b'CONSTANTS S = "bad\\qescape"\n',
            b" " * (1024 * 1024 + 1),
        ):
            with self.subTest(source=source):
                result = self.run_adapter("cfg", source)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, b"")

    def test_unsupported_expression_refuses_instead_of_trimming(self):
        result = self.run_adapter(
            "cfg", b"CONSTANTS F = [x \\in S |-> x]\nSPECIFICATION Spec\n"
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")

    def test_semantic_deletion_reordering_and_comment_changes_are_rejected(self):
        before = b"CONSTANTS Op <- First Op <- Second\nINVARIANTS Z A\nCHECK_DEADLOCK FALSE\n\\* retained\n"
        candidates = (
            before.replace(b"CHECK_DEADLOCK FALSE\n", b""),
            before.replace(b"Op <- First Op <- Second", b"Op <- Second Op <- First"),
            before.replace(b"INVARIANTS Z A", b"INVARIANTS A Z"),
            before.replace(b"\\* retained", b"\\* changed"),
            before.replace(b"First", b"Different"),
        )
        for after in candidates:
            with self.subTest(after=after):
                result = self.run_adapter("verify-cfg", before, other=after)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, b"")

    def test_verifier_accepts_whitespace_only_layout_change(self):
        result = self.run_adapter(
            "verify-cfg",
            b"CONSTANTS N=2\nSPECIFICATION Spec\n",
            other=b"CONSTANTS\n    N = 2\nSPECIFICATION Spec\n",
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        self.assertIn(b"TLC-PARSER-ROUNDTRIP", result.stderr)

    def test_actual_module_uses_ast_checked_formatter_and_is_idempotent(self):
        noncanonical = (
            b"---- MODULE BackupSync ----\nEXTENDS Naturals\n  Inc(x)==x+1\n====\n"
        )
        proof = self.run_adapter("tla", noncanonical, suffix=".tla")
        self.assertEqual(proof.returncode, 0, proof.stderr.decode(errors="replace"))
        self.assertNotEqual(
            proof.stdout, noncanonical, "An unchanged-byte echo is not a formatter"
        )
        source = (FIXTURES / "BackupSync.tla").read_bytes()
        first = self.run_adapter("tla", source, suffix=".tla")
        self.assertEqual(first.returncode, 0, first.stderr.decode(errors="replace"))
        self.assertIn(b"TLA-AST-VERIFIED", first.stderr)
        second = self.run_adapter("tla", first.stdout, suffix=".tla")
        self.assertEqual(second.returncode, 0, second.stderr.decode(errors="replace"))
        self.assertEqual(second.stdout, first.stdout)

    def test_malformed_module_does_not_emit_original_as_success(self):
        result = self.run_adapter(
            "tla", b"---- MODULE BackupSync ----\nBroken == /\\\n====\n", suffix=".tla"
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")

    def test_prepared_cache_rejects_receipt_and_inventory_tampering(self):
        module = self.adapter()
        with tempfile.TemporaryDirectory(prefix="cache-contract-") as directory:
            root = Path(directory)
            cache = root / "build/tlc-verifier"
            for name in (
                "TlcConfigVerifier.kt",
                "formal-tools.json",
                "tools.json",
                "runtime.json",
            ):
                shutil.copy2(self.prepared / name, root / name)
            shutil.copytree(self.prepared / "build/tlc-verifier", cache)
            shutil.copy2(
                self.prepared / "build/formal-runtime.json",
                root / "build/formal-runtime.json",
            )
            main = cache / "TlcConfigVerifier.class"
            original_bytes = main.read_bytes()
            original = json.loads((cache / "receipt.json").read_text())
            binding_original = json.loads(
                (root / "build/formal-runtime.json").read_text()
            )
            module.ROOT, module.VERIFIER = root, cache

            def bind(receipt):
                raw = json.dumps(receipt).encode()
                (cache / "receipt.json").write_bytes(raw)
                binding = {
                    **binding_original,
                    "receipt_sha256": hashlib.sha256(raw).hexdigest(),
                }
                (root / "build/formal-runtime.json").write_text(json.dumps(binding))

            bind(original)
            self.assertIn(str(cache), module.verifier_classpath())
            for classes in ({}, {"../outside.class": "0" * 64}, {str(main): "0" * 64}):
                altered = {**original, "classes": classes}
                with self.subTest(classes=classes):
                    bind(altered)
                    with self.assertRaises(ValueError):
                        module.verifier_classpath()
            bind(original)
            (cache / "unexpected.class").write_bytes(b"unreviewed")
            with self.assertRaises(ValueError):
                module.verifier_classpath()
            (cache / "unexpected.class").unlink()
            (root / "build/formal-runtime.json").write_text(
                json.dumps({**binding_original, "receipt_sha256": "0" * 64})
            )
            with self.assertRaises(ValueError):
                module.verifier_classpath()
            bind(original)
            main.unlink()
            main.symlink_to(root / "TlcConfigVerifier.kt")
            with self.assertRaises(ValueError):
                module.verifier_classpath()
            main.unlink()
            main.write_bytes(original_bytes)
            # Even two consistently forged ignored receipts cannot authorize new
            # class bytes: their inventory must equal the tracked recipe's pins.
            main.write_bytes(original_bytes + b"tamper")
            forged = {
                **original,
                "classes": {
                    **original["classes"],
                    main.name: hashlib.sha256(main.read_bytes()).hexdigest(),
                },
            }
            bind(forged)
            with self.assertRaises(ValueError):
                module.verifier_classpath()
            main.write_bytes(original_bytes)
            bind(original)
            with patch.dict(os.environ, {"JAVA_HOME": ""}):
                with self.assertRaises(ValueError):
                    module.verifier_classpath()
            (root / "runtime.json").write_text(json.dumps({"spec": {"stale": True}}))
            with self.assertRaises(ValueError):
                module.verifier_classpath()

            # Bootstrap reuse must not demand the native spec that setup.py only
            # writes AFTER all native_setup commands have succeeded.
            def must_not_compile(*args, **kwargs):
                raise AssertionError("Prepared verifier was compiled twice")

            module.checked_tools().prepare(must_not_compile)


if __name__ == "__main__":
    unittest.main(verbosity=2)
