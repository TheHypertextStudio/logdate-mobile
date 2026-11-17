#!/usr/bin/env python3
"""Format a repository with the Studio's standard formatter for each language.

    ./run format                 format every file in the repository
    ./run format PATH...         format these files or folders
    ./run format --staged        format what is staged, then restage it (pre-commit)
    ./run format --check [BASE]  fail when files are not formatted: every tracked file,
                                 or what changed since BASE; in GitHub Actions BASE is
                                 the push or pull request's own base

Each formatter runs once over all of its files:

    Prettier  JavaScript, TypeScript, JSON, CSS, HTML, Markdown, YAML, GraphQL
    Ruff      Python
    ktlint    Kotlin (needs Java)

Files no formatter claims are left alone, as are lock files and anything .gitattributes
marks `-format`, `linguist-generated` or `linguist-vendored`. Pinned formatter versions
install themselves into the user cache the first time they are needed.
"""

import fnmatch
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

PRETTIER = "3.9.10"
RUFF = "0.17.2"
KTLINT = "1.8.0"
KTLINT_SHA256 = "369ad2b789f95a011f807e1fcb690ccef80bd7cd014fd139e73ae82dcc0baeab"
# The Studio style. A repository's own Prettier configuration wins when present.
STYLE = {
    "semi": True,
    "singleQuote": True,
    "trailingComma": "all",
    "printWidth": 100,
    "tabWidth": 2,
    "useTabs": False,
    "bracketSpacing": True,
    "arrowParens": "always",
    "endOfLine": "lf",
    "overrides": [
        {
            "files": ["*.md", "*.mdx", "*.markdown"],
            "options": {"proseWrap": "preserve", "singleQuote": False},
        }
    ],
}
FORMATTERS = {
    "prettier": "js jsx mjs cjs ts tsx mts cts json jsonc json5 css scss less html vue "
    "md markdown mdx yml yaml graphql gql",
    "ruff": "py pyi",
    "ktlint": "kt kts",
}
CLAIMS = {s: tool for tool, suffixes in FORMATTERS.items() for s in suffixes.split()}
LEAVE = (
    "package-lock.json pnpm-lock.yaml yarn.lock bun.lock npm-shrinkwrap.json "
    "*.lock *.min.js *.min.css *.map *.snap"
).split()
# Configuration the formatters read beside the files they format.
CONFIG = (
    ".prettierrc* prettier.config.* .editorconfig package.json pyproject.toml ruff.toml "
    ".ruff.toml"
).split()
# Studio-managed copies stay exactly as installed.
MANAGED = (".hypertext/format.py", ".hypertext/gate/")
MAX_BYTES = 2 * 1024 * 1024
ARGUMENT_BYTES = 64 * 1024


class Blocked(Exception):
    """The files cannot be formatted; the commit must not proceed."""


class Unavailable(Exception):
    """A formatter could not be prepared on this machine."""


def git(*args, stdin=None, env=None, cwd=None):
    result = subprocess.run(
        ["git", "--literal-pathspecs", *args],
        input=stdin,
        capture_output=True,
        env=env,
        cwd=cwd,
    )
    if result.returncode:
        detail = result.stderr.decode(errors="replace").strip()
        raise Blocked("git " + args[0] + " failed: " + detail)
    return result.stdout


def names(output):
    return [os.fsdecode(item) for item in output.split(b"\0") if item]


def matches(path, patterns):
    name = Path(path).name
    return any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns)


def claimed(path):
    """The formatter for this path, or None when no formatter claims it."""
    if matches(path, LEAVE) or path.startswith(MANAGED):
        return None
    return CLAIMS.get(Path(path).suffix.lower().lstrip("."))


def read_index():
    entries = {}
    for row in git("ls-files", "--stage", "-z").split(b"\0"):
        if row:
            meta, path = row.split(b"\t", 1)
            mode, oid, stage = meta.decode().split()
            if stage != "0":
                raise Blocked("Resolve merge conflicts before committing.")
            entries[os.fsdecode(path)] = (mode, oid)
    return entries


def opted_out(paths, cached):
    """Paths the repository marks as generated, vendored or not to be formatted."""
    if not paths:
        return set()
    output = git(
        "check-attr",
        *(["--cached"] if cached else []),
        "-z",
        "--stdin",
        "format",
        "linguist-generated",
        "linguist-vendored",
        stdin=b"\0".join(map(os.fsencode, paths)) + b"\0",
    )
    fields = output.split(b"\0")
    result = set()
    for path, attribute, value in zip(fields[0::3], fields[1::3], fields[2::3]):
        off = (b"unset", b"false") if attribute == b"format" else (b"set", b"true")
        if value in off:
            result.add(os.fsdecode(path))
    return result


