#!/usr/bin/env python3
"""Read-only release evidence gate. Never executes commands from a target repo."""

from datetime import date, datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def git_bytes(repo, *args):
    from . import setup_files

    code, output = setup_files.git_read(repo, *args)
    if code:
        raise subprocess.CalledProcessError(code, ["trusted git", *args], output=output)
    return output


def git(repo, *args):
    return git_bytes(repo, *args).decode("utf-8").strip()


def read(path):
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("JSON exceeds the 8 MiB input limit")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    value = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=unique,
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError("Non-finite JSON number")
        ),
    )
    if not isinstance(value, dict):
        raise ValueError(f"Expected an object: {path.name}")
    return value


def context(repo, profile):
    if Path(git(repo, "rev-parse", "--show-toplevel")).resolve() != repo.resolve():
        raise ValueError("--repo must name the checkout root")
    flags = git(repo, "ls-files", "-v", "-z").split("\0")
    if any(row and (row[0].islower() or row[0] == "S") for row in flags):
        raise ValueError(
            "Hidden index flags (assume-unchanged/skip-worktree) prevent exact verification"
        )
    if git(
        repo,
        "-c",
        "core.fsmonitor=false",
        "-c",
        "core.untrackedCache=false",
        "status",
        "--porcelain",
        "--untracked-files=all",
        "--ignore-submodules=none",
    ):
        raise ValueError("Release checkout has uncommitted or untracked changes")
    if not profile.resolve().is_relative_to(repo.resolve()):
        raise ValueError("Release profile must be inside the tested checkout")
    relative = profile.resolve().relative_to(repo.resolve()).as_posix()
    git(repo, "ls-files", "--error-unmatch", "--", relative)
    committed = git_bytes(repo, "show", f"HEAD:{relative}")
    if hashlib.sha256(committed).hexdigest() != digest(profile):
        raise ValueError("Release profile must match the tested commit")
    return {
        "commit": git(repo, "rev-parse", "HEAD"),
        "engine_sha256": hashlib.sha256(
            "".join(
                f"{name}:{digest(ROOT / 'core' / name)}\n"
                for name in (
                    "__init__.py",
                    "gate.py",
                    "runner.py",
                    "bootstrap.py",
                    "setup_files.py",
                    "setup.py",
                    "repository.py",
                    "doctor.py",
                    "install.py",
                    "cli.py",
                    "terminal.py",
                )
            ).encode()
        ).hexdigest(),
        "standards_sha256": digest(ROOT / "docs/product-standards.md"),
        "android_sources_sha256": digest(ROOT / "policy/android-sources/manifest.json"),
        "policy_sha256": digest(ROOT / "policy/key-standards.json"),
        "profile_sha256": digest(profile),
    }


def required(profile, policy):
    if (
        set(policy) != {"version", "capabilities", "rules"}
        or type(policy["version"]) is not int
        or policy["version"] != 1
    ):
        raise ValueError("Invalid policy version or fields")
    names, rules = policy["capabilities"], policy["rules"]
    if (
        not isinstance(names, list)
        or not names
        or any(not isinstance(n, str) for n in names)
        or len(set(names)) != len(names)
    ):
        raise ValueError("Invalid policy capabilities")
    if not isinstance(rules, list) or not rules:
        raise ValueError("Policy needs rules")
    seen = set()
    for rule in rules:
        if not isinstance(rule, dict) or set(rule) != {
            "id",
            "when",
            "evidence_kinds",
            "source",
            "exception_allowed",
        }:
            raise ValueError("Invalid policy rule fields")
        identifier = rule["id"]
        if not isinstance(identifier, str) or not identifier or identifier in seen:
            raise ValueError("Invalid or duplicate policy ID")
        seen.add(identifier)
        if not isinstance(rule["when"], list) or any(
            n not in names for n in rule["when"]
        ):
            raise ValueError("Unknown policy condition")
        if (
            not isinstance(rule["evidence_kinds"], list)
            or not rule["evidence_kinds"]
            or any(
                k not in {"automated", "observed", "device", "provider"}
                for k in rule["evidence_kinds"]
            )
        ):
            raise ValueError("Invalid policy evidence kinds")
        if (
            type(rule["exception_allowed"]) is not bool
            or not isinstance(rule["source"], str)
            or not rule["source"]
        ):
            raise ValueError("Invalid policy applicability")
    if set(profile) - {"schema_version", "product", "capabilities", "verification"}:
        raise ValueError("Unknown profile fields")
    if (
        type(profile.get("schema_version")) is not int
        or profile["schema_version"] != 1
        or not isinstance(profile.get("product"), str)
        or not profile["product"].strip()
    ):
        raise ValueError("Profile needs schema_version 1 and a nonempty product name")
    capabilities = profile.get("capabilities")
    if not isinstance(capabilities, dict) or set(capabilities) != set(
        policy["capabilities"]
    ):
        raise ValueError(
            "Profile must explicitly declare every supported capability; no unknown keys"
        )
    if any(type(value) is not bool for value in capabilities.values()):
        raise ValueError("Capabilities must be booleans")
    if capabilities["android"] and not capabilities["interface"]:
        raise ValueError("An Android app must declare its interface capability")
    return [
        rule
        for rule in policy["rules"]
        if not rule["when"] or any(capabilities[key] for key in rule["when"])
    ]


