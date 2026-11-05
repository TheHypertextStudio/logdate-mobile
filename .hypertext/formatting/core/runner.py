"""Execute repository checks and enforce release evidence without an agent."""

import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import threading
import queue
import sys
from . import gate


def configuration(profile, rules):
    checks = profile.get("verification")
    allowed = {rule["id"] for rule in rules if "automated" in rule["evidence_kinds"]}
    if not isinstance(checks, list) or not checks:
        raise ValueError("Profile needs a nonempty verification command list")
    seen = set()
    for entry in checks:
        if not isinstance(entry, dict) or set(entry) != {
            "id",
            "argv",
            "timeout_seconds",
        }:
            raise ValueError("Each command needs exactly id, argv, timeout_seconds")
        identifier, argv = entry["id"], entry["argv"]
        if (
            not isinstance(identifier, str)
            or identifier not in allowed
            or identifier in seen
        ):
            raise ValueError(
                "Unknown, inapplicable, non-automated, or duplicate command ID"
            )
        if (
            not isinstance(argv, list)
            or not argv
            or any(not isinstance(arg, str) or not arg or "\x00" in arg for arg in argv)
        ):
            raise ValueError("argv must be a nonempty list of nonempty strings")
        timeout = entry["timeout_seconds"]
        if type(timeout) is not int or not 1 <= timeout <= 3600:
            raise ValueError(
                "Command timeout must be an integer from 1 to 3600 seconds"
            )
        seen.add(identifier)
    if "execution.verification" not in seen:
        raise ValueError(
            "execution.verification must execute; no asserted-pass substitute"
        )
    return checks


def watch_windows_parent(pid):
    """Stop an isolated CLI if its waiting Windows wrapper is terminated."""
    import ctypes as c
    from ctypes import wintypes as w

    if pid != os.getppid():
        raise ValueError("Windows wrapper parent changed before setup")
    kernel = c.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    kernel.OpenProcess.restype = w.HANDLE
    kernel.GetCurrentProcess.restype = w.HANDLE
    kernel.GetProcessTimes.argtypes = [w.HANDLE, *([c.c_void_p] * 4)]
    kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    parent = kernel.OpenProcess(
        0x101000, False, pid
    )  # SYNCHRONIZE | QUERY_LIMITED_INFORMATION
    if not parent:
        raise c.WinError(c.get_last_error())
    try:
        times = []
        for handle in (parent, kernel.GetCurrentProcess()):
            values = (c.c_uint64 * 4)()
            if not kernel.GetProcessTimes(
                handle, *[c.byref(values, i * 8) for i in range(4)]
            ):
                raise c.WinError(c.get_last_error())
            times.append(values[0])
        if times[0] > times[1] or kernel.WaitForSingleObject(parent, 0) != 258:
            raise ValueError("Windows wrapper exited before setup; refusing PID reuse")
    except BaseException:
        kernel.CloseHandle(parent)
        raise

    def watch():
        kernel.WaitForSingleObject(parent, 0xFFFFFFFF)
        # OS closure of our job handles kills command descendants even on hard exit.
        os._exit(130)

    threading.Thread(target=watch, daemon=True).start()


def windows_job():
    """An unnamed, non-breakaway job; closing its last handle kills descendants."""
    import ctypes as c
    from ctypes import wintypes as w

    kernel = c.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [c.c_void_p, w.LPCWSTR]
    kernel.CreateJobObjectW.restype = w.HANDLE
    kernel.SetInformationJobObject.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD]
    kernel.CloseHandle.argtypes = [w.HANDLE]

    class Basic(c.Structure):
        _fields_ = [
            ("times", c.c_int64 * 2),
            ("flags", w.DWORD),
            ("minimum", c.c_size_t),
            ("maximum", c.c_size_t),
            ("active", w.DWORD),
            ("affinity", c.c_size_t),
            ("priority", w.DWORD),
            ("scheduling", w.DWORD),
        ]

    class Extended(c.Structure):
        _fields_ = [
            ("basic", Basic),
            ("io", c.c_uint64 * 6),
            ("limits", c.c_size_t * 4),
        ]

    job = kernel.CreateJobObjectW(None, None)
    if not job:
        raise c.WinError(c.get_last_error())
    info = Extended()
    info.basic.flags = 0x2000  # KILL_ON_JOB_CLOSE, no breakaway
    if not kernel.SetInformationJobObject(job, 9, c.byref(info), c.sizeof(info)):
        kernel.CloseHandle(job)
        raise c.WinError(c.get_last_error())
    return job, kernel