def read_blobs(oids):
    """Contents of the blobs small enough to format, read with two Git processes."""
    oids = sorted(set(oids))
    if not oids:
        return {}
    request = "".join(oid + "\n" for oid in oids).encode()
    sizes = git("cat-file", "--batch-check", stdin=request).decode().splitlines()
    wanted = [line.split()[0] for line in sizes if int(line.split()[2]) <= MAX_BYTES]
    output = git(
        "cat-file", "--batch", stdin="".join(o + "\n" for o in wanted).encode()
    )
    blobs, position = {}, 0
    for oid in wanted:
        end = output.index(b"\n", position)
        size = int(output[position:end].split()[2])
        blobs[oid] = output[end + 1 : end + 1 + size]
        position = end + 2 + size
    return blobs


def text(data):
    """True for content a formatter can read: nonempty UTF-8 text."""
    if data is None or not data.strip() or b"\0" in data:
        return False
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def cache():
    base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
    return Path(base) / "hypertext-studio"


def provide(name, version, build):
    """Install a pinned tool into the user cache the first time it is needed."""
    home = cache() / "format" / (name + "-" + version)
    if (home / ".ready").exists():
        return home
    home.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=name + "-", dir=home.parent))
    try:
        build(staging)
        (staging / ".ready").touch()
        shutil.rmtree(home, ignore_errors=True)
        os.rename(staging, home)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        shutil.rmtree(staging, ignore_errors=True)
        if (home / ".ready").exists():  # another process finished first
            return home
        raise Unavailable(name + " " + version + " could not be installed") from error
    return home


def install(*command):
    subprocess.run(command, check=True, capture_output=True, timeout=300)


def prettier():
    node, npm = shutil.which("node"), shutil.which("npm")
    if not node or not npm:
        raise Unavailable("Node.js is not installed")
    home = provide(
        "prettier",
        PRETTIER,
        lambda folder: install(
            npm,
            "install",
            "--prefix",
            str(folder),
            "--no-save",
            "--no-package-lock",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
            "--loglevel=error",
            "prettier@" + PRETTIER,
        ),
    )
    script = home / "node_modules/prettier/bin/prettier.cjs"
    return [node, str(script), "--write", "--log-level", "warn", "--"]


def ruff():
    def build(folder):
        install(sys.executable, "-m", "venv", str(folder))
        install(
            str(folder / "bin/python"), "-m", "pip", "install", "-q", "ruff==" + RUFF
        )

    return [str(provide("ruff", RUFF, build) / "bin/ruff"), "format", "--quiet", "--"]


def ktlint():
    java = shutil.which("java")
    if not java:
        raise Unavailable("Java is not installed")

    def build(folder):
        jar = folder / "ktlint.jar"
        earlier = cache() / "formatters" / ("ktlint-" + KTLINT + "-all.jar")
        if earlier.is_file():
            shutil.copyfile(earlier, jar)
        else:
            url = (
                "https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/"
                + KTLINT
                + "/ktlint-cli-"
                + KTLINT
                + "-all.jar"
            )
            with urllib.request.urlopen(url, timeout=120) as response:
                jar.write_bytes(response.read())
        if hashlib.sha256(jar.read_bytes()).hexdigest() != KTLINT_SHA256:
            raise ValueError("ktlint " + KTLINT + " does not match its pinned checksum")

    jar = provide("ktlint", KTLINT, build) / "ktlint.jar"
    # Paths arrive on stdin, so one process formats any number of files.
    return [
        java,
        "-Djava.awt.headless=true",
        "-jar",
        str(jar),
        "--format",
        "--relative",
        "--patterns-from-stdin=",
    ]


def batches(paths):
    """Split paths into argument lists that stay well inside the OS limit."""
    batch, size = [], 0
    for path in paths:
        if batch and size + len(path) + 1 > ARGUMENT_BYTES:
            yield batch
            batch, size = [], 0
        batch.append(path)
        size += len(path) + 1
    if batch:
        yield batch


def ktlint_failures(output):
    """ktlint lists what it cannot fix. Only a file it cannot parse blocks."""
    lines = list(dict.fromkeys(line for line in output.splitlines() if line.strip()))
    unreadable = [line for line in lines if "Not a valid Kotlin file" in line]
    if unreadable or "(cannot be auto-corrected)" in output:
        return unreadable
    raise Unavailable("ktlint did not run: " + (lines[0] if lines else "no output"))


