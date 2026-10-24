#!/usr/bin/env python3
"""Make room for managed Android guests on disposable hosted Linux runners."""

from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

SDK = Path("/usr/local/lib/android/sdk")
DOTNET = Path("/usr/share/dotnet")
MIN_FREE = 12 * 1024**3
IMAGE = re.compile(
    r"system-images;android-[0-9]+(?:\.[0-9]+)?;[A-Za-z0-9_]+;(?:x86_64|x86|arm64-v8a|armeabi-v7a)"
)


def require_host(environment):
    if (
        environment.get("GITHUB_ACTIONS") != "true"
        or environment.get("RUNNER_ENVIRONMENT") != "github-hosted"
        or environment.get("RUNNER_OS") != "Linux"
        or any(
            not re.fullmatch(r"[0-9]+", environment.get(key, ""))
            for key in ["GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"]
        )
    ):
        raise RuntimeError(
            "Native host cleanup requires a disposable GitHub-hosted Linux job"
        )


def real_directory(path):
    if path.is_symlink() or path.resolve() != path:
        raise RuntimeError(f"Refusing symlink in native host cleanup scope: {path}")
    if not path.is_dir():
        raise RuntimeError(f"Expected native host directory: {path}")


def unused_images(sdk, keep_image):
    images = sdk / "system-images"
    if images.is_symlink():
        raise RuntimeError(f"Refusing symlink in native host cleanup scope: {images}")
    if not images.exists():
        return []
    real_directory(images)
    result = []
    for version in sorted(images.iterdir()):
        if not version.is_dir() and not version.is_symlink():
            continue
        real_directory(version)
        for tag in sorted(version.iterdir()):
            if not tag.is_dir() and not tag.is_symlink():
                continue
            real_directory(tag)
            for abi in sorted(tag.iterdir()):
                if not abi.is_dir() and not abi.is_symlink():
                    continue
                real_directory(abi)
                package = ";".join(abi.relative_to(sdk).parts)
                if (
                    IMAGE.fullmatch(package)
                    and (abi / "package.xml").is_file()
                    and package != keep_image
                ):
                    result.append(package)
    return result


def reclaim(environment, keep_image, run=subprocess.run):
    require_host(environment)
    if not IMAGE.fullmatch(keep_image):
        raise ValueError("Supply the exact managed system-image package to preserve")
    if environment.get("ANDROID_HOME") != str(SDK) or environment.get(
        "ANDROID_SDK_ROOT", str(SDK)
    ) != str(SDK):
        raise RuntimeError("Refusing an unexpected hosted Android SDK root")
    real_directory(SDK)
    packages = unused_images(SDK, keep_image)
    # Validate every removal before performing any of them. These are preinstalled
    # tools on a single-use hosted VM, never a developer or self-hosted SDK.
    remove_dotnet = DOTNET.exists() or DOTNET.is_symlink()
    if remove_dotnet:
        real_directory(DOTNET)
    if remove_dotnet:
        run(["sudo", "--", "rm", "-rf", "--", str(DOTNET)], check=True, timeout=120)
    if packages:
        run(
            [
                str(SDK / "cmdline-tools/latest/bin/sdkmanager"),
                "--uninstall",
                *packages,
            ],
            check=True,
            timeout=120,
        )
    return {
        "preservedImage": keep_image,
        "removedImages": packages,
        "removedDotnet": remove_dotnet,
    }


def check_capacity(paths, disk_usage=shutil.disk_usage):
    filesystems = []
    for requested in paths:
        path = Path(requested).absolute()
        existing = path
        while not existing.exists():
            existing = existing.parent
        filesystems.append(
            {
                "path": str(path),
                "existingParent": str(existing),
                "freeBytes": disk_usage(existing).free,
            }
        )
    # Leave room for both fresh guests and proof files beyond the emulator's
    # startup disk check instead of launching on an almost-full runner.
    return {
        "minimumFreeBytes": MIN_FREE,
        "filesystems": filesystems,
        "ready": all(item["freeBytes"] >= MIN_FREE for item in filesystems),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="mode", required=True)
    commands.add_parser("reclaim").add_argument("--keep-image", required=True)
    commands.add_parser("check").add_argument(
        "--path", type=Path, action="append", required=True
    )
    args = parser.parse_args()
    require_host(os.environ)
    workspace = Path(os.environ["GITHUB_WORKSPACE"])
    if args.mode == "reclaim":
        before = shutil.disk_usage(workspace).free
        report = reclaim(os.environ, args.keep_image)
        report.update(
            beforeFreeBytes=before, afterFreeBytes=shutil.disk_usage(workspace).free
        )
    else:
        report = check_capacity(args.path)
    directory = Path(os.environ["RUNNER_TEMP"]) / "native-inputs"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"native-host-capacity-{args.mode}.json"
    with open(
        target,
        "x",
        opener=lambda name, flags: os.open(name, flags | os.O_NOFOLLOW, 0o600),
    ) as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps(report, indent=2))
    if report.get("ready") is False:
        sys.exit(
            "Native acceptance requires 12 GiB free on each guest/output filesystem; see the retained capacity report"
        )


if __name__ == "__main__":
    main()
