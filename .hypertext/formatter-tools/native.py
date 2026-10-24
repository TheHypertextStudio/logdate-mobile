#!/usr/bin/env python3
"""Format Kotlin staged bytes with the exact engine used by native Gradle checks."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
VERSION = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"][
    "ktlintEngine"
]
# A catalog upgrade requires deliberate review of the new official artifact.
ARTIFACTS = {
    "1.8.0": "369ad2b789f95a011f807e1fcb690ccef80bd7cd014fd139e73ae82dcc0baeab"
}
if VERSION not in ARTIFACTS:
    raise SystemExit(
        "Native formatter blocked: catalog engine needs a reviewed artifact checksum"
    )
SHA256 = ARTIFACTS[VERSION]
URL = f"https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/{VERSION}/ktlint-cli-{VERSION}-all.jar"
CACHE = (
    Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache")))
    / "hypertext-studio/formatters"
)
JAR = CACHE / ("ktlint-" + VERSION + "-all.jar")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def verified():
    return JAR.is_file() and digest(JAR) == SHA256


def setup():
    if not verified():
        CACHE.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix=".ktlint-download-", dir=CACHE)
        temporary = Path(name)
        try:
            with (
                os.fdopen(descriptor, "wb") as output,
                urllib.request.urlopen(URL, timeout=60) as response,
            ):
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 128 * 1024 * 1024:
                        raise ValueError("KtLint download exceeded 128 MiB")
                    output.write(chunk)
            if digest(temporary) != SHA256:
                raise ValueError("KtLint download does not match its pinned SHA256")
            os.replace(temporary, JAR)
        finally:
            temporary.unlink(missing_ok=True)
    result = subprocess.run(
        java_command() + ["--version"], capture_output=True, timeout=30
    )
    if (
        result.returncode
        or result.stdout.strip() != f"ktlint version {VERSION}".encode()
    ):
        raise ValueError(
            f"Pinned KtLint {VERSION} could not execute; use the repository JDK"
        )
    print(f"Pinned KtLint {VERSION} ready in the shared native cache.")


def java_command():
    java = shutil.which("java")
    if not java:
        raise ValueError(
            "Java is unavailable; prepare the repository JDK before formatter setup"
        )
    return [java, "-Djava.awt.headless=true", "-jar", str(JAR)]


KTLINT_STDIN = r"""
import com.pinterest.ktlint.rule.engine.api.Code;
import com.pinterest.ktlint.rule.engine.api.EditorConfigDefaults;
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride;
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine;
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import kotlin.Unit;

/** Preserve staged stdin bytes and the original config path without CLI printf expansion. */
public final class KtlintStdin {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ROOT"))
            .setLevel(ch.qos.logback.classic.Level.OFF);
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        var engine = new KtLintRuleEngine(
            new StandardRuleSetProvider().getRuleProviders(),
            EditorConfigDefaults.Companion.getEMPTY_EDITOR_CONFIG_DEFAULTS(),
            EditorConfigOverride.Companion.getEMPTY_EDITOR_CONFIG_OVERRIDE(),
            true,
            FileSystems.getDefault());
        var code = Code.Companion.fromSnippetWithPath(input, Path.of(args[0]));
        boolean[] failed = {false};
        String result = engine.format(code, (error, corrected) -> {
            if (!corrected) {
                failed[0] = true;
                System.err.println(error);
            }
            return Unit.INSTANCE;
        });
        // Check the final result as well: a non-correctable rule must block the hook.
        engine.lint(Code.Companion.fromSnippetWithPath(result, Path.of(args[0])), error -> {
            failed[0] = true;
            System.err.println(error);
            return Unit.INSTANCE;
        });
        if (failed[0]) System.exit(1);
        System.out.write(result.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
"""


def main():
    if sys.argv[1:] == ["--setup"]:
        setup()
        return 0
    if len(sys.argv) != 3 or sys.argv[1] != "ktlint":
        raise ValueError("Unknown native formatter; expected ktlint PATH or --setup")
    if not verified():
        raise ValueError(
            "Pinned KtLint is missing or changed. Run python3 .hypertext/formatter-tools/native.py --setup before committing"
        )
    # ktlint 1.8 CLI passes formatted stdin to printf, corrupting literal %
    # sequences. Use the same verified engine API and original virtual path.
    with tempfile.TemporaryDirectory(prefix="hypertext-ktlint-") as directory:
        bridge = Path(directory) / "KtlintStdin.java"
        bridge.write_text(KTLINT_STDIN, encoding="utf-8")
        command = java_command()[:2] + [
            "--class-path",
            str(JAR),
            str(bridge),
            str((Path(__file__).resolve().parents[2] / sys.argv[2]).resolve()),
        ]
        source = sys.stdin.buffer.read()
        result = subprocess.run(command, input=source, capture_output=True)
    # KtLint can report a parse error on stderr and exit zero with
    # no stdout. Never expose that as a successful formatting operation.
    if (
        result.returncode
        or result.stderr
        or (source.strip() and not result.stdout.strip())
    ):
        if result.stderr:
            sys.stderr.buffer.write(result.stderr)
        raise ValueError(
            "KtLint did not produce clean, nonempty formatted source; staged bytes were not changed"
        )
    sys.stdout.buffer.write(result.stdout)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("Native formatter blocked: " + str(error), file=sys.stderr)
        raise SystemExit(1)
