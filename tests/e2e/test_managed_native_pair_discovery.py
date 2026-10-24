"""Bounded settling must never turn an ambiguous owned set into device authority."""

from pathlib import Path
from types import SimpleNamespace
import runpy
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
ENTRY = runpy.run_path(
    str(Path(__file__).with_name("managed-native-restore-acceptance.py"))
)
PAIR = runpy.run_path(str(Path(__file__).with_name("managed-native-pair.py")))


def candidate(pid):
    return {"managedId": f"owned-{pid}_GradleManagedDevice", "avd": "managed-avd"}


class DiscoveryFixture:
    def __init__(
        self, snapshots, *, unready=(), drift=False, roles=None, nonce="nonce"
    ):
        self.snapshots = snapshots
        self.index = -1
        self.now = 0.0
        self.operations = []
        self.observed = set()
        self.custody = {"nonce": "nonce", "targets": {}, "gradle": {"pid": 9}}
        self.unready = unready
        self.drift = drift
        self.roles = roles or {101: ("source", 0), 202: ("destination", 1)}
        self.nonce = nonce
        self.process = SimpleNamespace(pid=9, poll=lambda: None)
        target = {
            "process_identity": lambda pid: {
                "birth": "gradle-birth",
                "command": "gradle nonce",
            },
            "managed_candidates": lambda lines, existing, avd: self.snapshots[
                self.index
            ],
            "validate_driver_receipt": ENTRY["TARGET"]["validate_driver_receipt"],
            "require_binding": self.binding,
        }
        self.h = SimpleNamespace(
            TARGET=target,
            live_managed_candidates=lambda selected, avd, **diagnostics: ENTRY[
                "live_managed_candidates"
            ](selected, avd, process_identity=self.current_identity),
            ManagedPairAmbiguousError=ENTRY["ManagedPairAmbiguousError"],
            PREPARATION=ENTRY["PREPARATION"],
            EMULATOR={
                "owned_managed_candidates": lambda directory, selected, nonce: selected
            },
            process_snapshot=self.snapshot,
            managed_boot_deadline=ENTRY["managed_boot_deadline"],
            time=SimpleNamespace(monotonic=lambda: self.now, sleep=self.sleep),
            discovery_identity=self.identity,
            command=self.command,
            console_port=ENTRY["console_port"],
            observe_driver_receipt=self.receipt,
            save=Mock(),
        )

    def snapshot(self):
        self.index = min(self.index + 1, len(self.snapshots) - 1)
        return ["snapshot"]

    def sleep(self, delay):
        self.now += delay

    def identity(self, pid):
        self.operations.append((self.now, "identity", pid))
        return self.current_identity(pid)

    def current_identity(self, pid):
        if (self.index, pid) in self.unready:
            return None
        return {
            "birth": "changed"
            if self.drift and self.index > 0 and pid == 101
            else f"birth-{pid}",
            "command": f"qemu-system-aarch64 @managed-avd -id owned-{pid}_GradleManagedDevice",
        }

    def command(self, *args, **kwargs):
        self.operations.append((self.now, "command", args))
        if args[0] == "lsof":
            pid = int(args[args.index("-p") + 1])
            port = {101: 5554, 202: 5556, 303: 5558}[pid]
            return SimpleNamespace(stdout=f"n127.0.0.1:{port}\n".encode())
        if args[-3:] == ("emu", "avd", "name"):
            return SimpleNamespace(stdout=b"managed-avd\n")
        return SimpleNamespace(stdout=b"1\n")

    def receipt(self, adb, serial, nonce, selected):
        pid = selected["pid"]
        self.operations.append((self.now, "receipt", pid))
        role, shard = self.roles.get(pid, ("destination", 1))
        return {
            "nonce": self.nonce,
            "phase": "ready",
            "pid": pid + 1000,
            "startTicks": str(pid),
            "role": role,
            "shardIndex": shard,
        }

    def binding(self, custody, serial):
        self.operations.append((self.now, "binding", serial))
        return custody["targets"][serial]

    def run(self, budget=2.0):
        PAIR["discover_managed_pair"](
            self.h,
            self.process,
            Path("/owned/output"),
            set(),
            "managed-avd",
            {"boot": budget},
            "adb",
            "nonce",
            self.custody,
            self.observed,
        )