# The trusted isolated launcher joins the job BEFORE spawning any project code.
# Unlike assigning Popen's child afterward, this leaves no descendant-escape race.
WINDOWS_LAUNCHER = """
import ctypes as c, json, subprocess, sys
from ctypes import wintypes as w
k=c.WinDLL('kernel32',use_last_error=True)
k.GetCurrentProcess.restype=w.HANDLE
k.AssignProcessToJobObject.argtypes=[w.HANDLE,w.HANDLE]
k.CloseHandle.argtypes=[w.HANDLE]
h=int(sys.argv[1])
if not k.AssignProcessToJobObject(h,k.GetCurrentProcess()):
    raise c.WinError(c.get_last_error())
k.CloseHandle(h)
sys.exit(subprocess.call(json.loads(sys.argv[2]), shell=False, close_fds=True))
"""


def windows_executable(name, repo, environment):
    """Resolve explicit local commands against the project; never search CWD implicitly."""
    values = {key.upper(): value for key, value in environment.items()}
    path = Path(name)
    if path.is_absolute() or path.parent != Path(".") or "/" in name or "\\" in name:
        candidates = [path if path.is_absolute() else Path(repo) / path]
    else:
        suffixes = [""] if path.suffix else [".exe", ".com"]
        candidates = [
            Path(directory) / (name + suffix)
            for directory in values.get("PATH", "").split(os.pathsep)
            if directory and Path(directory).is_absolute()
            for suffix in suffixes
        ]
    for candidate in candidates:
        if candidate.suffix.lower() in {".exe", ".com"} and candidate.is_file():
            return str(candidate.absolute())
    raise FileNotFoundError(
        "Windows native executable unavailable; scripts require an explicit trusted interpreter"
    )


def stop_windows_job(job, kernel):
    """Terminate explicitly, then prove the entire job drained before returning success."""
    import ctypes as c
    from ctypes import wintypes as w

    kernel.TerminateJobObject.argtypes = [w.HANDLE, w.UINT]
    kernel.QueryInformationJobObject.argtypes = [
        w.HANDLE,
        c.c_int,
        c.c_void_p,
        w.DWORD,
        c.c_void_p,
    ]

    class Accounting(c.Structure):
        _fields_ = [
            ("times", c.c_int64 * 4),
            ("faults", w.DWORD),
            ("total", w.DWORD),
            ("active", w.DWORD),
            ("terminated", w.DWORD),
        ]

    try:
        if not kernel.TerminateJobObject(job, 130):
            raise c.WinError(c.get_last_error())
        deadline = time.monotonic() + 2
        while True:
            info = Accounting()
            if not kernel.QueryInformationJobObject(
                job, 1, c.byref(info), c.sizeof(info), None
            ):
                raise c.WinError(c.get_last_error())
            if info.active == 0:
                return
            if time.monotonic() >= deadline:
                raise OSError(
                    "Windows command descendants did not stop within cleanup budget"
                )
            time.sleep(0.01)
    finally:
        if not kernel.CloseHandle(job):
            raise c.WinError(c.get_last_error())