def artifact(record, directory):
    location = record.get("artifact")
    if not isinstance(location, str) or not location:
        raise ValueError("Missing evidence artifact")
    relative = Path(location)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Evidence paths must be relative and cannot traverse parents")
    unresolved = directory / relative
    if any(
        directory.joinpath(*relative.parts[:index]).is_symlink()
        for index in range(1, len(relative.parts) + 1)
    ):
        raise ValueError("Evidence cannot use symlinks")
    path = unresolved.resolve()
    if not path.is_relative_to(directory.resolve()) or not path.is_file():
        raise ValueError("Artifact must be a file inside the evidence directory")
    if path.stat().st_size > 256 * 1024 * 1024:
        raise ValueError("Evidence artifact exceeds 256 MiB")
    if path.stat().st_size == 0 or record.get("sha256") != digest(path):
        raise ValueError(
            "Evidence artifact is empty, changed, or has no matching SHA-256"
        )
    return path


def acceptance(
    path, repo, supplemental, shipping, target, signature=None, allowed_signers=None
):
    if path is None or shipping is None or not target:
        raise ValueError(
            "Release requires external --acceptance, --artifact and --target"
        )
    if path.resolve().is_relative_to(repo.resolve()) or path.resolve().is_relative_to(
        supplemental.parent.resolve()
    ):
        raise ValueError(
            "Acceptance must come from outside the candidate and evidence directories"
        )
    value = read(path)
    if (
        set(value)
        != {
            "schema_version",
            "subject",
            "target",
            "artifact_sha256",
            "evidence_sha256",
            "files",
            "observed_at",
            "expires_at",
            "approved_exceptions",
        }
        or type(value["schema_version"]) is not int
        or value["schema_version"] != 1
    ):
        raise ValueError("Invalid acceptance contract")
    now = datetime.now(timezone.utc)
    observed, expires = (
        datetime.fromisoformat(value[key]) for key in ("observed_at", "expires_at")
    )
    if (
        observed.tzinfo is None
        or expires.tzinfo is None
        or not observed <= now < expires
        or expires - observed > timedelta(days=7)
    ):
        raise ValueError("Acceptance is stale, future-dated, or exceeds seven days")
    if (
        value["target"] != target
        or value["artifact_sha256"] != digest(shipping)
        or value["evidence_sha256"] != digest(supplemental)
    ):
        raise ValueError(
            "Acceptance does not match target, shipping artifact, or evidence"
        )
    if not isinstance(value["approved_exceptions"], dict) or not isinstance(
        value["files"], dict
    ):
        raise ValueError("Invalid acceptance files or exception approvals")
    if value["approved_exceptions"]:
        if (
            signature is None
            or allowed_signers is None
            or allowed_signers.resolve().is_relative_to(repo.resolve())
            or allowed_signers.resolve().is_relative_to(supplemental.parent.resolve())
        ):
            raise ValueError(
                "Exceptions require an owner-signed acceptance and externally pinned allowed signers"
            )
        from . import setup_files

        verified = subprocess.run(
            [
                setup_files.trusted_tool(repo, "ssh-keygen"),
                "-Y",
                "verify",
                "-f",
                str(allowed_signers),
                "-I",
                "hypertext-owner",
                "-n",
                "hypertext-release",
                "-s",
                str(signature),
            ],
            input=path.read_bytes(),
            capture_output=True,
        )
        if verified.returncode != 0:
            raise ValueError("Owner exception signature verification failed")
    actual = {
        p.relative_to(supplemental.parent).as_posix()
        for p in supplemental.parent.rglob("*")
        if p.is_file() or p.is_symlink()
    }
    if actual != set(value["files"]) | {supplemental.name}:
        raise ValueError("Acceptance must bind the complete evidence directory")
    if (
        sum((supplemental.parent / name).stat().st_size for name in value["files"])
        > 512 * 1024 * 1024
    ):
        raise ValueError("Evidence bundle exceeds 512 MiB")
    for name, sha in value["files"].items():
        artifact({"artifact": name, "sha256": sha}, supplemental.parent)
    for record in read(supplemental).get("checks", []):
        if value["files"].get(record.get("artifact")) != record.get("sha256"):
            raise ValueError("Acceptance must bind every supplemental artifact")
    return value