def format_tree(tree, groups):
    """Run each formatter once over its files. Returns (unavailable, failures)."""
    unavailable, failures = [], []
    for tool, paths in sorted(groups.items()):
        try:
            command = {"prettier": prettier, "ruff": ruff, "ktlint": ktlint}[tool]()
            if tool == "ktlint":
                result = subprocess.run(
                    command,
                    cwd=tree,
                    input="\0".join(paths),
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=1800,
                )
                if result.returncode:
                    failures += ktlint_failures(result.stdout + result.stderr)
                continue
            for batch in batches(paths):
                result = subprocess.run(
                    [*command, *batch],
                    cwd=tree,
                    capture_output=True,
                    text=True,
                    errors="replace",
                    timeout=1800,
                )
                if result.returncode:
                    failures.append((result.stderr or result.stdout).strip())
        except Unavailable as error:
            unavailable.append(tool + ": " + str(error))
    return unavailable, failures


def own_prettier_style(tree):
    if any(tree.glob(".prettierrc*")) or any(tree.glob("prettier.config.*")):
        return True
    try:
        return "prettier" in json.loads((tree / "package.json").read_bytes())
    except (OSError, ValueError, TypeError):
        return False


def format_files(root, contents, selected):
    """Format `selected` in a private tree built from `contents`; return the changes."""
    groups = {}
    for path in selected:
        groups.setdefault(claimed(path), []).append(path)
    with tempfile.TemporaryDirectory(prefix="hypertext-format-") as folder:
        tree = Path(folder)
        for path, data in contents.items():
            (tree / path).parent.mkdir(parents=True, exist_ok=True)
            (tree / path).write_bytes(data)
        # Configuration can import the repository's installed packages.
        for directory in {
            tree,
            *(p for path in selected for p in (tree / path).parents),
        }:
            if tree in (directory, *directory.parents):
                modules = root / directory.relative_to(tree) / "node_modules"
                if modules.is_dir() and not (directory / "node_modules").exists():
                    (directory / "node_modules").symlink_to(modules)
        if "prettier" in groups and not own_prettier_style(tree):
            (tree / ".prettierrc.json").write_text(json.dumps(STYLE))
        unavailable, failures = format_tree(tree, groups)
        changed = []
        for path in selected:
            new = (tree / path).read_bytes()
            if new != contents[path]:
                changed.append((path, contents[path], new))
    return unavailable, failures, changed


def replace_file(target, data):
    draft = target.with_name("." + target.name + ".formatting")
    draft.write_bytes(data)
    draft.chmod(target.stat().st_mode & 0o777)
    os.replace(draft, target)


def sync_worktree(root, path, old, new):
    """Bring the working file along. Returns False when unstaged edits overlap."""
    target = root / path
    if target.is_symlink() or not target.is_file():
        return True
    work = target.read_bytes()
    if work == new:
        return True
    if work == old:
        result = new
    elif work.replace(b"\r\n", b"\n") == old:
        result = new.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    else:  # partially staged: carry only the formatting change into the working file
        with tempfile.TemporaryDirectory() as folder:
            files = [Path(folder) / name for name in ("work", "base", "new")]
            for file, data in zip(files, (work, old, new)):
                file.write_bytes(data)
            merged = subprocess.run(
                ["git", "merge-file", "-p", *map(str, files)], capture_output=True
            )
        if merged.returncode:
            return False
        result = merged.stdout
    replace_file(target, result)
    return True


def pending_file():
    return Path(os.fsdecode(git("rev-parse", "--absolute-git-dir")).strip()) / (
        "hypertext-format-pending.json"
    )


def alternate_index():
    """True for `git commit <paths>`, whose hook sees a temporary index."""
    selected = os.environ.get("GIT_INDEX_FILE")
    if not selected:
        return False
    env = {k: v for k, v in os.environ.items() if k != "GIT_INDEX_FILE"}
    real = Path(os.fsdecode(git("rev-parse", "--git-path", "index", env=env)).strip())
    real = real.resolve()
    return Path(selected).resolve() not in (real, real.with_name(real.name + ".lock"))


def working_files(root, paths):
    """Tracked and new files in the working tree, optionally under `paths`."""
    listing = ["ls-files", "-z", "--cached", "--others", "--exclude-standard"]
    everything = names(git(*listing, cwd=root))
    if not paths:
        return everything, everything
    chosen = set(names(git(*listing, "--full-name", "--", *paths)))
    return everything, [p for p in everything if p in chosen]


