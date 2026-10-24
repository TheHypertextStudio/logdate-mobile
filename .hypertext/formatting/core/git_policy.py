"""Studio commit contracts, local hooks and changelogs. No host or agent dependency."""

import hashlib
import contextvars
import functools
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

POLICY_PATH = ".hypertext/commits.json"
FORBIDDEN_SCOPES = {
    "internal",
    "misc",
    "other",
    "general",
    "all",
    "core",
    "ui",
    "frontend",
    "backend",
    "components",
    "utils",
    "tests",
    "docs",
    "ci",
    "build",
    "refactor",
    "deps",
}
HEADER = re.compile(
    r"(feat|fix|chore)\(([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\)(!)?: (\S(?:.*\S)?)"
)
IDENTITY = re.compile(r"[^<>\r\n]+ <[^<>\s@]+@[^<>\s@]+>")
TECHNICAL = re.compile(
    r"\b(?:refactor(?:ing|ed)?|middleware|JWT|OAuth|SQL|React|SwiftUI|ViewModel|DTO|ORM|webpack|tsconfig|node_modules|useEffect)\b|`[^`]+`|\b\w+\.(?:tsx?|jsx?|py|swift|kt|rs)\b",
    re.I,
)
VAGUE = re.compile(
    r"^(?:update|fix|change|improve|add|remove)(?: (?:stuff|things|code|files|misc|something|updates?))?[.!]?$",
    re.I,
)
MARKER = "# Hypertext Studio managed Git hook v1"
DEADLINE = contextvars.ContextVar("git_policy_deadline", default=None)
MAX_COMMITS = 10000


def bounded_history(function):
    @functools.wraps(function)
    def run(*args, **kwargs):
        token = DEADLINE.set(time.monotonic() + 60)
        try:
            return function(*args, **kwargs)
        finally:
            DEADLINE.reset(token)

    return run


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate commit-policy key: " + key)
        result[key] = value
    return result


def read_text(path):
    path = Path(path)
    if (
        path.is_symlink()
        or not path.is_file()
        or path.stat().st_nlink != 1
        or path.stat().st_size > 1024 * 1024
    ):
        raise ValueError("Expected a regular file under 1 MiB: " + str(path))
    with path.open(encoding="utf-8", newline="") as stream:
        return stream.read()


def validate_policy(value):
    if (
        not isinstance(value, dict)
        or set(value) != {"schema_version", "scopes"}
        or type(value["schema_version"]) is not int
        or value["schema_version"] != 1
    ):
        raise ValueError("Commit policy requires schema_version 1 and scopes")
    scopes = value["scopes"]
    if not isinstance(scopes, dict) or not scopes:
        raise ValueError("Define actual product domains in the commit scope registry")
    for name, scope in scopes.items():
        if (
            not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", name)
            or name in FORBIDDEN_SCOPES
        ):
            raise ValueError(
                "Scope must name a feature/domain, never a catch-all or implementation layer: "
                + name
            )
        if (
            not isinstance(scope, dict)
            or set(scope) != {"description", "includes", "excludes", "technical"}
            or type(scope["technical"]) is not bool
        ):
            raise ValueError(
                "Each scope needs description, includes, excludes and an explicit technical boolean: "
                + name
            )
        if (
            not isinstance(scope["description"], str)
            or not scope["description"].strip()
        ):
            raise ValueError(
                "Scope needs an understandable domain description: " + name
            )
        for key in ("includes", "excludes"):
            if (
                not isinstance(scope[key], list)
                or not scope[key]
                or any(
                    not isinstance(item, str) or not item.strip() for item in scope[key]
                )
            ):
                raise ValueError(
                    "Define scope boundaries with nonempty includes and excludes: "
                    + name
                )
    return value


def read_policy(path):
    return validate_policy(json.loads(read_text(path), object_pairs_hook=unique))