def android_matrix(path):
    source = (ROOT / "docs/product-standards.md").read_text(encoding="utf-8")
    start, end = (
        "### Google Android adaptive design requirements\n",
        "### Research and inclusive product decisions\n",
    )
    if start not in source or end not in source:
        raise ValueError("Android source boundaries require explicit reconciliation")
    baseline = source.split(start, 1)[1].split(end, 1)[0]
    expected = set(
        re.findall(r"https://developer\.android\.com/[^)\s]+#[^)\s]+", baseline)
    )
    document = read(path)
    manifest_path = ROOT / "policy/android-sources/manifest.json"
    if document.get("sources_sha256") != digest(manifest_path):
        raise ValueError("Android evidence must bind the frozen source manifest")
    manifest = read(manifest_path)
    expected_pages = set(
        re.findall(r"https://developer\.android\.com/[^)\s]+", baseline)
    )
    expected_pages = {url.split("#")[0] for url in expected_pages}
    if {source["url"] for source in manifest["sources"]} != expected_pages:
        raise ValueError("Frozen sources do not cover the complete source baseline")
    wanted_clauses = {}
    for source in manifest["sources"]:
        snapshot_path = ROOT / "policy/android-sources" / source["file"]
        if digest(snapshot_path) != source["sha256"]:
            raise ValueError("Android source snapshot changed")
        for clause in read(snapshot_path)["clauses"]:
            wanted_clauses[(source["url"], clause["label"])] = clause["sha256"]
    coverage = document.get("clauses")
    if not isinstance(coverage, list):
        raise ValueError(
            "Android needs full source clause coverage, including specialized checklists"
        )
    covered = set()
    for row in coverage:
        if not isinstance(row, dict):
            raise ValueError("Invalid source clause")
        key = (row.get("source"), row.get("clause"))
        if key in covered or wanted_clauses.get(key) != row.get("sha256"):
            raise ValueError("Duplicate, changed or unknown Android source clause")
        covered.add(key)
        if row.get("status") == "not_applicable":
            if (
                not isinstance(row.get("reason"), str)
                or not row["reason"].strip()
                or not isinstance(row.get("applicability_condition"), str)
                or not row["applicability_condition"].strip()
            ):
                raise ValueError(
                    "Source clause exclusion needs the actual applicability condition and product reason"
                )
        elif (
            row.get("status") != "pass"
            or not isinstance(row.get("observation"), str)
            or not row["observation"].strip()
        ):
            raise ValueError("Source clause lacks observed result")
    if covered != set(wanted_clauses):
        raise ValueError("Incomplete Android subclause or specialized-source coverage")
    matrix = document.get("checks")
    if not expected or not isinstance(matrix, list):
        raise ValueError("Android evidence needs the indexed guideline/test matrix")
    seen = set()
    for row in matrix:
        if not isinstance(row, dict) or not isinstance(row.get("source"), str):
            raise ValueError("Invalid Android matrix row")
        identifier = row["source"]
        if identifier in seen:
            raise ValueError("Duplicate Android source row")
        seen.add(identifier)
        if row.get("status") == "not_applicable":
            if "/tier-3#Config_" in identifier or "/tier-3#T-Config_" in identifier:
                raise ValueError(
                    "Mandatory configuration transitions cannot be exempted"
                )
            if not isinstance(row.get("reason"), str) or not row["reason"].strip():
                raise ValueError(
                    "Android non-applicability needs a capability-based reason"
                )
            if (
                row.get("applicability_condition") is None
                or "not implemented" in row["reason"].lower()
            ):
                raise ValueError(
                    "Android exclusions need the actual source applicability condition"
                )
        elif (
            row.get("status") != "pass"
            or not isinstance(row.get("observation"), str)
            or not row["observation"].strip()
        ):
            raise ValueError("Android row has no passing observed result")
    if seen != expected:
        raise ValueError(
            "Android matrix does not cover every indexed guideline and test"
        )