def run(mode, base=None, paths=()):
    root = Path(os.fsdecode(git("rev-parse", "--show-toplevel")).strip())
    if mode == "fix":
        everything, selected = working_files(root, paths)
    os.chdir(root)

    if mode == "fix":
        regular = {
            p
            for p in everything
            if (root / p).is_file()
            and not (root / p).is_symlink()
            and (root / p).stat().st_size <= MAX_BYTES
        }
        selected = [p for p in selected if p in regular and claimed(p)]
        selected = [p for p in selected if p not in opted_out(selected, cached=False)]
        configs = [p for p in regular if matches(p, CONFIG)]
        contents = {p: (root / p).read_bytes() for p in {*selected, *configs}}
    else:
        index = read_index()
        if mode == "staged":
            pending_file().unlink(missing_ok=True)
            changed = git(
                "diff",
                "--cached",
                "--name-only",
                "--no-renames",
                "-z",
                "--diff-filter=ACMT",
            )
            selected = names(changed)
        elif base:
            changed = git(
                "diff",
                "--name-only",
                "--no-renames",
                "-z",
                "--diff-filter=ACMT",
                base,
                "HEAD",
            )
            selected = names(changed)
        else:
            selected = list(index)
        selected = [
            p
            for p in selected
            if index.get(p, ("",))[0] in ("100644", "100755") and claimed(p)
        ]
        selected = [p for p in selected if p not in opted_out(selected, cached=True)]
        configs = [p for p in index if matches(p, CONFIG) and index[p][0] != "120000"]
        blobs = read_blobs(index[p][1] for p in [*selected, *configs])
        contents = {
            p: blobs[index[p][1]] for p in {*selected, *configs} if index[p][1] in blobs
        }
    selected = [p for p in selected if text(contents.get(p))]
    if not selected:
        return 0

    unavailable, failures, changed = format_files(root, contents, selected)
    for problem in unavailable:
        prefix = "Formatting skipped, " if mode == "staged" else "Not formatted, "
        print(prefix + problem + ".", file=sys.stderr)
    if failures:
        raise Blocked("These files could not be formatted:\n" + "\n".join(failures))
    if mode == "check":
        if changed:
            print("Not formatted:", file=sys.stderr)
            for path, _, _ in changed:
                print("  " + path, file=sys.stderr)
            print("Fix with: ./run format", file=sys.stderr)
        return 2 if unavailable else 1 if changed else 0
    if mode == "fix":
        for path, _, new in changed:
            replace_file(root / path, new)
        if changed:
            print("Formatted " + str(len(changed)) + " file(s).")
        return 2 if unavailable else 0
    if not changed:
        return 0

    rows, record = [], []
    for path, old, new in changed:
        oid = git("hash-object", "-w", "--no-filters", "--stdin", stdin=new)
        oid = oid.decode().strip()
        mode_bits = index[path][0]
        rows.append(
            mode_bits.encode() + b" " + oid.encode() + b"\t" + os.fsencode(path)
        )
        record.append([mode_bits, index[path][1], oid, path])
    if alternate_index():
        pending_file().write_text(json.dumps(record))
    git("update-index", "-z", "--index-info", stdin=b"\0".join(rows) + b"\0")
    kept = [
        path for path, old, new in changed if not sync_worktree(root, path, old, new)
    ]
    print("Formatted " + str(len(changed)) + " file(s).")
    for path in kept:
        print("Left your unstaged edits untouched: " + path)
    return 0


def post_commit():
    """`git commit <paths>` restores its own index afterwards; reapply the formatting."""
    file = pending_file()
    if not file.is_file():
        return 0
    record = json.loads(file.read_text())
    file.unlink()
    index = read_index()
    rows = [
        mode.encode() + b" " + new.encode() + b"\t" + os.fsencode(path)
        for mode, old, new, path in record
        if index.get(path) == (mode, old)
    ]
    if rows:
        git("update-index", "-z", "--index-info", stdin=b"\0".join(rows) + b"\0")
    return 0


def ci_base():
    """In GitHub Actions, the commit a push or pull request is compared against."""
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return None
    target = os.environ.get("GITHUB_BASE_REF")
    if not target:
        try:
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        except (KeyError, OSError, ValueError):
            return None
        target = event.get("before") or ""
        if not target.strip("0"):  # a new branch has no previous commit
            target = (event.get("repository") or {}).get("default_branch")
        if not target:
            return None
    git("fetch", "--quiet", "--depth=1", "origin", target)
    return os.fsdecode(git("rev-parse", "FETCH_HEAD")).strip()


def main(arguments):
    option = arguments[0] if arguments else ""
    try:
        if option == "--post-commit" and len(arguments) == 1:
            return post_commit()
        if option == "--staged" and len(arguments) == 1:
            return run("staged")
        if option == "--check" and len(arguments) <= 2:
            return run("check", arguments[1] if arguments[1:] else ci_base())
        if option in ("-h", "--help"):
            print(__doc__.strip())
            return 0
        paths = arguments[1:] if option == "--" else arguments
        if option == "--" or not any(a.startswith("-") for a in arguments):
            return run("fix", paths=paths)
    except Blocked as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception as error:  # a fault in this tool is not the committer's problem
        print("Formatting skipped, internal error: " + repr(error), file=sys.stderr)
        return 0 if option in ("--staged", "--post-commit") else 2
    print(__doc__.strip(), file=sys.stderr)
    return 64


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