def trailers(message):
    """Only terminal trailer paragraphs count; quoted claims in prose do not."""
    block = message.strip().split("\n\n")[-1]
    lines = block.splitlines()
    if not lines or not all(
        re.match(r"^[A-Za-z][A-Za-z -]*: .+", line) or line.startswith((" ", "\t"))
        for line in lines
    ):
        return []
    return [
        (key.lower(), value.strip())
        for line in lines
        if not line.startswith((" ", "\t"))
        for key, value in [line.split(": ", 1)]
    ]


def coauthors(message):
    values = [value for key, value in trailers(message) if key == "co-authored-by"]
    if any(not IDENTITY.fullmatch(value) for value in values):
        raise ValueError(
            "Co-authored-by must contain the actual contributor name and email"
        )
    return values


def validate_message(message, policy, expected_agents=()):
    validate_policy(policy)
    if (
        not isinstance(message, str)
        or not message
        or "\r" in message
        or any(ord(c) < 32 and c not in "\n\t" for c in message)
    ):
        raise ValueError("Commit message must be plain text with LF line endings")
    lines = message.rstrip().split("\n")
    match = HEADER.fullmatch(lines[0])
    if not match:
        raise ValueError(
            "Use feat(scope): outcome, fix(scope): outcome, or chore(scope): specific maintenance"
        )
    kind, scope, bang, description = match.groups()
    if scope not in policy["scopes"]:
        raise ValueError(
            "Unregistered scope "
            + scope
            + "; choose an existing domain. New feature categories require owner review"
        )
    if VAGUE.fullmatch(description) or description.lower() in {
        "wip",
        "work in progress",
        "changes",
        "updates",
    }:
        raise ValueError(
            "Describe the actual high-level change; generic maintenance messages are insufficient"
        )
    if len(lines) > 1 and lines[1] != "":
        raise ValueError("Separate the title and body/trailers with a blank line")
    authors = coauthors(message)
    for agent in expected_agents:
        if not IDENTITY.fullmatch(agent):
            raise ValueError("Expected agent attribution must be Name <email>")
        if agent not in authors:
            raise ValueError("Missing terminal Co-authored-by: " + agent)

    # Machine footers are metadata, not product prose. A technical word in body
    # prose still fails even when followed by otherwise valid attribution.
    def metadata(line):
        if re.fullmatch(r"Co-authored-by: " + IDENTITY.pattern, line, re.I):
            return True
        return bool(
            re.fullmatch(
                r"(?:Refs|Closes|Fixes): (?:#[0-9]+|[A-Z][A-Z0-9]+-[0-9]+|https://[^\s]+)(?:, (?:#[0-9]+|[A-Z][A-Z0-9]+-[0-9]+|https://[^\s]+))*",
                line,
            )
        )

    narrative = "\n".join(line for line in lines if not metadata(line))
    if (
        kind in {"feat", "fix"}
        and not policy["scopes"][scope]["technical"]
        and TECHNICAL.search(narrative)
    ):
        raise ValueError(
            "Use product-facing language for "
            + scope
            + "; explain what people can do or what now works"
        )
    body = "\n".join(lines[2:]).strip()
    footer_block = message.strip().split("\n\n")[-1] if trailers(message) else ""
    substantive = (
        body[: -len(footer_block)].strip()
        if footer_block and body.endswith(footer_block)
        else body
    )
    if kind == "chore" and not substantive:
        raise ValueError(
            "chore needs a body explaining the maintenance and why it matters; attribution alone is insufficient"
        )
    breaking = re.search(r"^BREAKING(?: CHANGE|-CHANGE): (\S[^\n]*)", message, re.M)
    if bang and not breaking:
        raise ValueError(
            "Breaking changes require a BREAKING CHANGE: explanation and migration impact"
        )
    return {
        "type": kind,
        "scope": scope,
        "description": description,
        "breaking": breaking.group(1) if breaking else None,
        "coauthors": authors,
    }


