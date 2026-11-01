"""Pinned, fail-closed TLA+/TLC formatting; staged bytes arrive on stdin."""

from dataclasses import dataclass
from formal_binding import FormalBinding
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import threading


ROOT = Path(__file__).resolve().parent
# Actual paths and launcher hashes are bound by explicit offline preparation.
JAR = None
JAVA = None
JAR_SHA = "d15655eeaff4b3f7b3d65c46b838bc0f35d131b2ef4b1e473689b62c98d4726f"
MAX_BYTES = 1024 * 1024
VERIFIER = ROOT / "build/tlc-verifier"
SCALARS = {
    "INIT",
    "NEXT",
    "SPECIFICATION",
    "VIEW",
    "SYMMETRY",
    "ALIAS",
    "_PERIODIC",
    "_RL_REWARD",
}
LISTS = {
    "INVARIANT",
    "INVARIANTS",
    "PROPERTY",
    "PROPERTIES",
    "CONSTRAINT",
    "CONSTRAINTS",
    "ACTION_CONSTRAINT",
    "ACTION_CONSTRAINTS",
    "POSTCONDITION",
    "POSTCONDITIONS",
    "_POSSIBLE",
}
KEYWORDS = SCALARS | LISTS | {"CONSTANT", "CONSTANTS", "CHECK_DEADLOCK"}


@dataclass(frozen=True)
class Token:
    kind: str
    image: str


def decode(data):
    if len(data) > MAX_BYTES:
        raise ValueError("Formal input exceeds 1 MiB")
    return data.decode("utf-8", errors="strict")


