"""Separate GMD snapshot preparation from the two live restore drivers."""

from pathlib import Path
import json
import hashlib
import re
import shlex
import signal
import xml.etree.ElementTree as ET


def require_cold_boot_capability(h, sdk):
    capability = h.command(str(sdk / "emulator/emulator"), "-help-no-snapshot-load")
    if b"snapshot" not in capability.stdout + capability.stderr:
        raise RuntimeError(
            "The installed emulator must support cold boots of managed instances"
        )


def require_cached_image(sdk, avd):
    match = re.fullmatch(
        r"dev34_(google_apis(?:_playstore)?)_(arm64-v8a|x86_64)_Pixel_9_Pro", avd
    )
    if match is None:
        raise RuntimeError("The managed profile has no supported cached image mapping")
    source, abi = match.groups()
    image = sdk / "system-images/android-34" / source / abi
    package = image / "package.xml"
    if not package.is_file() or not (image / "system.img").is_file():
        raise RuntimeError(
            "The exact cached managed runtime is absent; installation is not permitted"
        )
    expected = f"system-images;android-34;{source};{abi}"
    packages = [
        node.get("path")
        for node in ET.parse(package).getroot().iter()
        if node.tag.rsplit("}", 1)[-1] == "localPackage"
    ]
    if packages != [expected]:
        raise RuntimeError(
            "The installed package does not match the managed runtime mapping"
        )
    return {
        "package": expected,
        "packageSha256": hashlib.sha256(package.read_bytes()).hexdigest(),
    }


def _read_only_verifier(arguments, avd):
    if arguments.count("-avd") > 1:
        return None
    names = [value[1:] for value in arguments if value.startswith("@")]
    if arguments.count("-avd") == 1:
        index = arguments.index("-avd") + 1
        if index < len(arguments):
            names.append(arguments[index])
    if (
        names != [avd]
        or arguments.count("-id") != 1
        or arguments.count("-read-only") != 1
        or arguments.count("-no-snapshot-save") != 1
        or "-check-snapshot-loadable" in arguments
    ):
        return None
    index = arguments.index("-id") + 1
    if index >= len(arguments):
        return None
    value = arguments[index]
    return (
        value
        if re.fullmatch(
            r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}_GradleManagedDevice", value
        )
        else None
    )


def retire_preparation(
    invocations, nonce, avd, *, identity, signal_process, sleep, monotonic, grace=5
):
    retired = []
    for record in invocations:
        pid, birth = record.get("launcherPid"), record.get("birth")
        if (
            record.get("nonce") != nonce
            or record.get("purpose") != "preparation"
            or type(pid) is not int
            or pid <= 1
            or not isinstance(birth, str)
            or not birth
        ):
            raise RuntimeError(
                "Preparation invocation has no exact purpose/process identity"
            )

        def observed():
            actual = identity(pid)
            if actual is not None and actual.get("birth") != birth:
                raise identity_refusal(pid, birth, actual)
            return actual

        end = monotonic() + grace
        natural_exit_deadline = end - grace / 2
        current = observed()
        while current is not None and monotonic() < natural_exit_deadline:
            sleep(min(0.1, max(0, natural_exit_deadline - monotonic())))
            current = observed()
        if current is None:
            retired.append({"pid": pid, "retired": True, "cleanup": []})
            continue
        identifier = _read_only_verifier(record["arguments"], avd)
        if identifier is None:
            raise RuntimeError("A writable or unknown preparation process remains live")

        def live():
            actual = observed()
            if actual is None:
                return False
            arguments = shlex.split(actual["command"])
            if (
                actual.get("birth") != birth
                or not arguments
                or not Path(arguments[0]).name.startswith("qemu-system-")
                or _read_only_verifier(arguments[1:], avd) != identifier
            ):
                raise identity_refusal(pid, birth, actual)
            return True

        actions = []
        for action in (signal.SIGTERM, signal.SIGKILL):
            if not live():
                break
            try:
                signal_process(pid, action)
            except ProcessLookupError:
                if live():
                    raise
            actions.append(action.name)
            deadline = end if action == signal.SIGKILL else end - grace / 4
            while observed() is not None and monotonic() < deadline:
                sleep(min(0.1, max(0, deadline - monotonic())))
        if live():
            raise RuntimeError("The owned read-only preparation guest did not retire")
        retired.append(
            {"pid": pid, "managedId": identifier, "retired": True, "cleanup": actions}
        )
    return retired


def identity_refusal(pid, birth, actual):
    failure = RuntimeError("Preparation process identity changed; cleanup refused")
    command = actual.get("command", "")
    failure.identity_diagnostic = {
        "pid": pid,
        "expectedBirth": birth,
        "observedBirth": actual.get("birth"),
        "commandSha256": hashlib.sha256(command.encode()).hexdigest(),
        "qemuCommand": bool(
            re.search(r"(?:^|/)qemu-system-[A-Za-z0-9_-]+(?: |$)", command)
        ),
        "readOnlyFlagPresent": "-read-only" in command,
        "noSnapshotSaveFlagPresent": "-no-snapshot-save" in command,
    }
    return failure