def git(repo, *args, identity=False):
    from . import setup_files, runner

    environment = dict(os.environ)
    for key in list(environment):
        if key.startswith("GIT_") and not identity:
            environment.pop(key)
    environment.update(
        GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never", SSH_ASKPASS_REQUIRE="never"
    )
    timeout = (
        min(15, max(0, DEADLINE.get() - time.monotonic())) if DEADLINE.get() else 15
    )
    if timeout <= 0:
        raise ValueError(
            "Git history inspection exceeded 60 seconds; narrow the transaction"
        )
    output = io.BytesIO()
    argv = [
        setup_files.trusted_git(Path(repo)),
        "--no-replace-objects",
        "-c",
        "core.fsmonitor=false",
        "-c",
        "protocol.ext.allow=never",
        "-C",
        str(repo),
        *args,
    ]
    try:
        code = runner.execute(
            argv,
            Path(repo),
            output,
            timeout,
            stdin=subprocess.DEVNULL,
            environment=environment,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError(
            "Git inspection stopped: "
            + args[0]
            + "; command time/output limit or execution failure"
        ) from None
    # Never echo remote URLs, credential-bearing errors or message contents.
    if code:
        raise ValueError(
            "Git inspection failed: "
            + args[0]
            + "; check repository, access and fetched history"
        )
    try:
        return output.getvalue().decode("utf-8").strip()
    except UnicodeError:
        raise ValueError("Git inspection returned invalid UTF-8: " + args[0]) from None


def check_hook_context(repo):
    expected = git(repo, "rev-parse", "--absolute-git-dir")
    actual = git(repo, "rev-parse", "--absolute-git-dir", identity=True)
    if Path(actual).resolve() != Path(expected).resolve():
        raise ValueError(
            "Hook repository context differs from the checkout; use the intended worktree"
        )


def complete_history(repo):
    if git(repo, "rev-parse", "--is-shallow-repository") != "false":
        raise ValueError(
            "Shallow history cannot establish commit coverage; fetch complete history"
        )
    graft = Path(git(repo, "rev-parse", "--git-path", "info/grafts"))
    if not graft.is_absolute():
        graft = Path(repo) / graft
    if graft.exists() or graft.is_symlink():
        raise ValueError(
            "Grafted history cannot establish commit coverage; remove the graph override"
        )


def tree_policy(repo, tree):
    entry = git(repo, "ls-tree", tree, "--", POLICY_PATH)
    if not re.fullmatch(
        r"100(?:644|755) blob (?:[a-f0-9]{40}|[a-f0-9]{64})\t" + re.escape(POLICY_PATH),
        entry,
    ):
        raise ValueError("Published branch registry must be a regular file")
    return validate_policy(
        json.loads(
            git(repo, "show", tree + ":" + POLICY_PATH), object_pairs_hook=unique
        )
    )


def authorized_policy(repo, candidate, baseline):
    if candidate == baseline:
        return
    digest = hashlib.sha256(
        read_text(Path(repo) / POLICY_PATH).encode("utf-8")
    ).hexdigest()
    if os.environ.get("HYPERTEXT_APPROVED_COMMIT_POLICY_SHA256") != digest:
        raise ValueError(
            "Scope registry differs from committed/published policy; owner review and exact policy authorization required"
        )


def hook_policy(repo):
    check_hook_context(repo)
    policy = read_policy(Path(repo) / POLICY_PATH)
    # Git supplies a temporary index for partial commits. Inspect that exact index.
    staged = validate_policy(
        json.loads(
            git(repo, "show", ":" + POLICY_PATH, identity=True),
            object_pairs_hook=unique,
        )
    )
    if staged != policy:
        raise ValueError(
            "Working and staged scope registries differ; stage the reviewed registry with its change"
        )
    if git(repo, "rev-parse", "--revs-only", "HEAD"):
        authorized_policy(repo, policy, tree_policy(repo, "HEAD"))
    return policy


def check_registry(repo, policy):
    from . import setup_files

    path = setup_files.confined(Path(repo) / POLICY_PATH, Path(repo))
    if read_policy(path) != policy:
        raise ValueError(
            "Candidate scope registry differs from the trusted baseline; review scope changes separately before using them"
        )


@bounded_history
def verify_range(repo, base, head, policy, synthetic_head=False):
    complete_history(repo)
    check_registry(repo, policy)

    def resolve(value):
        result = git(
            repo, "rev-parse", "--verify", "--end-of-options", value + "^{commit}"
        )
        if not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", result):
            raise ValueError("Expected an exact commit identity")
        return result

    base, head = resolve(base), resolve(head)
    git(repo, "merge-base", "--is-ancestor", base, head)
    commits = git(
        repo,
        "rev-list",
        "--reverse",
        "--max-count=" + str(MAX_COMMITS + 1),
        base + ".." + head,
    ).splitlines()
    if not commits:
        raise ValueError(
            "No new commits in the selected range; choose the actual previous release/base"
        )
    if len(commits) > MAX_COMMITS:
        raise ValueError("Commit range exceeds 10000 commits")
    return validate_commits(repo, commits, policy, head if synthetic_head else None)


def validate_commits(repo, commits, policy, synthetic_head=None):
    rows = []
    for commit in commits:
        parents = git(repo, "rev-list", "--parents", "-n", "1", commit).split()[1:]
        if len(parents) > 1:
            if commit == synthetic_head:
                continue  # host-created merge-queue candidate only
            raise ValueError(
                "Merge commits cannot enter the Studio changelog; rebase the change before main: "
                + commit[:12]
            )
        message = git(repo, "show", "-s", "--format=%B", commit)
        try:
            row = validate_message(message, policy)
        except ValueError as error:
            raise ValueError(commit[:12] + ": " + str(error)) from error
        row["commit"] = commit
        rows.append(row)
    if not rows:
        raise ValueError("No product commits validated in the selected range")
    return rows


def validate_host_policy(value):
    expected = {
        "default_branch": "main",
        "allow_rebase_merge": True,
        "allow_merge_commit": False,
        "allow_squash_merge": False,
    }
    if not isinstance(value, dict):
        raise ValueError(
            "Repository merge settings must be an authenticated API object"
        )
    for key, wanted in expected.items():
        if type(value.get(key)) is not type(wanted) or value[key] != wanted:
            raise ValueError(
                "Repository must use main and rebase-only PR merging; configure "
                + key
                + "="
                + str(wanted)
            )


@bounded_history
def verify_main_range(repo, base, head, policy, main_ref="refs/heads/main"):
    if main_ref not in {"refs/heads/main", "refs/remotes/origin/main"}:
        raise ValueError("Changelog authority must be main, never a topic branch")
    complete_history(repo)
    head = git(repo, "rev-parse", "--verify", "--end-of-options", head + "^{commit}")
    try:
        git(repo, "merge-base", "--is-ancestor", head, main_ref)
    except ValueError:
        raise ValueError(
            "Product changelogs require commits landed on main; fetch main and integrate the change first"
        ) from None
    return verify_range(repo, base, head, policy)


def validate_squash(message, rows, policy):
    agents = sorted({agent for row in rows for agent in row["coauthors"]})
    return validate_message(message, policy, agents)


def changelog(rows, policy):
    lines = ["# Changelog", ""]
    breaking = [row for row in rows if row["breaking"]]
    if breaking:
        lines += ["## Breaking changes", ""]
        lines += ["- " + row["breaking"] for row in breaking]
        lines += [""]
    for title, kinds in (("Changes", {"feat", "fix"}), ("Maintenance", {"chore"})):
        selected = [row for row in rows if row["type"] in kinds]
        if not selected:
            continue
        lines += ["## " + title, ""]
        for scope in sorted({row["scope"] for row in selected}):
            lines += ["### " + policy["scopes"][scope]["description"], ""]
            lines += [
                "- " + row["description"] for row in selected if row["scope"] == scope
            ]
            lines += [""]
    return "\n".join(lines)


def hook_contents(root, relative=False):
    launcher = Path(root) / "scripts/hypertext.py"
    # Installed product launchers remain relative to the checkout; plugin-owned
    # installations can also use their verified package until gate adoption.
    import shlex

    location = "scripts/hypertext.py" if relative else str(launcher)
    prefix = "exec python3 " + shlex.quote(location) + ' verify --repo "$PWD" '
    return {
        "pre-commit": "#!/bin/sh\n" + MARKER + "\n" + prefix + "--git-precommit\n",
        "commit-msg": "#!/bin/sh\n" + MARKER + "\n" + prefix + '--git-message "$1"\n',
        "pre-push": "#!/bin/sh\n"
        + MARKER
        + "\n"
        + prefix
        + '--git-prepush --git-remote "$1" --git-remote-url "$2"\n',
    }


def install_hooks(repo, root, apply=False):
    from . import setup_files

    repo = Path(repo).resolve()
    if Path(git(repo, "rev-parse", "--show-toplevel")).resolve() != repo:
        raise ValueError("Git hook installation requires the checkout root")
    configured = (
        git(repo, "config", "--get-regexp", r"^core\.hooksPath$")
        if git_configured(repo)
        else ""
    )
    if configured and configured.split(None, 1)[-1] != ".githooks":
        raise ValueError(
            "Existing core.hooksPath needs explicit reconciliation; no hooks were replaced"
        )
    default = Path(git(repo, "rev-parse", "--git-common-dir"))
    if not default.is_absolute():
        default = repo / default
    old_hooks = default / "hooks"
    if old_hooks.is_dir():
        for existing in old_hooks.iterdir():
            if not existing.name.endswith(".sample"):
                raise ValueError(
                    "Existing Git hook needs explicit reconciliation: " + str(existing)
                )
    target = setup_files.confined(repo / ".githooks", repo)
    hooks = hook_contents(root, relative=Path(root).resolve() == repo)
    for name, content in hooks.items():
        path = setup_files.confined(target / name, repo)
        if path.exists() and read_text(path) != content:
            raise ValueError(
                "Existing hook differs; preserve and reconcile " + str(path)
            )
    if apply:
        target.mkdir(exist_ok=True)
        for name, content in hooks.items():
            path = target / name
            if not path.exists():
                with path.open("x", encoding="utf-8", newline="\n") as stream:
                    stream.write(content)
            path.chmod(path.stat().st_mode | 0o111)
        git(repo, "config", "--local", "core.hooksPath", ".githooks")
    return {
        "applied": apply,
        "hooks": sorted(hooks),
        "hooks_path": ".githooks",
        "host_protection": "unverified",
    }


def git_configured(repo):
    # --get exits 1 for an absent value; don't conflate it with inspection errors.
    return bool(
        git(repo, "config", "--list", "--name-only")
        .splitlines()
        .count("core.hookspath")
    )


def repository_contract(repo, active=False):
    from . import setup_files

    repo = Path(repo).resolve()
    policy = read_policy(setup_files.confined(repo / POLICY_PATH, repo))
    for name, content in hook_contents(repo, relative=True).items():
        path = setup_files.confined(repo / ".githooks" / name, repo)
        if read_text(path) != content:
            raise ValueError(
                "Missing/modified Studio hook: " + name + "; reconcile and reinstall"
            )
        if active and os.name != "nt" and not os.access(path, os.X_OK):
            raise ValueError("Studio hook is not executable: " + name)
    if active:
        if (
            not git_configured(repo)
            or git(repo, "config", "--get", "core.hooksPath") != ".githooks"
        ):
            raise ValueError(
                "Local Git hooks are not active; install --git-hooks --apply when authorized"
            )
    return policy


def require_studio_identity(repo):
    check_hook_context(repo)
    identity = git(repo, "var", "GIT_COMMITTER_IDENT", identity=True)
    match = re.search(r"<([^<>]+)>", identity)
    if not match:
        raise ValueError("Git could not resolve the effective committer email")
    email = match.group(1)
    if not re.fullmatch(r"[^@\s]+@hypertext\.studio", email, re.I | re.ASCII):
        if os.environ.get("HYPERTEXT_ALLOW_NON_STUDIO_EMAIL") == "1":
            return "Explicit non-Studio committer exception: " + email
        raise ValueError(
            "Committer "
            + email
            + " is outside @hypertext.studio. Set git config --local user.email YOUR@hypertext.studio and clear conflicting identity overrides. An authorized exception requires HYPERTEXT_ALLOW_NON_STUDIO_EMAIL=1 for this operation. Co-authored-by emails are unrestricted."
        )
    return None


@bounded_history
def verify_push(repo, remote, updates, policy, remote_url=None):
    """Validate the exact ref-update transaction supplied by native Git pre-push."""
    check_registry(repo, policy)
    complete_history(repo)
    if len(updates) > 1024 * 1024:
        raise ValueError("Push transaction exceeds 1 MiB")
    lines = updates.splitlines()
    if len(lines) > 1024:
        raise ValueError("Push transaction exceeds 1024 refs")
    inspected = set()
    published = None
    queried = False

    def commit(oid):
        return git(repo, "rev-parse", "--verify", "--end-of-options", oid + "^{commit}")

    for line in lines:
        parts = line.split()
        if len(parts) != 4 or any(
            not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", oid)
            for oid in (parts[1], parts[3])
        ):
            raise ValueError("Malformed native pre-push ref transaction")
        local_ref, local_oid, remote_ref, remote_oid = parts
        if not remote_ref.startswith("refs/"):
            raise ValueError("Expected a full remote ref name")
        git(repo, "check-ref-format", remote_ref)
        if set(local_oid) == {"0"}:
            if remote_ref == "refs/heads/main":
                raise ValueError("Deleting main is prohibited")
            continue
        head = commit(local_oid)
        if tree_policy(repo, head) != policy:
            raise ValueError(
                "Pushed ref scope registry differs from selected policy; inspect the actual outgoing branch"
            )
        base = None
        if set(remote_oid) != {"0"}:
            try:
                previous = commit(remote_oid)
            except ValueError as error:
                raise ValueError(
                    "Remote history is unavailable locally; fetch the remote before pushing"
                ) from error
            entry = git(repo, "ls-tree", previous, "--", POLICY_PATH)
            if not entry and remote_ref != "refs/heads/main":
                digest = hashlib.sha256(
                    read_text(Path(repo) / POLICY_PATH).encode("utf-8")
                ).hexdigest()
                if os.environ.get("HYPERTEXT_APPROVED_COMMIT_POLICY_SHA256") != digest:
                    raise ValueError(
                        "Legacy branch registry adoption requires exact owner-approved policy authorization"
                    )
                # The old branch predates policy adoption. Anchor its replacement
                # to published main, never an editable local tracking reference.
                if not queried:
                    url = remote_url or git(repo, "remote", "get-url", "--push", remote)
                    advertised = git(
                        repo, "ls-remote", "--", url, "refs/heads/main"
                    ).splitlines()
                    if len(advertised) != 1 or not re.fullmatch(
                        r"(?:[a-f0-9]{40}|[a-f0-9]{64})\trefs/heads/main", advertised[0]
                    ):
                        raise ValueError(
                            "Legacy branch adoption requires one published main"
                        )
                    published = commit(advertised[0].split()[0])
                    queried = True
                if published is None:
                    raise ValueError(
                        "Legacy branch adoption requires one published main"
                    )
                previous = published
                authorized_policy(repo, policy, tree_policy(repo, previous))
                git(repo, "merge-base", "--is-ancestor", previous, head)
                base = previous
            else:
                base = git(repo, "merge-base", previous, head)
                authorized_policy(repo, policy, tree_policy(repo, previous))
            if remote_ref == "refs/heads/main" and base != previous:
                raise ValueError("Rewriting main history is prohibited")
        else:
            # Local tracking refs are editable. Query the actual push destination
            # once, never a fetch URL or a guessed local branch.
            if not queried:
                url = remote_url or git(repo, "remote", "get-url", "--push", remote)
                advertised = git(
                    repo, "ls-remote", "--", url, "refs/heads/main"
                ).splitlines()
                if advertised:
                    if len(advertised) != 1 or not re.fullmatch(
                        r"(?:[a-f0-9]{40}|[a-f0-9]{64})\trefs/heads/main", advertised[0]
                    ):
                        raise ValueError("Remote main advertisement is malformed")
                    try:
                        published = commit(advertised[0].split()[0])
                    except ValueError as error:
                        raise ValueError(
                            "Published main history is missing locally; fetch the push destination"
                        ) from error
                queried = True
            previous = published
            if previous is not None:
                authorized_policy(repo, policy, tree_policy(repo, previous))
            if previous is not None:
                base = git(repo, "merge-base", previous, head)
        selection = base + ".." + head if base else head
        commits = git(
            repo,
            "rev-list",
            "--reverse",
            "--max-count=" + str(MAX_COMMITS + 1),
            selection,
        ).splitlines()
        if len(set(commits) | inspected) > MAX_COMMITS:
            raise ValueError("Push transaction exceeds 10000 unique commits")
        new = [oid for oid in commits if oid not in inspected]
        if new:
            validate_commits(repo, new, policy)
        inspected.update(commits)
    return len(inspected)


def main(argv, command):
    from .cli import Parser

    parser = Parser(
        prog="hypertext " + command,
        description=__doc__,
        repeatable=("--agent-coauthor",),
    )
    parser.add_argument("--repo", type=Path, required=True)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument(
        "--git-host-policy",
        type=Path,
        help="Validate authenticated repository API merge settings",
    )
    modes.add_argument("--git-message", type=Path)
    modes.add_argument("--git-range", help="BASE..HEAD, with explicit endpoints")
    modes.add_argument(
        "--git-identity",
        action="store_true",
        help="Enforce local committer identity (legacy hook entry)",
    )
    modes.add_argument(
        "--git-precommit",
        action="store_true",
        help="Shared pre-commit staged formatting, identity and registry gate",
    )
    modes.add_argument(
        "--git-prepush",
        action="store_true",
        help="Validate the native ref transaction on stdin",
    )
    parser.add_argument(
        "--git-main-ref",
        choices=("refs/heads/main", "refs/remotes/origin/main"),
        help="Main ref used as changelog authority",
    )
    parser.add_argument("--git-remote", help="Remote name supplied by native pre-push")
    parser.add_argument(
        "--git-remote-url", help="Actual destination supplied by native pre-push"
    )
    parser.add_argument("--trusted-git-policy", type=Path)
    parser.add_argument(
        "--approved-git-policy-sha256",
        help="Owner-controlled authorization for one exact scope-registry change",
    )
    parser.add_argument("--git-squash-message", type=Path)
    parser.add_argument(
        "--git-synthetic-head",
        action="store_true",
        help="Only for a protected host merge-queue synthetic HEAD",
    )
    parser.add_argument("--agent-coauthor", action="append", default=[])
    parser.add_argument(
        "--changelog",
        action="store_true",
        help="Write generated Markdown to stdout from a validated range",
    )
    args = parser.parse_args(argv)
    try:
        if args.git_host_policy:
            if command != "verify" or any(
                (
                    args.changelog,
                    args.git_main_ref,
                    args.trusted_git_policy,
                    args.git_squash_message,
                    args.git_synthetic_head,
                    args.agent_coauthor,
                    args.approved_git_policy_sha256,
                    args.git_remote,
                    args.git_remote_url,
                )
            ):
                raise ValueError(
                    "Repository settings validation requires verify --git-host-policy only"
                )
            validate_host_policy(
                json.loads(read_text(args.git_host_policy), object_pairs_hook=unique)
            )
            print(
                "Repository settings require rebase-only PR merging on main; required-check protection must be activated separately."
            )
            return 0
        if args.git_main_ref and not args.changelog:
            raise ValueError("--git-main-ref is only valid for a main changelog")
        if args.changelog and (args.git_squash_message or args.git_synthetic_head):
            raise ValueError(
                "Main changelogs cannot use a squash proposal or synthetic merge head"
            )
        if args.git_identity or args.git_precommit:
            if command != "verify" or any(
                (
                    args.changelog,
                    args.trusted_git_policy,
                    args.git_squash_message,
                    args.agent_coauthor,
                    args.git_synthetic_head,
                    args.approved_git_policy_sha256,
                    args.git_remote,
                    args.git_remote_url,
                )
            ):
                raise ValueError(
                    "Pre-commit identity checks cannot combine with history or release options"
                )
            exception = require_studio_identity(args.repo)
            if exception:
                print(exception, file=sys.stderr)
            if args.git_precommit:
                hook_policy(args.repo)
                from .formatting import format_staged

                changed = format_staged(args.repo)
                hook_policy(args.repo)
                if changed:
                    print(
                        "Automatically formatted "
                        + str(len(changed))
                        + " staged file(s)."
                    )
            return 0
        if args.git_prepush:
            if (
                command != "verify"
                or not args.git_remote
                or any(
                    (
                        args.changelog,
                        args.trusted_git_policy,
                        args.git_squash_message,
                        args.agent_coauthor,
                        args.git_synthetic_head,
                        args.approved_git_policy_sha256,
                    )
                )
            ):
                raise ValueError(
                    "Pre-push requires verify --git-prepush --git-remote NAME without release/range options"
                )
            policy = read_policy(args.repo / POLICY_PATH)
            check_hook_context(args.repo)
            verify_push(
                args.repo,
                args.git_remote,
                sys.stdin.read(1024 * 1024 + 1),
                policy,
                args.git_remote_url,
            )
            return 0
        if args.git_remote or args.git_remote_url:
            raise ValueError("Remote options are only valid for --git-prepush")
        if args.changelog and (command != "release" or not args.git_range):
            raise ValueError(
                "Generate changelogs with release --changelog --git-range BASE..HEAD"
            )
        if command == "release" and not args.changelog:
            raise ValueError(
                "Use release --changelog for Git-only release notes; normal release gates remain separate"
            )
        if (args.git_squash_message or args.git_synthetic_head) and not args.git_range:
            raise ValueError("Squash/merge-queue validation requires a commit range")
        if args.trusted_git_policy and args.trusted_git_policy.resolve().is_relative_to(
            args.repo.resolve()
        ):
            raise ValueError("Trusted scope policy must be outside candidate control")
        policy = read_policy(args.trusted_git_policy or args.repo / POLICY_PATH)
        if args.approved_git_policy_sha256:
            if not args.trusted_git_policy:
                raise ValueError(
                    "Scope authorization requires a protected baseline policy"
                )
            candidate = args.repo / POLICY_PATH
            if (
                hashlib.sha256(candidate.read_bytes()).hexdigest()
                != args.approved_git_policy_sha256
            ):
                raise ValueError(
                    "Scope authorization does not match the exact candidate policy"
                )
            policy = read_policy(candidate)
        check_registry(args.repo, policy)
        if args.git_message:
            if not args.trusted_git_policy:
                policy = hook_policy(args.repo)
            expected = args.agent_coauthor or (
                [os.environ["HYPERTEXT_AGENT_COAUTHOR"]]
                if os.environ.get("HYPERTEXT_AGENT_COAUTHOR")
                else []
            )
            validate_message(
                read_text(args.git_message).replace("\r\n", "\n"), policy, expected
            )
        else:
            endpoints = args.git_range.split("..")
            if len(endpoints) != 2 or not all(endpoints):
                raise ValueError(
                    "Supply an explicit BASE..HEAD range, not a three-dot or implicit range"
                )
            if args.changelog:
                rows = verify_main_range(
                    args.repo,
                    *endpoints,
                    policy,
                    main_ref=args.git_main_ref or "refs/heads/main",
                )
            else:
                rows = verify_range(
                    args.repo,
                    *endpoints,
                    policy,
                    synthetic_head=args.git_synthetic_head,
                )
            for row in rows:
                if any(agent not in row["coauthors"] for agent in args.agent_coauthor):
                    raise ValueError(
                        "Commit "
                        + row["commit"][:12]
                        + " is missing expected agent attribution"
                    )
            if args.git_squash_message:
                validate_squash(read_text(args.git_squash_message), rows, policy)
            if args.changelog:
                print(changelog(rows, policy))
                return 0
        print(
            "Commit structure and registered scopes passed; semantic review and contribution truth remain required."
        )
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("Git policy blocked: " + str(error), file=sys.stderr)
        return 1