def execute(
    argv,
    repo,
    stream,
    timeout,
    stdin=None,
    stop_event=None,
    on_start=None,
    environment=None,
    stderr=subprocess.STDOUT,
):
    if stop_event is not None and stop_event.is_set():
        raise InterruptedError("Command stopped before execution")
    deadline = time.monotonic() + timeout
    child_environment = dict(
        os.environ if environment is None else environment, PYTHONDONTWRITEBYTECODE="1"
    )
    job, kernel, options = None, None, {}
    command = argv
    if os.name == "nt":
        if Path(argv[0]).suffix.lower() in {".cmd", ".bat", ".ps1"}:
            raise ValueError(
                "Windows commands require a native executable; use an explicit trusted interpreter for scripts"
            )
        executable = windows_executable(argv[0], repo, child_environment)
        job, kernel = windows_job()
        command = [
            sys.executable,
            "-I",
            "-S",
            "-B",
            "-c",
            WINDOWS_LAUNCHER,
            str(job),
            json.dumps([executable, *argv[1:]]),
        ]
        startup = subprocess.STARTUPINFO()
        startup.lpAttributeList = {"handle_list": [job]}
        options["startupinfo"] = startup
    try:
        if job is not None:
            os.set_handle_inheritable(job, True)
        process = subprocess.Popen(
            command,
            cwd=repo,
            stdin=stdin,
            stdout=subprocess.PIPE,
            stderr=stderr,
            shell=False,
            start_new_session=os.name == "posix",
            **options,
            env=child_environment,
        )
    except BaseException:
        if job is not None:
            kernel.CloseHandle(job)
        raise
    finally:
        if job is not None and kernel is not None:
            # Popen has copied the handle; project descendants must never inherit it.
            try:
                os.set_handle_inheritable(job, False)
            except OSError:
                pass
    reader, old_handler = None, None
    stopped = threading.Event()
    try:
        chunks = queue.Queue(maxsize=2)
        stopped = threading.Event()

        def collect():
            try:
                while not stopped.is_set():
                    chunk = process.stdout.read1(65536)
                    while not stopped.is_set():
                        try:
                            chunks.put(chunk, timeout=0.05)
                            break
                        except queue.Full:
                            continue
                    if not chunk:
                        break
            finally:
                process.stdout.close()

        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        if threading.current_thread() is threading.main_thread():
            old_handler = signal.getsignal(signal.SIGTERM)

            def cancelled(signum, frame):
                raise InterruptedError("Verification cancelled")

            signal.signal(signal.SIGTERM, cancelled)
        if on_start is not None:
            on_start(process)
        size = 0
        while True:
            if stop_event is not None and stop_event.is_set():
                raise InterruptedError("Command stopped")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(argv, timeout)
            try:
                chunk = chunks.get(timeout=min(0.05, remaining))
            except queue.Empty:
                continue
            if not chunk:
                return process.wait(timeout=max(0.01, deadline - time.monotonic()))
            size += len(chunk)
            if size > 16 * 1024 * 1024:
                raise OSError("Verification log exceeds 16 MiB")
            stream.write(chunk)
    finally:
        stopped.set()
        tree_stopped = False
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
                tree_stopped = True
            elif job is not None:
                try:
                    stop_windows_job(job, kernel)
                    tree_stopped = True
                finally:
                    job = None
            elif process.poll() is None:
                process.kill()
        except ProcessLookupError:
            tree_stopped = True
        finally:
            # A launcher cancelled before joining the job is still our direct child.
            if process.poll() is None:
                process.kill()
            process.wait(timeout=2)
            if reader is not None and reader.ident is not None:
                reader.join(timeout=2)
            if process.stdout is not None and (reader is None or not reader.is_alive()):
                process.stdout.close()
            if old_handler is not None:
                signal.signal(signal.SIGTERM, old_handler)
            if reader is not None and reader.is_alive():
                raise OSError("Command output reader did not stop")
            process.hypertext_tree_stopped = tree_stopped