class ManagedPairDiscoveryTest(unittest.TestCase):
    def test_three_owned_candidates_settle_before_any_probe_or_device_authority(self):
        f = DiscoveryFixture(
            [
                {p: candidate(p) for p in (101, 202, 303)},
                {p: candidate(p) for p in (101, 202)},
            ]
        )
        try:
            f.run()
        except RuntimeError as error:
            self.fail(f"Owned overlap must settle before probing: {error}")
        self.assertEqual({101, 202, 303}, f.observed)
        self.assertEqual({101, 202}, {b["pid"] for b in f.custody["targets"].values()})
        self.assertTrue(f.operations)
        self.assertTrue(all(at >= 0.5 for at, _, _ in f.operations))

    def test_persistent_ambiguity_expires_without_extending_the_first_qemu_deadline(
        self,
    ):
        f = DiscoveryFixture([{p: candidate(p) for p in (101, 202, 303)}])
        with self.assertRaisesRegex(RuntimeError, "boot observation expired"):
            f.run(budget=1.0)
        self.assertLessEqual(f.now, 1.5)
        self.assertEqual({101, 202, 303}, f.observed)
        self.assertEqual([], f.operations)
        self.assertEqual({}, f.custody["targets"])

    def test_candidate_first_seen_at_deadline_is_still_retained_for_cleanup(self):
        f = DiscoveryFixture(
            [
                {p: candidate(p) for p in (101, 202)},
                {p: candidate(p) for p in (101, 202, 303)},
            ],
            unready={(0, 101), (0, 202)},
        )
        with self.assertRaisesRegex(RuntimeError, "boot observation expired"):
            f.run(budget=0.5)
        self.assertEqual({101, 202, 303}, f.observed)

    def test_timeout_retains_latest_observation_without_admitting_it(self):
        f = DiscoveryFixture(
            [
                {p: candidate(p) for p in (101, 202, 303)},
                {p: candidate(p) for p in (101, 202)},
            ],
        )
        with self.assertRaisesRegex(RuntimeError, "boot observation expired"):
            f.run(budget=0.5)
        self.assertEqual(
            {
                p: {**candidate(p), "identity": f.current_identity(p)}
                for p in (101, 202)
            },
            f.custody.get("observedCandidates"),
        )
        self.assertEqual({101, 202, 303}, f.observed)
        self.assertEqual({}, f.custody["targets"])
        self.assertEqual([], f.operations)

    def test_persistent_ambiguity_remains_distinct_from_admitted_targets(self):
        selected = {p: candidate(p) for p in (101, 202, 303)}
        f = DiscoveryFixture([selected])
        with self.assertRaisesRegex(RuntimeError, "boot observation expired"):
            f.run(budget=0.5)
        self.assertEqual(
            {
                p: {**item, "identity": f.current_identity(p)}
                for p, item in selected.items()
            },
            f.custody.get("observedCandidates"),
        )
        self.assertEqual({}, f.custody["targets"])
        self.assertEqual([], f.operations)

    def test_stale_admitted_target_is_never_carried_into_a_replacement_pair(self):
        f = DiscoveryFixture(
            [
                {p: candidate(p) for p in (101, 202)},
                {p: candidate(p) for p in (202, 303)},
            ],
            unready={(0, 202)},
        )
        with self.assertRaisesRegex(RuntimeError, "admitted managed target"):
            f.run()
        self.assertEqual({101}, {b["pid"] for b in f.custody["targets"].values()})
        self.assertFalse(
            any(
                at > 0 and operation in ("command", "receipt", "binding")
                for at, operation, _ in f.operations
            )
        )
        self.assertEqual({101, 202, 303}, f.observed)

    def test_admitted_process_birth_drift_cannot_be_silently_rebound(self):
        f = DiscoveryFixture(
            [
                {p: candidate(p) for p in (101, 202)},
                {p: candidate(p) for p in (101, 202)},
            ],
            unready={(0, 202)},
            drift=True,
        )
        with self.assertRaisesRegex(RuntimeError, "admitted managed target"):
            f.run()
        self.assertEqual(
            "birth-101", next(iter(f.custody["targets"].values()))["identity"]["birth"]
        )
        self.assertFalse(
            any(
                at > 0 and operation in ("command", "receipt", "binding")
                for at, operation, _ in f.operations
            )
        )

    def test_overlap_after_one_admission_keeps_that_exact_target_and_all_cleanup_pids(
        self,
    ):
        f = DiscoveryFixture(
            [
                {p: candidate(p) for p in (101, 202)},
                {p: candidate(p) for p in (101, 202, 303)},
                {p: candidate(p) for p in (101, 202)},
            ],
            unready={(0, 202)},
        )
        try:
            f.run()
        except RuntimeError as error:
            self.fail(f"The same admitted pair must survive bounded overlap: {error}")
        self.assertEqual({101, 202, 303}, f.observed)
        self.assertFalse(any(at == 0.5 for at, _, _ in f.operations))
        self.assertEqual(
            "birth-101", f.custody["targets"]["emulator-5554"]["identity"]["birth"]
        )

    def test_roles_and_shards_remain_distinct_before_pair_admission(self):
        for roles in (
            {101: ("source", 0), 202: ("source", 1)},
            {101: ("source", 0), 202: ("destination", 0)},
        ):
            with self.subTest(roles=roles):
                f = DiscoveryFixture(
                    [{p: candidate(p) for p in (101, 202)}], roles=roles
                )
                with self.assertRaisesRegex(
                    RuntimeError, "distinct source/destination roles"
                ):
                    f.run()

    def test_wrong_driver_nonce_never_admits_a_target(self):
        f = DiscoveryFixture([{p: candidate(p) for p in (101, 202)}], nonce="foreign")
        with self.assertRaisesRegex(RuntimeError, "boot observation expired"):
            f.run(budget=0.5)
        self.assertEqual({}, f.custody["targets"])
        self.assertFalse(
            any(operation == "binding" for _, operation, _ in f.operations)
        )

    def test_stopping_gradle_while_ambiguous_does_not_probe_or_start_a_scenario(self):
        f = DiscoveryFixture([{p: candidate(p) for p in (101, 202, 303)}])
        f.process.poll = lambda: 0 if f.now else None
        with self.assertRaisesRegex(RuntimeError, "stopped before both drivers"):
            f.run()
        self.assertEqual([], f.operations)
        self.assertEqual({101, 202, 303}, f.observed)

    def test_preparation_boot_deadline_cannot_restart_for_the_driver_phase(self):
        f = DiscoveryFixture([{p: candidate(p) for p in (101, 202, 303)}])
        f.custody["bootDeadline"] = 0.25
        with self.assertRaisesRegex(RuntimeError, "boot observation expired"):
            f.run(budget=5)
        self.assertLessEqual(f.now, 0.25)
        self.assertEqual({}, f.custody["targets"])

    def test_a_late_second_receipt_cannot_admit_a_target_after_the_deadline(self):
        f = DiscoveryFixture([{p: candidate(p) for p in (101, 202)}])
        f.custody["bootDeadline"] = 0.25
        original = f.receipt

        def delayed(*args):
            result = original(*args)
            if args[-1]["pid"] == 202:
                f.now = 1
            return result

        f.h.observe_driver_receipt = delayed
        with self.assertRaisesRegex(RuntimeError, "boot"):
            f.run(budget=5)
        self.assertNotIn("emulator-5556", f.custody["targets"])

    def test_final_binding_checks_cannot_finish_after_the_deadline(self):
        f = DiscoveryFixture([{p: candidate(p) for p in (101, 202)}])
        f.custody["bootDeadline"] = 0.25
        original = f.binding

        def delayed(custody, serial):
            result = original(custody, serial)
            if len(custody["targets"]) == 2:
                f.now = 1
            return result

        f.h.TARGET["require_binding"] = delayed
        with self.assertRaisesRegex(RuntimeError, "boot"):
            f.run(budget=5)

    def test_root_api_keeps_live_caller_overrides_after_extraction(self):
        function = ENTRY["run_managed_pair"]
        namespace = function.__globals__
        original = namespace["validate_native_arguments"]
        try:
            namespace["validate_native_arguments"] = Mock(
                side_effect=RuntimeError("caller override")
            )
            with self.assertRaisesRegex(RuntimeError, "caller override"):
                function(SimpleNamespace(native_arguments=[]))
            namespace["validate_native_arguments"].assert_called_once_with([])
        finally:
            namespace["validate_native_arguments"] = original


if __name__ == "__main__":
    unittest.main()