def prepare_managed_device(
    h, sdk, output, avd, nonce, device_name, package, budget, observer=None
):
    h.save(output / "preparation-image.json", require_cached_image(sdk, avd))
    facade = h.EMULATOR["create_managed_sdk"](
        sdk, output / "managed-sdk-preparation", avd, nonce, purpose="preparation"
    )
    env = h.managed_environment(facade, h.os.environ, package)
    if observer is not None:
        observer.set_phase("preparation")
    boot_deadline = h.time.monotonic() + budget["boot"]
    args = [
        "./gradlew",
        f":tools:native-restore-driver:{device_name}Setup",
        f"-Pandroid.testInstrumentationRunnerArguments.nativeManagedNonce={nonce}",
        "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader",
        *bounded_gradle_arguments(),
    ]
    with (output / "preparation.log").open("wb") as log:
        process = h.subprocess.Popen(
            args, cwd=h.ROOT, env=env, stdout=log, stderr=h.subprocess.STDOUT
        )
        h.save(
            output / "preparation-process.json",
            {"nonce": nonce, "pid": process.pid, "phase": "preparation"},
        )
        process.wait(timeout=max(0, boot_deadline - h.time.monotonic()))
        if process.returncode != 0:
            raise RuntimeError(
                "Managed snapshot preparation failed; inspect preparation.log"
            )
        paths = sorted((output / "managed-emulator-invocations").glob("*.json"))
        invocations = [json.loads(path.read_text()) for path in paths]
        try:
            retired = retire_preparation(
                invocations,
                nonce,
                avd,
                identity=h.discovery_identity,
                signal_process=h.os.kill,
                sleep=h.time.sleep,
                monotonic=h.time.monotonic,
            )
        except RuntimeError as failure:
            if hasattr(failure, "identity_diagnostic"):
                try:
                    h.save(
                        output / "preparation-refusal.json",
                        {"nonce": nonce, **failure.identity_diagnostic},
                    )
                except Exception as diagnostic_failure:
                    h.add_failure_note(
                        failure,
                        "Preparation diagnostic failed: "
                        + type(diagnostic_failure).__name__,
                    )
            raise
        if not h.EMULATOR["managed_processes_stopped"](
            process, invocations, set(), h.process_snapshot(), facade
        ):
            raise RuntimeError(
                "Managed preparation still has a live consumer; driver launch refused"
            )
        h.save(
            output / "preparation-retirement.json",
            {"nonce": nonce, "accepted": True, "processes": retired},
        )
        if h.time.monotonic() >= boot_deadline:
            raise RuntimeError("Preparation consumed the unchanged managed boot budget")
        if observer is not None:
            observer.set_phase("drivers")
        return boot_deadline


def remove_retired_facades(h, driver_sdk, output):
    # AVD image paths may reference preparation's facade until the driver generation ends.
    for facade in (driver_sdk, output / "managed-sdk-preparation"):
        if facade.exists():
            h.shutil.rmtree(facade)


def bounded_gradle_arguments():
    # Device tests consume compiled inputs; do not retain an independent compiler heap alongside guests.
    return [
        "--no-daemon",
        "--no-configuration-cache",
        "--max-workers=2",
        "--console=plain",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED",
    ]


def start_driver_generation(h, args, env, log, boot_deadline):
    require_boot_budget(h, boot_deadline)
    return h.subprocess.Popen(
        args, cwd=h.ROOT, env=env, stdout=log, stderr=h.subprocess.STDOUT
    )


def require_boot_budget(h, boot_deadline):
    if boot_deadline is not None and h.time.monotonic() >= boot_deadline:
        raise RuntimeError(
            "The managed boot budget expired; no later driver admission is permitted"
        )


def driver_arguments(
    h, group, device_name, app, probe, nonce, hashes, budget, capacity
):
    return [
        "./gradlew",
        f":tools:native-restore-driver:{group}GroupDebugAndroidTest",
        "-x",
        f":tools:native-restore-driver:{device_name}Setup",
        *h.pinned_install_arguments(app, probe),
        f"-Pandroid.testInstrumentationRunnerArguments.class={h.DRIVER_CASE}",
        "-Pandroid.testInstrumentationRunnerArguments.nativeManagedTransport=true",
        f"-Pandroid.testInstrumentationRunnerArguments.nativeManagedNonce={nonce}",
        f"-Pandroid.testInstrumentationRunnerArguments.nativeAppSha256={hashes[0]}",
        f"-Pandroid.testInstrumentationRunnerArguments.nativeProbeSha256={hashes[1]}",
        f"-Pandroid.testInstrumentationRunnerArguments.nativeManagedLifetimeMillis={budget['driverMillis']}",
        f"-Pandroid.experimental.testOptions.managedDevices.maxConcurrentDevices={capacity}",
        "-Pandroid.experimental.androidTest.numManagedDeviceShards=2",
        "-Pandroid.experimental.androidTest.builtin_test_platform=false",
        "-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader",
        *bounded_gradle_arguments(),
    ]