def run(
    repo,
    profile_path,
    output,
    supplemental=None,
    trusted_profile=None,
    acceptance_path=None,
    shipping=None,
    target=None,
    approved_profile_sha256=None,
    signature=None,
    allowed_signers=None,
):
    repo, output = repo.resolve(), output.absolute()
    if output.resolve().is_relative_to(repo):
        raise ValueError("Collect fresh evidence outside the target checkout")
    accepted = None
    if supplemental is not None:
        if trusted_profile is None:
            raise ValueError("Release requires a trusted profile")
        accepted = gate.acceptance(
            acceptance_path,
            repo,
            supplemental,
            shipping,
            target,
            signature,
            allowed_signers,
        )
    policy = gate.read(gate.ROOT / "policy/key-standards.json")
    profile = gate.read(profile_path)
    rules = gate.required(profile, policy)
    commands = configuration(profile, rules)
    if trusted_profile is not None and trusted_profile.resolve().is_relative_to(repo):
        raise ValueError(
            "Trusted profile must come from outside the candidate checkout"
        )
    if trusted_profile is not None and approved_profile_sha256 != gate.digest(
        profile_path
    ):
        trusted = gate.read(trusted_profile)
        gate.required(trusted, policy)
        if any(
            value and not profile["capabilities"][key]
            for key, value in trusted["capabilities"].items()
        ):
            raise ValueError(
                "Product capability downgrade requires owner authorization"
            )
        current = {entry["id"]: entry for entry in commands}
        for previous in configuration(trusted, gate.required(trusted, policy)):
            if current.get(previous["id"]) != previous:
                raise ValueError(
                    "Removing or replacing a trusted check requires owner authorization"
                )
    from . import repository

    workspace_failures = []
    if repository.javascript_workspace(repo):
        repository.workspace_checks(
            repo,
            repo,
            lambda identifier, ok, problem: (
                workspace_failures.append(identifier + ": " + problem)
                if not ok
                else None
            ),
        )
    if workspace_failures:
        raise ValueError(
            "Studio workspace conformance blocks verification: "
            + "; ".join(workspace_failures)
        )
    subject = gate.context(repo, profile_path)
    if accepted is not None and accepted["subject"] != subject:
        raise ValueError("Acceptance has a stale subject")
    from . import setup_files

    store = setup_files.Output(output)
    rows, failures = [], []
    try:
        for index, entry in enumerate(commands):
            log = output / f"command-{index}.log"
            started, code, error = time.monotonic(), None, None
            with store.log(log.name) as stream:
                try:
                    code = execute(
                        entry["argv"], repo, stream, entry["timeout_seconds"]
                    )
                except (OSError, subprocess.TimeoutExpired) as exception:
                    error = type(exception).__name__
            report = {
                "subject": subject,
                "argv": entry["argv"],
                "exit_code": code,
                "error": error,
                "duration_ms": round((time.monotonic() - started) * 1000),
                "log": log.name,
                "log_sha256": gate.digest(log),
            }
            path = output / f"command-{index}.json"
            store.save(report, path.name)
            passed = code == 0 and error is None
            rows.append(
                {
                    "id": entry["id"],
                    "status": "pass" if passed else "fail",
                    "kind": "automated",
                    "summary": f"Executed command; exit_code={code}; error={error}",
                    "artifact": path.name,
                    "sha256": gate.digest(path),
                }
            )
            if not passed:
                failures.append(
                    f"{entry['id']}: command failed (exit={code}, error={error}); see {path}"
                )
                break
        try:
            if gate.context(repo, profile_path) != subject:
                failures.append("Checkout or baseline changed during verification")
        except (ValueError, subprocess.CalledProcessError):
            failures.append("Verification modified the checkout or profile")
        evidence = {"schema_version": 1, "subject": subject, "checks": rows}
        evidence_path = output / "evidence.json"
        store.save(evidence, evidence_path.name)
        for index in range(len(rows)):
            try:
                gate.artifact(rows[index], output)
            except ValueError:
                failures.append("Command report was modified after collection")
            report = gate.read(output / f"command-{index}.json")
            if report["log_sha256"] != gate.digest(output / report["log"]):
                failures.append("Command evidence was modified after collection")
        store.verify()
    except (OSError, ValueError) as error:
        try:
            store.quarantine("evidence.json")
        except (OSError, ValueError):
            pass
        return ["Verification output integrity failed: " + str(error)]
    finally:
        store.close()
    if failures:
        return failures
    if supplemental is not None:
        if (
            gate.acceptance(
                acceptance_path,
                repo,
                supplemental,
                shipping,
                target,
                signature,
                allowed_signers,
            )
            != accepted
        ):
            raise ValueError("Acceptance changed during verification")
        additional = gate.read(supplemental)
        if (
            additional.get("schema_version") != 1
            or additional.get("subject") != subject
        ):
            raise ValueError("Supplemental evidence has a different or stale subject")
        supplied = additional.get("checks")
        if not isinstance(supplied, list):
            raise ValueError("Supplemental checks must be a list")
        repacked_approvals = {}
        actual_ids, seen = {entry["id"] for entry in commands}, set()
        for index, record in enumerate(supplied):
            if (
                not isinstance(record, dict)
                or not isinstance(record.get("id"), str)
                or record["id"] in seen
            ):
                raise ValueError("Invalid or duplicate supplemental ID")
            seen.add(record["id"])
            if record["id"] in actual_ids:
                raise ValueError(
                    "Supplemental records cannot replace freshly executed checks"
                )
            original = gate.artifact(record, supplemental.parent)
            copied = dict(record, artifact="supplemental/" + record["artifact"])
            if record.get("status") == "exception" and accepted[
                "approved_exceptions"
            ].get(record["id"]) == gate.record_digest(record):
                repacked_approvals[record["id"]] = gate.record_digest(copied)
            evidence["checks"].append(copied)
        for name, sha in accepted["files"].items():
            original = gate.artifact(
                {"artifact": name, "sha256": sha}, supplemental.parent
            )
            destination = output / "supplemental" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, destination)
            if gate.digest(destination) != sha:
                raise ValueError("Supplemental dependency changed during collection")
        evidence_path.write_text(
            json.dumps(evidence, indent=2) + "\n", encoding="utf-8"
        )
        return gate.check(repo, profile_path, evidence_path, policy, repacked_approvals)
    return []


