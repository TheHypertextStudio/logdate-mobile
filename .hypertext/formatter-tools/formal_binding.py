"""Portable, offline-only preparation and receipt checks for formal formatters."""

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

MAX_BYTES = 1024 * 1024


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            value.update(chunk)
    return value.hexdigest()


def regular(path, limit=MAX_BYTES):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
        raise ValueError("Expected bounded regular unshared file: " + str(path))
    return path


def read_json(path):
    return json.loads(regular(path).read_text(encoding="utf-8"))


def relative(name):
    if not isinstance(name, str) or not name:
        raise ValueError("Missing cache coordinate")
    path = Path(name)
    if (
        path.is_absolute()
        or ".." in path.parts
        or "\\" in name
        or path.as_posix() != name
    ):
        raise ValueError("Unsafe cache coordinate")
    return path


def confined(root, name):
    path = root
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Expected regular preparation directory")
    for part in relative(name).parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("Symlink in formal preparation path")
    return path


def atomic_json(path, value):
    if path.is_symlink():
        raise ValueError("Symlinked formal runtime destination")
    descriptor, temporary = tempfile.mkstemp(prefix=".formal-", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


class FormalBinding:
    def __init__(self, root, verifier):
        self.root = Path(root)
        self.verifier = Path(verifier)
        if self.verifier != self.root / "build/tlc-verifier":
            raise ValueError("Unexpected verifier cache location")
        self.recipe_path = self.root / "formal-tools.json"
        self.recipe = read_json(self.recipe_path)
        if (
            not isinstance(self.recipe, dict)
            or set(self.recipe)
            != {"schema_version", "tlc", "jdk", "kotlin_modules", "verifier"}
            or self.recipe["schema_version"] != 1
        ):
            raise ValueError("Invalid formal recipe schema")
        if self.recipe["tlc"] != {
            "cache_path": "tlaplus/1.8.0/tla2tools.jar",
            "bytes": 4502259,
            "sha256": "d15655eeaff4b3f7b3d65c46b838bc0f35d131b2ef4b1e473689b62c98d4726f",
        }:
            raise ValueError("Unexpected TLC recipe")
        if self.recipe["jdk"] != {
            "version": "21.0.10",
            "implementor": "JetBrains s.r.o.",
            "implementor_version": "JBRSDK-21.0.10+7-1163.108-nomod",
        }:
            raise ValueError("Unexpected JDK recipe")
        verifier_recipe = self.recipe["verifier"]
        if (
            not isinstance(verifier_recipe, dict)
            or set(verifier_recipe) != {"source", "source_sha256", "artifacts"}
            or verifier_recipe["source"] != "TlcConfigVerifier.kt"
        ):
            raise ValueError("Invalid verifier recipe")
        if not isinstance(verifier_recipe["artifacts"], dict) or set(
            verifier_recipe["artifacts"]
        ) != {
            "TlcConfigVerifier.class",
            "TlcConfigVerifier$Entry.class",
            "TlcConfigVerifier$Lexeme.class",
            "META-INF/main.kotlin_module",
        }:
            raise ValueError("Unexpected compiled artifact inventory")
        for name, expected in verifier_recipe["artifacts"].items():
            relative(name)
            if not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise ValueError("Invalid tracked artifact pin")
        source = confined(self.root, verifier_recipe["source"])
        if digest(regular(source)) != verifier_recipe["source_sha256"]:
            raise ValueError("Tracked verifier source differs from formal recipe")
        self.recipe_sha = digest(self.recipe_path)
        self.runtime_path = self.root / "build/formal-runtime.json"

    def tools(self):
        home_text = os.environ.get("JAVA_HOME", "")
        home = Path(home_text).expanduser()
        if (
            not home_text
            or not home.is_absolute()
            or home.resolve() != home
            or not home.is_dir()
        ):
            raise ValueError("Set explicit nonsymlink JAVA_HOME to pinned JBR 21.0.10")
        java = confined(home, "bin/java.exe" if os.name == "nt" else "bin/java")
        regular(java, 16 * MAX_BYTES)
        if not os.access(java, os.X_OK):
            raise ValueError("JAVA_HOME launcher is not executable")
        release = confined(home, "release")
        metadata = {}
        for line in regular(release).read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if not separator or key in metadata:
                raise ValueError("Malformed JDK release metadata")
            metadata[key] = json.loads(value)
        requirement = self.recipe["jdk"]
        if (
            metadata.get("JAVA_VERSION") != requirement["version"]
            or metadata.get("IMPLEMENTOR") != requirement["implementor"]
            or metadata.get("IMPLEMENTOR_VERSION") != requirement["implementor_version"]
        ):
            raise ValueError("JAVA_HOME release does not match pinned JBR metadata")
        cache = Path(
            os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))
        ).expanduser()
        if not cache.is_absolute():
            raise ValueError("XDG_CACHE_HOME must be absolute")
        jar = confined(cache, self.recipe["tlc"]["cache_path"])
        requirement = self.recipe["tlc"]
        if (
            regular(jar, requirement["bytes"]).stat().st_size != requirement["bytes"]
            or digest(jar) != requirement["sha256"]
        ):
            raise ValueError(
                "Pinned TLC cache is absent or changed; no download attempted"
            )
        return {
            "java_home": str(home),
            "java": str(java),
            "java_sha256": digest(java),
            "java_release_sha256": digest(release),
            "jdk_release": metadata,
            "jar": str(jar),
            "jar_sha256": digest(jar),
        }

    def modules(self):
        cache = Path(
            os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
        ).expanduser()
        if not cache.is_absolute():
            raise ValueError("GRADLE_USER_HOME must be absolute")
        modules = self.recipe["kotlin_modules"]
        if (
            not isinstance(modules, list)
            or len(modules) != 7
            or len({p["module"] for p in modules}) != 7
        ):
            raise ValueError("Expected seven distinct pinned Kotlin modules")
        result = []
        for item in modules:
            if set(item) != {"module", "cache_path", "bytes", "sha256"}:
                raise ValueError("Invalid Kotlin module recipe")
            parts = item["module"].split(":")
            if len(parts) != 3 or any(
                not re.fullmatch(r"[A-Za-z0-9_.-]+", p) for p in parts
            ):
                raise ValueError("Invalid Kotlin module coordinate")
            group, artifact, version = parts
            coordinate = relative(item["cache_path"])
            if (
                len(coordinate.parts) != 5
                or coordinate.parts[:3] != (group, artifact, version)
                or not re.fullmatch(r"[0-9a-f]{40}", coordinate.parts[3])
                or coordinate.parts[4] != artifact + "-" + version + ".jar"
            ):
                raise ValueError(
                    "Kotlin cache path does not match exact module coordinate"
                )
            path = confined(
                cache, "caches/modules-2/files-2.1/" + coordinate.as_posix()
            )
            if (
                not isinstance(item["bytes"], int)
                or item["bytes"] <= 0
                or item["bytes"] > 64 * MAX_BYTES
                or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"])
            ):
                raise ValueError("Invalid Kotlin byte/digest pin")
            if (
                regular(path, item["bytes"]).stat().st_size != item["bytes"]
                or digest(path) != item["sha256"]
            ):
                raise ValueError(
                    "Pinned Kotlin cache input is absent or changed; no download attempted"
                )
            result.append({**item, "path": str(path)})
        return result

    def inventory(self, folder):
        confined(self.root, folder.relative_to(self.root).as_posix())
        if not folder.is_dir():
            raise ValueError("Missing prepared verifier directory")
        actual = {}
        entries = []
        for entry in folder.iterdir():
            entries.append(entry)
            if len(entries) > 5:
                raise ValueError("Unexpected verifier root inventory")
        for path in entries:
            if path.name == "META-INF" and not path.is_symlink() and path.is_dir():
                nested = []
                for entry in path.iterdir():
                    nested.append(entry)
                    if len(nested) > 1:
                        raise ValueError("Unexpected verifier metadata inventory")
                if len(nested) != 1:
                    raise ValueError("Unexpected verifier metadata inventory")
                entries.extend(nested)
                continue
            regular(path)
            name = path.relative_to(folder).as_posix()
            if name != "receipt.json":
                actual[name] = digest(path)
        if actual != self.recipe["verifier"]["artifacts"]:
            raise ValueError("Compiled artifacts differ from tracked recipe")
        return actual

    def native_runtime(self):
        spec = read_json(confined(self.root, "tools.json"))
        runtime = read_json(confined(self.root, "runtime.json"))
        if not isinstance(runtime, dict) or runtime.get("spec") != spec:
            raise ValueError(
                "Native formatter runtime spec is stale; run supported preparation"
            )

    def receipt(self, require_native=True):
        if (
            digest(regular(self.recipe_path)) != self.recipe_sha
            or digest(regular(confined(self.root, self.recipe["verifier"]["source"])))
            != self.recipe["verifier"]["source_sha256"]
        ):
            raise ValueError("Tracked formal preparation inputs changed")
        tools, modules = self.tools(), self.modules()
        confined(self.root, "build/formal-runtime.json")
        runtime = read_json(self.runtime_path)
        if (
            not isinstance(runtime, dict)
            or set(runtime)
            != {
                "schema_version",
                "formal_recipe_sha256",
                "receipt_sha256",
                "tools",
                "modules",
            }
            or runtime["schema_version"] != 1
            or runtime["formal_recipe_sha256"] != self.recipe_sha
            or runtime["tools"] != tools
            or runtime["modules"] != modules
        ):
            raise ValueError("Formal runtime binding changed; prepare explicitly")
        self.inventory(self.verifier)
        receipt_path = confined(self.root, "build/tlc-verifier/receipt.json")
        receipt = read_json(receipt_path)
        if (
            digest(receipt_path) != runtime["receipt_sha256"]
            or not isinstance(receipt, dict)
            or set(receipt)
            != {
                "schema_version",
                "formal_recipe_sha256",
                "source_sha256",
                "classes",
                "tools",
                "modules",
                "compiler_stdout",
                "compiler_stderr",
            }
            or receipt["schema_version"] != 1
            or receipt["formal_recipe_sha256"] != self.recipe_sha
            or receipt["source_sha256"] != self.recipe["verifier"]["source_sha256"]
            or receipt["classes"] != self.recipe["verifier"]["artifacts"]
            or receipt["tools"] != tools
            or receipt["modules"] != modules
        ):
            raise ValueError("Verifier receipt is unbound or differs from tracked pins")
        if require_native:
            self.native_runtime()
        return modules

    def classpath(self, require_native=True):
        modules = self.receipt(require_native)
        stdlib = next(p["path"] for p in modules if ":kotlin-stdlib:" in p["module"])
        return os.pathsep.join([self.tools()["jar"], str(self.verifier), stdlib])

    def prepare(self, run_java):
        tools, modules = self.tools(), self.modules()
        if self.verifier.is_symlink() or self.runtime_path.is_symlink():
            raise ValueError("Symlinked formal cache or runtime")
        if self.verifier.exists() or self.runtime_path.exists():
            self.receipt(require_native=False)
            return  # Compile once; never silently rebuild an invalid/partial cache.
        build = self.root / "build"
        if build.is_symlink():
            raise ValueError("Symlinked formal build directory")
        build.mkdir(exist_ok=True)
        lock = build / ".formal-prepare.lock"
        lock.mkdir()  # Exclusive preparation; stale ownership requires inspection.
        try:
            with tempfile.TemporaryDirectory(
                prefix="formal-prepare-", dir=build
            ) as temporary:
                folder = Path(temporary)
                stdlib = next(
                    p["path"] for p in modules if ":kotlin-stdlib:" in p["module"]
                )
                annotations = next(
                    p["path"] for p in modules if ":annotations:" in p["module"]
                )
                stdout, stderr = run_java(
                    [
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib",
                        "-no-reflect",
                        "-classpath",
                        os.pathsep.join([tools["jar"], stdlib, annotations]),
                        "-jvm-target",
                        "21",
                        "-d",
                        str(folder),
                        str(self.root / "TlcConfigVerifier.kt"),
                    ],
                    folder,
                    classpath=os.pathsep.join(p["path"] for p in modules),
                    compiler=True,
                )
                classes = self.inventory(folder)
                receipt = {
                    "schema_version": 1,
                    "formal_recipe_sha256": self.recipe_sha,
                    "source_sha256": self.recipe["verifier"]["source_sha256"],
                    "classes": classes,
                    "tools": tools,
                    "modules": modules,
                    "compiler_stdout": stdout.decode("utf-8"),
                    "compiler_stderr": stderr.decode("utf-8"),
                }
                atomic_json(folder / "receipt.json", receipt)
                if self.verifier.exists() or self.runtime_path.exists():
                    raise ValueError("Formal cache changed during preparation")
                os.replace(folder, self.verifier)
            atomic_json(
                self.runtime_path,
                {
                    "schema_version": 1,
                    "formal_recipe_sha256": self.recipe_sha,
                    "receipt_sha256": digest(self.verifier / "receipt.json"),
                    "tools": tools,
                    "modules": modules,
                },
            )
            self.receipt(require_native=False)
        finally:
            lock.rmdir()
