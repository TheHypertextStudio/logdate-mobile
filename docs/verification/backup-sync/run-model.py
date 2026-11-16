#!/usr/bin/env python3
"""Check the six fixed configurations and require the five historical counterexamples."""

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
from datetime import datetime, timezone

parser = argparse.ArgumentParser()
parser.add_argument(
    "--jar",
    type=pathlib.Path,
    required=True,
    help="Existing official tla2tools.jar; never downloaded by this script",
)
parser.add_argument("--output", type=pathlib.Path, required=True)
args = parser.parse_args()
jar = args.jar.resolve(strict=True)
root = pathlib.Path(__file__).resolve().parent
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
# Released TLC writes a trace specification only on request; newer builds write one unless told not to.
tlc_help = subprocess.run(
    ["java", "-cp", str(jar), "tlc2.TLC", "-h"],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    timeout=60,
).stdout
trace_spec_flags = ["-noGenerateSpecTE"] if "-noGenerateSpecTE" in tlc_help else []
cases = [
    ("BackupSync", None),
    ("Cellular", None),
    ("BuggyEcho", "NoAcknowledgedPayload"),
    ("BuggyNotice", "TruthfulNotification"),
    ("BuggyLostAck", "NoCommittedPayloadReplay"),
    ("FixedQuiet", None),
    ("FixedEligibility", None),
    ("FixedCachedOriginal", None),
    ("RequiredForeground", None),
    ("BuggyEligible", "NoDeferredLiveClaim"),
    ("BuggyQuietCheck", "NoUnnecessaryCheckNotice"),
]
results = []
for case, expected in cases:
    cfg = root / (case + ".cfg")
    # TLC 2.19's default fingerprint set divides by zero while reporting collision odds for this model.
    command = [
        "java",
        "-Xmx1400m",
        "-Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet",
        "-cp",
        str(jar),
        "tlc2.TLC",
        "-workers",
        "2",
        *trace_spec_flags,
        "-metadir",
        str(out / (case + "-states")),
        "-config",
        str(cfg),
        str(root / "BackupSync.tla"),
    ]
    completed = subprocess.run(
        command,
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=180,
    )
    log = completed.stdout
    (out / (case + ".log")).write_text(log)
    if expected:
        passed = (
            completed.returncode != 0
            and ("Invariant " + expected + " is violated") in log
        )
    else:
        passed = (
            completed.returncode == 0
            and "Model checking completed. No error has been found." in log
        )
    counts = re.findall(
        r"([\d,]+) states generated, ([\d,]+) distinct states found", log
    )
    row = {
        "case": case,
        "passed": passed,
        "returnCode": completed.returncode,
        "expectedCounterexample": expected,
        "configSha256": hashlib.sha256(cfg.read_bytes()).hexdigest(),
    }
    if counts:
        row.update(
            generatedStates=int(counts[-1][0].replace(",", "")),
            distinctStates=int(counts[-1][1].replace(",", "")),
        )
    results.append(row)
    print(json.dumps(row), flush=True)
    if not passed:
        print(log[-4000:], flush=True)
        break
summary = {
    "checkedAtUtc": datetime.now(timezone.utc).isoformat(),
    "specSha256": hashlib.sha256((root / "BackupSync.tla").read_bytes()).hexdigest(),
    "toolJarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
    "bounds": {
        "contentItems": 1,
        "contentGenerations": 2,
        "workerIdentities": 2,
        "networkStates": ["offline", "cellular", "wifi"],
        "chargingStates": 2,
        "authenticationKeyServerFlags": 3,
        "snapshotProgressTotal": 1,
    },
    "cases": results,
    "passed": len(results) == len(cases) and all(r["passed"] for r in results),
}
(out / "results.json").write_text(json.dumps(summary, indent=2) + "\n")
raise SystemExit(0 if summary["passed"] else 1)