def record_digest(record):
    return hashlib.sha256(
        json.dumps(
            record, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
    ).hexdigest()


def check(repo, profile_path, evidence_path, policy, approved_exceptions=None):
    profile = read(profile_path)
    rules = required(profile, policy)
    expected = context(repo, profile_path)
    evidence = read(evidence_path)
    if (
        type(evidence.get("schema_version")) is not int
        or evidence.get("schema_version") != 1
        or evidence.get("subject") != expected
    ):
        raise ValueError(
            "Evidence does not match this commit, profile, standards, and gate policy"
        )
    rows = evidence.get("checks")
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError("checks must be a list of objects")
    mapping = {}
    for row in rows:
        identifier = row.get("id")
        if not isinstance(identifier, str) or identifier in mapping:
            raise ValueError("Missing or duplicate check ID")
        mapping[identifier] = row
    if set(mapping) != {rule["id"] for rule in rules}:
        missing = sorted({rule["id"] for rule in rules} - set(mapping))
        extra = sorted(set(mapping) - {rule["id"] for rule in rules})
        raise ValueError(
            f"Evidence must cover exactly the applicable key checks; missing/extra checks cannot pass; missing={missing}; extra={extra}"
        )
    errors = []
    for rule in rules:
        record = mapping[rule["id"]]
        try:
            if (
                not isinstance(record.get("summary"), str)
                or not record["summary"].strip()
            ):
                raise ValueError("Missing observed outcome summary")
            if record.get("status") == "exception":
                if (
                    not rule["exception_allowed"]
                    or record.get("kind") != "owner-decision"
                ):
                    raise ValueError("This check cannot be waived")
                approved = (approved_exceptions or {}).get(rule["id"])
                canonical = record_digest(record)
                if approved != canonical:
                    raise ValueError(
                        "Exception needs an exact owner-approved record digest from the external trust boundary"
                    )
                for field in (
                    "reason",
                    "impact",
                    "compensation",
                    "decided_on",
                    "review_on",
                ):
                    if (
                        not isinstance(record.get(field), str)
                        or not record[field].strip()
                    ):
                        raise ValueError(f"Missing exception field: {field}")
                if (
                    date.fromisoformat(record["decided_on"]) > date.today()
                    or date.fromisoformat(record["review_on"]) <= date.today()
                ):
                    raise ValueError("Exception is future-dated or expired")
            elif (
                record.get("status") != "pass"
                or record.get("kind") not in rule["evidence_kinds"]
            ):
                raise ValueError("Missing pass or inappropriate evidence kind")
            path = artifact(record, evidence_path.parent)
            if rule["id"] == "android.adaptive":
                android_matrix(path)
        except (ValueError, OSError) as error:
            errors.append(f"{rule['id']}: {error}")
    return errors


def diagnostics(failures, policy=None, evidence=None):
    """Explain failures without changing their acceptance or exception semantics."""
    rules = {row["id"]: row for row in (policy or {}).get("rules", [])}
    results = []
    for failure in failures:
        identifier, _, detail = str(failure).partition(": ")
        rule = rules.get(identifier)
        message = detail if rule else str(failure)
        action = (
            "Correct the reported input or configuration, then rerun the same gate."
        )
        lower = message.lower()
        if "command failed" in lower:
            action = "Read the command report and linked log; fix the failing behavior and rerun verification into a new output directory."
        elif any(
            term in lower
            for term in ("uncommitted", "untracked", "checkout", "index flags")
        ):
            action = "Inspect git status and index flags; preserve unrelated work and verify the intended clean commit."
        elif any(
            term in lower
            for term in ("exception", "waived", "authorization", "trusted")
        ):
            action = "Supply the required external trust input or obtain the owner's scoped decision; the agent cannot approve or weaken this check."
        elif any(
            term in lower
            for term in (
                "stale",
                "does not match",
                "changed",
                "modified",
                "hash",
                "digest",
            )
        ):
            action = "Recollect evidence for the actual commit, policy, profile and artifact; do not edit hashes to make old evidence pass."
        elif "missing/extra" in lower or "cover exactly" in lower:
            action = "List applicable requirements with hypertext.py doctor --requirements; collect the missing outcomes and remove only genuinely inapplicable records."
        elif "timeout" in lower:
            action = "Inspect the bounded command log and resolve the hang or missing prerequisite before retrying."
        elif "no such file" in lower or "not found" in lower:
            action = "Supply the named file or executable at the expected path; do not substitute an asserted pass."
        elif rule:
            action = "Collect a passing observed outcome using an allowed evidence kind, including its artifact; rerun release validation."
        results.append(
            {
                "requirement": identifier if rule else "gate.input",
                "source": rule["source"]
                if rule
                else "Gate execution and evidence integrity",
                "allowed_evidence_kinds": rule["evidence_kinds"] if rule else [],
                "problem": message,
                "evidence": str(evidence) if evidence else None,
                "next_action": action,
            }
        )
    return results


def report(
    failures, policy=None, evidence=None, json_output=False, scope="evidence-records"
):
    rows = diagnostics(failures, policy, evidence)
    if json_output:
        print(
            json.dumps(
                {
                    "schema_version": 1,
                    "status": "blocked" if rows else "passed",
                    "scope": scope,
                    "diagnostics": rows,
                    "limitation": "Read-only record validation; no checks executed and no release readiness or production outcome established."
                    if scope == "evidence-records"
                    else "Record integrity and executed checks do not independently prove non-automated app outcomes.",
                },
                indent=2,
            )
        )
    else:
        for row in rows:
            print(
                f"{row['requirement']}: {row['problem']}\n  Source: {row['source']}\n  Next: {row['next_action']}"
            )
            if row["evidence"]:
                print(f"  Evidence: {row['evidence']}")
    return 1 if rows else 0


def main(argv=None):
    from .cli import Parser

    parser = Parser(description=__doc__)
    parser.add_argument("command", choices=("requirements", "subject", "check"))
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument(
        "--profile", type=Path, help="Defaults to REPO/.hypertext/product.json"
    )
    parser.add_argument("--evidence", type=Path)
    parser.add_argument(
        "--json", action="store_true", help="Structured check diagnostics"
    )
    args = parser.parse_args(argv)
    repo = args.repo.resolve()
    profile = (
        args.profile.resolve() if args.profile else repo / ".hypertext/product.json"
    )
    try:
        policy = read(ROOT / "policy/key-standards.json")
        rules = required(read(profile), policy)
        # Without --json these print the raw list and subject: release evidence and
        # acceptance records copy the subject verbatim and compare it exactly.
        if args.command == "requirements":
            document = rules
            if args.json:
                document = {"schema_version": 1, "status": "ok", "requirements": rules}
            print(json.dumps(document, indent=2))
        elif args.command == "subject":
            document = context(repo, profile)
            if args.json:
                document = {"schema_version": 1, "status": "ok", "subject": document}
            print(json.dumps(document, indent=2))
        else:
            if args.evidence is None:
                raise ValueError("check requires --evidence")
            failures = check(repo, profile, args.evidence.resolve(), policy)
            if failures:
                raise SystemExit(report(failures, policy, args.evidence, args.json))
            if args.json:
                report([], policy, args.evidence, True)
            else:
                print(
                    f"Evidence records accepted for {len(rules)} key checks. This does not independently prove app behavior or full conformance."
                )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(
            report([str(error)], locals().get("policy"), args.evidence, args.json)
        )


if __name__ == "__main__":
    main()
