#!/usr/bin/env python3
"""Explicit transport of existing pinned inputs before offline preparation."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def allowed_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https"
        or parsed.netloc != "repo.maven.apache.org"
        or not parsed.path.startswith("/maven2/")
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("Formal inputs must use the exact Maven Central HTTPS origin")
    return url


COMPILER_MODULE = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21"
COMPILER_ORIGIN = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.21/kotlin-compiler-embeddable-2.3.21.jar"
COMPILER_PUBLISHER = "https://github.com/JetBrains/kotlin/releases/download/v2.3.21/kotlin-compiler-embeddable-2.3.21.jar"
COMPILER_ASSET = "https://release-assets.githubusercontent.com/github-production-release-asset/3432266/8c5a5b7a-09b2-47cf-9427-cdcb63c3596b"


def publisher_hop(original, meta, current, location, hop):
    # This one observed publisher chain transports the existing recipe input;
    # ensure_input still verifies its full size, SHA256 and Gradle SHA1.
    if original != COMPILER_ORIGIN or meta.get("module") != COMPILER_MODULE:
        return False
    if hop == 0:
        return current == original and location == COMPILER_PUBLISHER
    if hop == 1 and current == COMPILER_PUBLISHER:
        parsed = urllib.parse.urlsplit(location)
        return (
            parsed.scheme == "https"
            and parsed.netloc == "release-assets.githubusercontent.com"
            and parsed.path == urllib.parse.urlsplit(COMPILER_ASSET).path
            and bool(parsed.query)
            and not parsed.fragment
        )
    return False


def public_url(url):
    try:
        parsed = urllib.parse.urlsplit(url)
        return {"scheme": parsed.scheme, "host": parsed.hostname, "path": parsed.path}
    except ValueError:
        return {"scheme": "invalid", "host": None, "path": None}


class MavenResponseRedirect(ValueError):
    def __init__(self, source, location, status):
        self.location = location
        self.context = {
            "status": status,
            "source": public_url(source),
            "location": public_url(location),
        }
        super().__init__(json.dumps(self.context, sort_keys=True))


class MavenRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Close before exposing a redirect to the bounded explicit route policy.
        # urllib must never automatically follow a Location.
        response.close()
        raise MavenResponseRedirect(request.full_url, new_url, code)


def fetch_exact(url, meta, opener=None):
    allowed_url(url)
    fetch = opener or urllib.request.build_opener(MavenRedirect()).open
    deadline = time.monotonic() + 60
    context = {"source": public_url(url)}
    for attempt in range(1, 4):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        transient = True
        try:
            current = url
            for hop in range(3):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("Formal input transport window expired")
                try:
                    response = fetch(current, timeout=min(20, remaining))
                except MavenResponseRedirect as error:
                    if publisher_hop(url, meta, current, error.location, hop):
                        current = error.location
                        continue
                    raise
                try:
                    if response.geturl() != current or current == COMPILER_PUBLISHER:
                        raise ValueError(
                            "Final formal input URL differs from exact artifact route"
                        )
                except BaseException:
                    response.close()
                    raise
                return response
        except MavenResponseRedirect as error:
            context = error.context
        except urllib.error.HTTPError as error:
            context = {"status": error.code, "source": public_url(url)}
            error.close()
            transient = error.code in {408, 425, 429, 500, 502, 503, 504}
        except urllib.error.URLError as error:
            reason = error.reason
            transient = isinstance(reason, (TimeoutError, ConnectionError)) or (
                isinstance(reason, socket.gaierror) and reason.errno == socket.EAI_AGAIN
            )
            context = {"error_type": type(reason).__name__, "source": public_url(url)}
        except (TimeoutError, ConnectionError) as error:
            context = {"error_type": type(error).__name__, "source": public_url(url)}
        context = {
            **context,
            "module": meta.get("module", "pinned formal input"),
            "attempt": attempt,
            "max_attempts": 3,
        }
        if not transient or attempt == 3:
            break
        print(
            "Formal input transport retry: " + json.dumps(context, sort_keys=True),
            file=sys.stderr,
        )
        time.sleep(min(0.25 * attempt, max(0, deadline - time.monotonic())))
    raise ValueError(
        "Formal input transport failed: " + json.dumps(context, sort_keys=True)
    ) from None


def verified(path, meta):
    info = path.lstat()
    if (
        not stat.S_ISREG(info.st_mode)
        or info.st_nlink != 1
        or info.st_size != meta["bytes"]
    ):
        raise ValueError("Expected exact regular unshared formal input: " + str(path))
    sha256, sha1 = hashlib.sha256(), hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            sha256.update(chunk)
            sha1.update(chunk)
    if sha256.hexdigest() != meta["sha256"] or (
        meta.get("sha1") is not None and sha1.hexdigest() != meta["sha1"]
    ):
        raise ValueError(
            "Formal input digest changed; inspect without overwriting: " + str(path)
        )


def ensure_input(root, relative, meta, *, url=None, source=None, opener=None):
    if (
        not isinstance(meta.get("bytes"), int)
        or not 0 < meta["bytes"] <= 64 * 1024 * 1024
        or not re.fullmatch(r"[0-9a-f]{64}", meta.get("sha256", ""))
    ):
        raise ValueError("Invalid bounded formal input pin")
    coordinate = Path(relative)
    if (
        coordinate.is_absolute()
        or not coordinate.parts
        or ".." in coordinate.parts
        or "\\" in relative
        or coordinate.as_posix() != relative
        or root.is_symlink()
    ):
        raise ValueError("Unsafe formal input cache coordinate")
    root.mkdir(parents=True, exist_ok=True)
    path = root
    for part in coordinate.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("Symlink in formal input cache")
    if (url is None) == (source is None):
        raise ValueError("Choose exactly one explicit pinned input source")
    if url is not None:
        allowed_url(url)
    if source is not None:
        verified(
            source, meta
        )  # Never hide changed tracked vendor bytes behind a cache hit.
    if path.exists():
        verified(path, meta)
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".formal-input-", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            if source is None:
                stream = fetch_exact(url, meta, opener)
            else:
                stream = source.open("rb")
            with stream:
                count = 0
                while chunk := stream.read(min(65536, meta["bytes"] - count + 1)):
                    count += len(chunk)
                    if count > meta["bytes"]:
                        raise ValueError("Formal download exceeded its exact byte pin")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
        if count != meta["bytes"]:
            raise ValueError("Incomplete formal input download")
        verified(temporary, meta)
        # Atomic exclusive publication. Never replace another producer's input.
        os.link(temporary, path, follow_symlinks=False)
        temporary.unlink()
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        verified(path, meta)
        return path
    finally:
        temporary.unlink(missing_ok=True)


def maven_inputs(recipe):
    result = []
    for item in recipe["kotlin_modules"]:
        group, artifact, version = item["module"].split(":")
        if any(
            not re.fullmatch(r"[A-Za-z0-9_.-]+", value)
            for value in (group, artifact, version)
        ):
            raise ValueError("Unsafe pinned Maven module coordinate")
        coordinate = Path(item["cache_path"])
        if (
            len(coordinate.parts) != 5
            or coordinate.parts[:3] != (group, artifact, version)
            or not re.fullmatch(r"[0-9a-f]{40}", coordinate.parts[3])
            or coordinate.parts[4] != f"{artifact}-{version}.jar"
        ):
            raise ValueError("Pinned Maven cache coordinate differs from module")
        result.append(
            {
                **item,
                "relative": "caches/modules-2/files-2.1/" + item["cache_path"],
                "sha1": coordinate.parts[3],
                "url": f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar",
            }
        )
    if len(result) != 7 or len({x["module"] for x in result}) != 7:
        raise ValueError("Expected the existing seven distinct compiler inputs")
    return result


def main():
    def expired(*_):
        raise TimeoutError("Explicit formal input provisioning exceeded 180 seconds")

    signal.signal(signal.SIGALRM, expired)
    signal.alarm(180)
    formal = Path(__file__).resolve().parents[2] / ".hypertext/formatter-tools"
    spec = importlib.util.spec_from_file_location(
        "formal_binding", formal / "formal_binding.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    binding = module.FormalBinding(formal, formal / "build/tlc-verifier")
    cache = Path(
        os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))
    ).expanduser()
    gradle = Path(
        os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))
    ).expanduser()
    if not cache.is_absolute() or not gradle.is_absolute():
        raise ValueError("Explicit formal cache roots must be absolute")
    ensure_input(
        cache,
        binding.recipe["tlc"]["cache_path"],
        binding.recipe["tlc"],
        source=formal / "vendor/tla2tools-2ef2af8.jar",
    )
    binding.tools()  # Existing strict JAVA_HOME/vendor/version/TLC checks, before network.
    for item in maven_inputs(binding.recipe):
        ensure_input(gradle, item["relative"], item, url=item["url"])
    binding.modules()  # Existing exact seven-module validation; no JVM or compilation.
    signal.alarm(0)
    print("Verified pinned formal inputs. Normal offline preparation remains required.")


if __name__ == "__main__":
    main()
