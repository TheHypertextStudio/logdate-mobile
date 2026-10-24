"""Managed acceptance pair with explicit entrypoint dependencies."""


def run_managed_pair(
    h,
    options,
    *,
    recovery_assets=None,
    fixture_control=None,
    expected_android_origin=None,
    resource_owner=None,
):
    h.validate_native_arguments(options.native_arguments)
    if options.mode != "transfer" and options.native_arguments:
        raise ValueError("Only transfer mode accepts native scenario arguments")
    if options.mode == "enrollment" and any(
        path is None or not path.is_file()
        for path in (options.classpath, options.fixture_environment)
    ):
        raise ValueError(
            "Enrollment requires an existing private database fixture and server classpath"
        )
    if options.mode == "upgrade" and (
        options.fixture_environment is None or not options.fixture_environment.is_file()
    ):
        raise ValueError(
            "Released upgrade requires an existing private artifact fixture"
        )
    h.validate_followups(options.mode, options.after_transfer)
    budget = h.acceptance_budget(len(options.after_transfer), mode=options.mode)
    output = options.output.resolve()
    output.mkdir(mode=0o700, exist_ok=options.mode == "manual-account")
    observer = (
        h.RESOURCES["start_resource_observer"](output)
        if resource_owner is not None
        else None
    )
    if observer is not None:
        resource_owner.append(observer)
    nonce = str(h.uuid.uuid4())
    sdk = options.sdk.resolve()
    adb = str(sdk / "platform-tools/adb")
    package = h.os.environ.get(
        "LOGDATE_NATIVE_ACCEPTANCE_PACKAGE", "studio.hypertext.logdate.debug"
    )
    app, probe = h.candidate_apk_paths(
        options.candidate_app_apk, options.candidate_test_apk, h.ROOT
    )
    if options.candidate_app_apk is None:
        h.assemble_candidate_apks(
            output, package, recovery_assets=recovery_assets, sdk=sdk
        )
        app, probe = h.candidate_apk_paths(
            options.candidate_app_apk, options.candidate_test_apk, h.ROOT
        )
    for apk in (app, probe):
        if not apk.is_file():
            raise RuntimeError("Candidate build did not produce LogDate and probe APKs")
    # Both Gradle's initial installation and later native reinstallation use these
    # immutable copies; another build cannot replace the attested candidate.
    artifacts = output / "apks"
    artifacts.mkdir(mode=0o700)
    pinned = []
    for apk in (app, probe):
        destination = artifacts / apk.name
        h.shutil.copyfile(apk, destination)
        destination.chmod(0o400)
        pinned.append(destination)
    app, probe = pinned
    h.verify_pinned_packages(sdk, app, probe, package)
    if (
        expected_android_origin is not None
        and h.android_signing_origin(sdk, app) != expected_android_origin
    ):
        raise RuntimeError(
            "The pinned APK signer changed after the local recovery fixture was created"
        )
    initial_app, initial_probe, frozen_fixture = h.initial_installation(
        options.mode, app, probe, options.fixture_environment, output
    )
    hashes = [
        h.hashlib.sha256(apk.read_bytes()).hexdigest()
        for apk in (initial_app, initial_probe)
    ]
    architecture = "arm64-v8a" if h.platform.machine() == "arm64" else "x86_64"
    image = "google_apis_playstore" if options.mode == "d2d" else "google_apis"
    group = "nativeD2dPair" if options.mode == "d2d" else "nativeRestorePair"
    device_name = "d2dPairApi34" if options.mode == "d2d" else "restorePairApi34"
    avd = f"dev34_{image}_{architecture}_Pixel_9_Pro"
    properties = h.ROOT / "local.properties"
    if properties.is_file() and h.re.search(
        r"(?m)^\s*sdk(?:\\\.|\.)dir(?:\s|:|=)", properties.read_text()
    ):
        raise RuntimeError(
            "The managed SDK facade cannot override sdk.dir in local.properties"
        )
    h.PREPARATION["require_cold_boot_capability"](h, sdk)
    boot_deadline = h.PREPARATION["prepare_managed_device"](
        h, sdk, output, avd, nonce, device_name, package, budget, observer=observer
    )
    managed_sdk = h.EMULATOR["create_managed_sdk"](
        sdk, output / "managed-sdk", avd, nonce, purpose="drivers"
    )
    h.verify_managed_emulator(managed_sdk, output)
    env = h.managed_environment(managed_sdk, h.os.environ, package)
    env["LOGDATE_NATIVE_ACCEPTANCE_TIMEOUT_SECONDS"] = str(budget["native"])
    initial_processes = h.command("ps", "-axo", "pid,args").stdout.decode().splitlines()
    capacity = h.TARGET["managed_device_capacity"](initial_processes)
    args = h.PREPARATION["driver_arguments"](
        h,
        group,
        device_name,
        initial_app,
        initial_probe,
        nonce,
        hashes,
        budget,
        capacity,
    )
    existing = {
        int(line.split(None, 1)[0])
        for line in initial_processes
        if line.strip().split(None, 1)[0].isdigit() and "qemu-system" in line
    }
    custody_path = output / "custody.json"
    results = (
        h.ROOT
        / "tools/native-restore-driver/build/outputs/androidTest-results/managedDevice/debug"
        / device_name
    )
    results_started_ns = h.time.time_ns()
    with (output / "gradle.log").open("wb") as log:
        process = h.PREPARATION["start_driver_generation"](
            h, args, env, log, boot_deadline
        )
        # Retain this handle even if boot observation times out; never launch a replacement
        # while Gradle can still own the same test generation.
        custody = {
            "nonce": nonce,
            "adb": adb,
            "phase": "discovering",
            "targets": {},
            "gradle": {"pid": process.pid},
            "bootDeadline": boot_deadline,
        }
        h.save(output / "active-process.json", custody)
        accepted = False
        primary = None
        observed_managed_pids = set()
        try:
            discover_managed_pair(
                h,
                process,
                output,
                existing,
                avd,
                budget,
                adb,
                nonce,
                custody,
                observed_managed_pids,
            )
            custody["phase"] = "active"
            h.save(custody_path, custody)
            h.save(output / "active-process.json", custody)
            proxy = output / "bin"
            proxy.mkdir()
            executable = proxy / "adb"
            executable.write_text(
                "#!/bin/sh\nexec "
                + h.shlex.join(
                    [
                        h.sys.executable,
                        str(h.Path(__file__).with_name("managed-native-target.py")),
                    ]
                )
                + ' "$@"\n'
            )
            executable.chmod(0o700)
            env.update(
                PATH=str(proxy) + h.os.pathsep + env["PATH"],
                LOGDATE_MANAGED_CUSTODY=str(custody_path),
            )
            targets = custody["targets"]
            source = next(
                serial
                for serial, target in targets.items()
                if target["role"] == "source"
            )
            destination = next(
                serial
                for serial, target in targets.items()
                if target["role"] == "destination"
            )
            network_identity = {}
            stations = {}
            for serial, binding in targets.items():
                for peer in targets:
                    h.TARGET["require_binding"](custody, peer)
                observed = h.command(
                    *h.TARGET["device_command"](
                        adb, serial, ["shell", "ip", "-o", "link", "show", "wlan0"]
                    ),
                    check=False,
                )
                network_identity[binding["role"]] = {
                    "returnCode": observed.returncode,
                    "interface": observed.stdout.decode(),
                    "error": observed.stderr.decode(),
                }
                stations[serial] = h.TARGET["wifi_station_address"](observed.stdout)
            h.save(output / "managed-network-identities.json", network_identity)
            # The Play Store image forbids root reads; its provider-only profile
            # remains separate from the rooted, paired transport/network oracles.
            if options.mode != "d2d":
                station_identity = h.EMULATOR["require_managed_station_launches"](
                    output / "managed-emulator-launches", targets, nonce, stations
                )
                h.save(output / "managed-network-stations.json", station_identity)
            scenario_args = h.scenario_command(
                options.mode,
                source,
                destination,
                app,
                probe,
                output / "native-transfer",
                options.native_arguments,
                classpath=options.classpath,
                fixture_environment=frozen_fixture,
                fixture_control=fixture_control,
            )
            with (output / "native-harness.log").open("wb") as harness_log:
                h.subprocess.run(
                    scenario_args,
                    cwd=h.ROOT,
                    env=env,
                    stdout=harness_log,
                    stderr=h.subprocess.STDOUT,
                    check=True,
                    timeout=budget["native"],
                )
            for followup in options.after_transfer:
                with (output / f"{followup}-harness.log").open("wb") as harness_log:
                    h.subprocess.run(
                        h.followup_command(
                            followup, destination, app, probe, output / followup
                        ),
                        cwd=h.ROOT,
                        env=env,
                        stdout=harness_log,
                        stderr=h.subprocess.STDOUT,
                        check=True,
                        timeout=budget["followup"],
                    )
            accepted = True
        except BaseException as failure:
            primary = failure
            raise
        finally:
            custody["phase"] = "completing"
            h.save(custody_path, custody)
            # Each endpoint can disappear as soon as it acknowledges completion.
            # Terminal writes validate only their own driver, never the departed peer.
            completion_errors = []
            for serial, binding in custody["targets"].items():
                try:
                    h.TARGET["require_binding"](custody, serial)
                    result = h.json.dumps(
                        {
                            "nonce": nonce,
                            "startTicks": binding["driverStartTicks"],
                            "accepted": accepted,
                        }
                    ).encode()
                    remote = f"no_backup/managed-restore-{nonce}-complete.json"
                    script = f"cat > {h.shlex.quote(remote + '.part')} && mv {h.shlex.quote(remote + '.part')} {h.shlex.quote(remote)}"
                    h.subprocess.run(
                        [
                            adb,
                            "-s",
                            serial,
                            "shell",
                            h.shlex.join(["run-as", h.DRIVER, "sh", "-c", script]),
                        ],
                        input=result,
                        check=True,
                        capture_output=True,
                        timeout=15,
                    )
                except (
                    RuntimeError,
                    ValueError,
                    KeyError,
                    h.subprocess.SubprocessError,
                ) as failure:
                    completion_errors.append(type(failure).__name__)
            h.save(
                output / "active-process.json",
                {**custody, "completionErrors": completion_errors},
            )
            if process.poll() is None and len(custody["targets"]) == 2:
                try:
                    process.wait(timeout=budget["teardown"])
                except h.subprocess.TimeoutExpired:
                    pass
            try:
                listing = h.command("ps", "-axo", "pid,args", check=False)
                process_lines = (
                    listing.stdout.decode().splitlines()
                    if listing.returncode == 0
                    else None
                )
                invocations = [
                    h.json.loads(path.read_text())
                    for path in (output / "managed-emulator-invocations").glob("*.json")
                ]
                if h.EMULATOR["managed_processes_stopped"](
                    process,
                    invocations,
                    observed_managed_pids,
                    process_lines,
                    managed_sdk,
                ):
                    h.PREPARATION["remove_retired_facades"](h, managed_sdk, output)
            except Exception as failure:
                if primary is None:
                    primary = failure
                else:
                    h.add_failure_note(
                        primary,
                        "Could not remove private managed SDK facade: "
                        + type(failure).__name__,
                    )
            if len(custody["targets"]) == 2:
                try:
                    h.retain_terminal_results(
                        process, results, output, results_started_ns
                    )
                except Exception as failure:
                    if primary is None:
                        raise
                    h.add_failure_note(
                        primary,
                        "Could not retain managed results: " + type(failure).__name__,
                    )
        if primary is not None:
            raise primary
        if process.poll() is None:
            raise RuntimeError(
                "Gradle is still live; inspect the retained process handle"
            )
        if process.returncode != 0 or completion_errors:
            raise RuntimeError("The managed pair did not finish successfully")
        h.require_outer_results(output / "managed-results")
        h.save(
            output / "acceptance.json",
            h.acceptance_receipt(
                options.mode, options.native_arguments, options.after_transfer
            ),
        )