def lex(text):
    """Consume every character; comments and quoted lexemes remain byte-exact."""
    result = []
    index = 0
    while index < len(text):
        start = index
        char = text[index]
        if char in " \t\r\n\f":
            index += 1
            continue
        if text.startswith("\\*", index):
            end = text.find("\n", index)
            index = len(text) if end < 0 else end + 1
            result.append(Token("comment", text[start:index]))
            continue
        if text.startswith("(*", index):
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith("(*", index):
                    depth += 1
                    index += 2
                elif text.startswith("*)", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if depth:
                raise ValueError("Unfinished nested comment")
            result.append(Token("comment", text[start:index]))
            continue
        if char == '"':
            index += 1
            while index < len(text) and text[index] != '"':
                if text[index] in "\r\n":
                    raise ValueError("Newline in quoted string")
                if text[index] == "\\":
                    index += 1
                    if index >= len(text) or text[index] not in '\\"tnfr':
                        raise ValueError("Unsupported string escape")
                index += 1
            if index >= len(text):
                raise ValueError("Unfinished string")
            index += 1
            result.append(Token("string", text[start:index]))
            continue
        match = re.match(
            r"[A-Za-z_][A-Za-z_0-9]*|[0-9]+|<-|[=(){}\[\],!]", text[index:]
        )
        if not match:
            raise ValueError("Unsupported TLC token at character " + str(index))
        image = match.group()
        index += len(image)
        kind = (
            "identifier"
            if image[0].isalpha() or image[0] == "_"
            else "number"
            if image.isdigit()
            else "symbol"
        )
        result.append(Token(kind, image))
    return result


class Config:
    """Recognized TLC grammar; retain declaration order, duplicates and definedness."""

    def __init__(self, text):
        self.tokens = lex(text)
        self.significant = [t for t in self.tokens if t.kind != "comment"]
        self.index = 0
        self.breaks = {}
        self.after_heading = set()
        self.ast = []
        singletons = set()
        while self.index < len(self.significant):
            self.breaks[self.index] = 0
            keyword = self.take().image
            if keyword not in KEYWORDS:
                raise ValueError("Expected a supported TLC section")
            if keyword in SCALARS | {"CHECK_DEADLOCK"}:
                if keyword in singletons:
                    raise ValueError("Repeated singleton section")
                singletons.add(keyword)
                value = self.identifier()
                if keyword == "CHECK_DEADLOCK" and value not in ("TRUE", "FALSE"):
                    raise ValueError("CHECK_DEADLOCK requires TRUE or FALSE")
                self.ast.append((keyword, value))
            elif keyword in LISTS:
                values = []
                while self.peek() and self.peek().image not in KEYWORDS:
                    values.append(self.identifier())
                self.ast.append((keyword, tuple(values)))
            else:
                self.after_heading.add(self.index - 1)
                rows = []
                while self.peek() and self.peek().image not in KEYWORDS:
                    self.breaks[self.index] = 4
                    lhs = [self.identifier()]
                    while self.accept("!"):
                        lhs.append(self.identifier())
                    arguments = None
                    if self.accept("("):
                        arguments = [self.value()]
                        while self.accept(","):
                            arguments.append(self.value())
                        self.expect(")")
                    operator = self.take().image
                    if operator not in ("=", "<-"):
                        raise ValueError("Constant requires = or <-")
                    if arguments is not None and operator != "=":
                        raise ValueError("Unsupported argument substitution")
                    module = None
                    if self.accept("["):
                        module = self.identifier()
                        self.expect("]")
                    value = (
                        ("operator", self.identifier())
                        if operator == "<-"
                        else self.value()
                    )
                    rows.append(
                        (
                            tuple(lhs),
                            None if arguments is None else tuple(arguments),
                            operator,
                            module,
                            value,
                        )
                    )
                self.ast.append((keyword, tuple(rows)))

    def peek(self):
        return (
            self.significant[self.index] if self.index < len(self.significant) else None
        )

    def take(self):
        token = self.peek()
        if token is None:
            raise ValueError("Unexpected end of configuration")
        self.index += 1
        return token

    def accept(self, image):
        if self.peek() and self.peek().image == image:
            self.index += 1
            return True
        return False

    def expect(self, image):
        if not self.accept(image):
            raise ValueError("Expected " + image)

    def identifier(self):
        token = self.take()
        if token.kind != "identifier" or token.image in KEYWORDS:
            raise ValueError("Expected supported TLC identifier")
        return token.image

    def value(self):
        if self.accept("{"):
            values = []
            if not self.accept("}"):
                values.append(self.value())
                while self.accept(","):
                    values.append(self.value())
                self.expect("}")
            return ("set", tuple(values))
        token = self.take()
        if (
            token.kind not in ("identifier", "number", "string")
            or token.image in KEYWORDS
        ):
            raise ValueError("Unsupported TLC value")
        return (token.kind, token.image)

    def formatted(self):
        parts = []
        start = True
        previous = None
        indent = 0
        significant = 0

        def newline():
            nonlocal start, previous
            if parts and not parts[-1].endswith("\n"):
                parts.append("\n")
            start = True
            previous = None

        for token in self.tokens:
            if token.kind == "comment":
                newline()
                parts.append(" " * indent + token.image)
                newline()
                continue
            if significant in self.breaks:
                newline()
                indent = self.breaks[significant]
            if start:
                parts.append(" " * indent)
                start = False
            elif token.image not in (")", "]", "}", ",", "!") and previous not in (
                "(",
                "[",
                "{",
                "!",
            ):
                parts.append(" ")
            parts.append(token.image)
            previous = token.image
            if significant in self.after_heading:
                newline()
            significant += 1
        newline()
        return "".join(parts)


def checked_tools():
    global JAR, JAVA
    binding = FormalBinding(ROOT, VERIFIER)
    tools = binding.tools()
    JAR, JAVA = Path(tools["jar"]), Path(tools["java"])
    return binding


def java(arguments, folder, classpath=None, compiler=False):
    binding = checked_tools()
    if not compiler:
        binding.receipt(require_native=True)
    environment = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
        environment.pop(name, None)
    command = [
        str(JAVA),
        "-Xmx768m" if compiler else "-Xmx512m",
        "-XX:MaxMetaspaceSize=384m" if compiler else "-XX:MaxMetaspaceSize=256m",
        "-Dfile.encoding=UTF-8",
        "-Djava.io.tmpdir=" + str(folder),
        "-cp",
        str(JAR) if classpath is None else classpath,
        *arguments,
    ]
    sys.stderr.write("FORMAL-JAVA " + json.dumps(command) + "\n")
    child = subprocess.Popen(
        command,
        cwd=folder,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    buffers = [bytearray(), bytearray()]
    exceeded = threading.Event()

    def collect(stream, buffer):
        try:
            while chunk := stream.read(65536):
                if len(buffer) + len(chunk) > 2 * MAX_BYTES:
                    exceeded.set()
                    child.kill()
                    break
                buffer.extend(chunk)
        finally:
            stream.close()

    readers = [
        threading.Thread(target=collect, args=(stream, buffer))
        for stream, buffer in zip((child.stdout, child.stderr), buffers)
    ]
    for reader in readers:
        reader.start()

    def stop():
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)

    def terminate(signum, frame):
        stop()
        raise SystemExit(128 + signum)

    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        try:
            child.wait(timeout=60 if compiler else 30)
        except subprocess.TimeoutExpired:
            stop()
            raise ValueError("Formal tool deadline exceeded") from None
        for reader in readers:
            reader.join(timeout=3)
            if reader.is_alive():
                raise ValueError("Formal output reader did not retire")
        if exceeded.is_set():
            raise ValueError("Formal tool exceeded bounded output capture")
        stdout, stderr = (bytes(buffer) for buffer in buffers)
        if child.returncode:
            raise ValueError(
                "Formal tool refused input: "
                + stderr.decode("utf-8", errors="replace")[-4000:]
            )
        return stdout, stderr
    finally:
        signal.signal(signal.SIGTERM, previous)
        stop()
        for reader in readers:
            reader.join(timeout=3)


def prepare_verifier():
    # Bootstrap precedes native setup.py publishing its new runtime.spec.
    checked_tools().prepare(java)


def verifier_classpath():
    return checked_tools().classpath(require_native=True)


def verify_cfg(before, after):
    left = Config(decode(before))
    right = Config(decode(after))
    if left.ast != right.ast:
        raise ValueError("Configuration semantic AST changed")
    with tempfile.TemporaryDirectory(prefix="tlc-roundtrip-") as directory:
        folder = Path(directory)
        a, b = folder / "original.cfg", folder / "formatted.cfg"
        a.write_bytes(before)
        b.write_bytes(after)
        stdout, _ = java(
            ["TlcConfigVerifier", str(a), str(b)],
            folder,
            classpath=verifier_classpath(),
        )
        if not stdout.startswith(b"TLC-PARSER-ROUNDTRIP "):
            raise ValueError("Missing actual TLC parser/token verification")
        if (
            "java=" + str(JAVA.parent.parent)
        ).encode() not in stdout or b"maxHeap=536870912" not in stdout:
            raise ValueError("Actual verifier JVM does not match bounded JBR runtime")
        sys.stderr.buffer.write(stdout)


def format_cfg(source):
    config = Config(decode(source))
    candidate = config.formatted().encode("utf-8")
    if Config(decode(candidate)).formatted().encode("utf-8") != candidate:
        raise ValueError("CFG formatter is not idempotent")
    verify_cfg(source, candidate)
    return candidate


def format_tla(source, name):
    decode(source)
    if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*\.tla", name):
        raise ValueError("Invalid TLA module filename")
    with tempfile.TemporaryDirectory(prefix="tla-format-") as directory:
        folder = Path(directory)
        path = folder / name
        path.write_bytes(source)
        output, _ = java(["formatter.Main", str(path)], folder)
        decode(output)
        path.write_bytes(output)
        again, _ = java(["formatter.Main", str(path)], folder)
        if again != output:
            raise ValueError("TLA formatter is not idempotent")
        sys.stderr.write("TLA-AST-VERIFIED default=true jar=" + JAR_SHA + "\n")
        return output


def main():
    if sys.argv[1:] == ["prepare"]:
        prepare_verifier()
        return
    if len(sys.argv) not in (3, 4):
        raise ValueError(
            "Expected cfg/tla context-path, or verify-cfg original candidate"
        )
    mode, path = sys.argv[1], Path(sys.argv[2])
    if mode == "verify-cfg" and len(sys.argv) == 4:
        with path.open("rb") as left, Path(sys.argv[3]).open("rb") as right:
            verify_cfg(left.read(MAX_BYTES + 1), right.read(MAX_BYTES + 1))
        return
    if len(sys.argv) != 3 or mode not in ("cfg", "tla"):
        raise ValueError("Unsupported formal mode")
    source = sys.stdin.buffer.read(MAX_BYTES + 1)
    result = format_cfg(source) if mode == "cfg" else format_tla(source, path.name)
    sys.stdout.buffer.write(result)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, UnicodeError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