def main(argv=None, command=None):
    from .cli import Parser

    parser = Parser(
        prog="hypertext " + command if command else None, description=__doc__
    )
    if command is None:
        parser.add_argument("command", choices=("verify", "release"))
    else:
        parser.set_defaults(command=command)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument(
        "--trusted-profile",
        type=Path,
        help="Profile from the protected baseline; expansions allowed, weakening blocked",
    )
    parser.add_argument(
        "--approved-profile-sha256",
        help="Owner-controlled external authorization for one exact profile change",
    )
    parser.add_argument(
        "--acceptance-signature",
        type=Path,
        help="SSH signature required for exceptions",
    )
    parser.add_argument(
        "--allowed-signers", type=Path, help="External owner public-key allowlist"
    )
    parser.add_argument(
        "--acceptance",
        type=Path,
        help="External protected-job or owner acceptance manifest",
    )
    parser.add_argument("--artifact", type=Path, help="Exact shipping artifact")
    parser.add_argument(
        "--target", help="Exact environment and provider-configuration identity"
    )
    parser.add_argument(
        "--json", action="store_true", help="Structured gate diagnostics"
    )
    args = parser.parse_args(argv)
    try:
        if args.command == "release" and args.evidence is None:
            raise ValueError(
                "release requires --evidence; device/provider/design checks cannot be skipped"
            )
        if args.command == "verify" and any(
            value is not None
            for value in (
                args.evidence,
                args.acceptance,
                args.artifact,
                args.target,
                args.acceptance_signature,
                args.allowed_signers,
            )
        ):
            raise ValueError(
                "Use release for supplemental evidence and release acceptance inputs"
            )
        failures = run(
            args.repo,
            args.repo / ".hypertext/product.json",
            args.output,
            args.evidence.resolve() if args.evidence else None,
            args.trusted_profile.resolve() if args.trusted_profile else None,
            args.acceptance.resolve() if args.acceptance else None,
            args.artifact.resolve() if args.artifact else None,
            args.target,
            args.approved_profile_sha256,
            args.acceptance_signature,
            args.allowed_signers,
        )
        if failures:
            raise SystemExit(
                gate.report(
                    failures,
                    gate.read(gate.ROOT / "policy/key-standards.json"),
                    args.output,
                    args.json,
                    scope=args.command,
                )
            )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(
            gate.report(
                [str(error)],
                evidence=args.output,
                json_output=args.json,
                scope=args.command,
            )
        )
    if args.json:
        gate.report([], evidence=args.output, json_output=True, scope=args.command)
    else:
        print(
            "Executed checks passed."
            if args.command == "verify"
            else "Executed checks and release evidence validation passed; non-automated outcomes still require inspection."
        )