def discover_managed_pair(
    h,
    process,
    output,
    existing,
    avd,
    budget,
    adb,
    nonce,
    custody,
    observed_managed_pids,
):
    deadline = custody.get("bootDeadline")
    launch_deadline = h.time.monotonic() + max(1200, budget["boot"] * 2)
    while len(custody["targets"]) < 2:
        if process.poll() is not None:
            raise RuntimeError(
                "The managed pair stopped before both drivers were ready; inspect gradle.log"
            )
        identity = h.TARGET["process_identity"](process.pid)
        if nonce not in identity["command"]:
            h.time.sleep(0.2)
            continue
        custody["gradle"]["identity"] = identity
        processes = h.process_snapshot()
        if processes is None:
            if h.time.monotonic() > (deadline or launch_deadline):
                raise RuntimeError(
                    "Managed emulator process discovery expired after repeated host process-list timeouts"
                )
            h.time.sleep(0.2)
            continue
        candidates = h.TARGET["managed_candidates"](processes, existing, avd)
        candidates = h.EMULATOR["owned_managed_candidates"](
            output / "managed-emulator-launches", candidates, nonce
        )
        observed_managed_pids.update(candidates)
        try:
            candidates = h.live_managed_candidates(
                candidates, avd, diagnostics=output / "ambiguous-managed-processes.json"
            )
        except h.ManagedPairAmbiguousError as ambiguity:
            candidates = ambiguity.candidates
        custody["observedCandidates"] = candidates
        if candidates:
            deadline = h.managed_boot_deadline(
                deadline, h.time.monotonic(), budget["boot"]
            )
        if deadline is not None and h.time.monotonic() >= deadline:
            raise RuntimeError(
                "The managed pair is still owned by the recorded Gradle handle; boot observation expired"
            )
        if deadline is None and not candidates and h.time.monotonic() > launch_deadline:
            raise RuntimeError(
                "Gradle did not launch an owned managed emulator before the bounded discovery deadline"
            )
        if len(candidates) > 2:
            # GMD may overlap owned launches. None is device authority until the set settles.
            h.save(output / "active-process.json", custody)
            h.time.sleep(min(0.5, max(0.0, deadline - h.time.monotonic())))
            continue
        for binding in custody["targets"].values():
            current = candidates.get(binding["pid"])
            if (
                current is None
                or current["managedId"] != binding["managedId"]
                or current["avd"] != binding["avd"]
                or h.discovery_identity(binding["pid"]) != binding["identity"]
            ):
                raise RuntimeError(
                    "An admitted managed target changed before the exact owned pair was ready"
                )
        for pid, candidate in candidates.items():
            candidate = {**candidate, "pid": pid}
            listeners = h.command(
                "lsof",
                "-nP",
                "-a",
                "-p",
                str(pid),
                "-iTCP",
                "-sTCP:LISTEN",
                "-Fn",
                check=False,
            ).stdout.decode()
            port = h.console_port(listeners)
            if port is None:
                continue
            serial = f"emulator-{port}"
            if serial in custody["targets"]:
                continue
            receipt = h.observe_driver_receipt(adb, serial, nonce, candidate)
            if not isinstance(receipt, dict):
                continue
            if not h.TARGET["validate_driver_receipt"](
                receipt, nonce, receipt.get("pid")
            ):
                continue
            reported = (
                h.command(adb, "-s", serial, "emu", "avd", "name")
                .stdout.decode()
                .splitlines()[0]
            )
            if (
                reported != avd
                or h.command(
                    adb, "-s", serial, "shell", "getprop", "ro.kernel.qemu"
                ).stdout.strip()
                != b"1"
            ):
                raise RuntimeError(
                    "The managed endpoint does not match its QEMU process"
                )
            h.PREPARATION["require_boot_budget"](h, deadline)
            custody["targets"][serial] = {
                **candidate,
                "driverPid": receipt["pid"],
                "driverStartTicks": receipt["startTicks"],
                "role": receipt["role"],
                "shardIndex": receipt["shardIndex"],
            }
            h.TARGET["require_binding"](custody, serial)
        h.save(output / "active-process.json", custody)
        if len(custody["targets"]) < 2:
            h.time.sleep(0.5)
    targets = custody["targets"]
    if len(candidates) != 2 or {binding["pid"] for binding in targets.values()} != set(
        candidates
    ):
        raise RuntimeError(
            "An admitted managed target no longer belongs to the exact owned pair"
        )
    if {target["role"] for target in targets.values()} != {"source", "destination"} or {
        target["shardIndex"] for target in targets.values()
    } != {0, 1}:
        raise RuntimeError(
            "Expected distinct source/destination roles on both managed shards"
        )
    for serial in targets:
        h.TARGET["require_binding"](custody, serial)
    h.PREPARATION["require_boot_budget"](h, deadline)
