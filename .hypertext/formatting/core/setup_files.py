"""Bounded local inspection and tamper-evident setup output. Not an OS sandbox."""

import hashlib
import io
import json
import os
import ntpath
from pathlib import Path
import stat
import subprocess
import time
import uuid
from . import runner

GENERATED = {
    ".git",
    "node_modules",
    ".venv",
    "venv",
    "__pycache__",
    "target",
    "build",
    "dist",
    ".next",
    ".gradle",
    ".cache",
    ".turbo",
    ".build",
}
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024


def relative_path(name):
    if not isinstance(name, str) or not name or "\0" in name:
        raise ValueError("Expected a nonempty relative file path")
    if os.name == "nt":
        parts = name.replace("\\", "/").split("/")
        if ntpath.splitdrive(name)[0] or any(
            ":" in p
            or p.endswith((" ", "."))
            or p.split(".")[0].upper()
            in {
                "CON",
                "PRN",
                "AUX",
                "NUL",
                *(f"COM{i}" for i in range(10)),
                *(f"LPT{i}" for i in range(10)),
            }
            for p in parts
        ):
            raise ValueError("Ambiguous Windows file path")
    path = Path(name)
    if path.is_absolute() or ".." in path.parts or path == Path("."):
        raise ValueError("File path must stay inside the selected repo")
    return path


BUNDLE_FILES = (
    "core/__init__.py",
    "core/gate.py",
    "core/runner.py",
    "core/bootstrap.py",
    "core/setup_files.py",
    "core/setup.py",
    "core/repository.py",
    "core/dead_code.py",
    "docs/repository-standards.md",
    "docs/repository-profiles.md",
    "core/doctor.py",
    "core/install.py",
    "core/cli.py",
    "core/terminal.py",
    "core/git_policy.py",
    "core/formatting.py",
    "core/git_setup.py",
    "core/formatter_distribution.py",
    "adapters/formatting/commit.py",
    "adapters/formatting/install.py",
    "adapters/formatting/tools/setup.py",
    "adapters/formatting/tools/run.py",
    "adapters/formatting/tools/prettier.mjs",
    "adapters/formatting/tools/preset.mjs",
    "adapters/formatting/tools/text.py",
    "adapters/formatters/package.json",
    "adapters/formatters/package-lock.json",
    "adapters/formatters/requirements.txt",
    "adapters/formatters/run.py",
    "docs/git-standards.md",
    "docs/product-standards.md",
    "docs/repo-gates.md",
    "policy/key-standards.json",
    "scripts/hypertext.py",
    "adapters/repository/hypertext.py",
    "adapters/repository/AGENTS.md",
    "adapters/repository/github-actions.yml",
    "adapters/generic/AGENTS.md",
    "adapters/claude/CLAUDE.md",
    "adapters/cursor/hypertext.mdc",
    "adapters/copilot/hypertext.instructions.md",
    "action.yml",
    "LICENSE",
    "NOTICE.md",
    "policy/android-sources/manifest.json",
)


def bundle_files(root):
    from . import gate

    manifest = gate.read(Path(root) / "policy/android-sources/manifest.json")
    names = [row["file"] for row in manifest["sources"]]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate Android source filename")
    for name in names:
        path = relative_path(name)
        if len(path.parts) != 1 or path.suffix != ".json" or name == "manifest.json":
            raise ValueError("Invalid Android source filename")
    return (*BUNDLE_FILES, *("policy/android-sources/" + name for name in names))


def confined(path, root):
    path, root = Path(path).absolute(), Path(root).absolute()
    if not path.is_relative_to(root):
        raise ValueError("Path escapes the selected directory")
    for part in (path, *path.parents):
        if part == root:
            break
        if part.is_symlink() or (
            os.name == "nt"
            and part.exists()
            and part.lstat().st_file_attributes & 0x400
        ):
            raise ValueError(f"Symlinked path: {path.relative_to(root)}")
    return path


def regular(path, root, limit=MAX_FILE):
    path = confined(path, root)
    info = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
        raise ValueError(f"Not a bounded regular file: {path.relative_to(root)}")
    return info


def windows_security(path):
    """Fingerprint owner, group and DACL without exporting identities or permissions."""
    import ctypes as c
    from ctypes import wintypes as w

    api = c.WinDLL("advapi32", use_last_error=True)
    api.GetFileSecurityW.argtypes = [
        w.LPCWSTR,
        w.DWORD,
        c.c_void_p,
        w.DWORD,
        c.POINTER(w.DWORD),
    ]
    needed = w.DWORD()
    api.GetFileSecurityW(str(path), 7, None, 0, c.byref(needed))
    if not 0 < needed.value <= 1024 * 1024:
        raise ValueError("Cannot inspect bounded Windows security descriptor")
    buffer = c.create_string_buffer(needed.value)
    if not api.GetFileSecurityW(str(path), 7, buffer, needed, c.byref(needed)):
        raise c.WinError(c.get_last_error())
    return hashlib.sha256(buffer.raw[: needed.value]).hexdigest()


def windows_streams(path, limit=MAX_FILE):
    """Bound named $DATA streams; the unnamed stream is hashed separately."""
    import ctypes as c
    from ctypes import wintypes as w

    api = c.WinDLL("kernel32", use_last_error=True)

    class Stream(c.Structure):
        _fields_ = [("size", c.c_int64), ("name", w.WCHAR * 296)]

    api.FindFirstStreamW.argtypes = [w.LPCWSTR, c.c_int, c.c_void_p, w.DWORD]
    api.FindFirstStreamW.restype = w.HANDLE
    api.FindNextStreamW.argtypes = [w.HANDLE, c.c_void_p]
    api.FindClose.argtypes = [w.HANDLE]
    entry = Stream()
    handle = api.FindFirstStreamW(str(path), 0, c.byref(entry), 0)
    if handle == c.c_void_p(-1).value:
        error = c.get_last_error()
        if error == 38:
            return ()
        raise c.WinError(error)
    rows = []
    total = 0
    try:
        while True:
            if entry.name != "::$DATA":
                if len(rows) >= 64 or entry.size < 0 or total + entry.size > limit:
                    raise ValueError("Windows named streams exceed inspection limit")
                content = hashlib.sha256()
                count = 0
                with open(str(path) + entry.name, "rb") as source:
                    for chunk in iter(lambda: source.read(65536), b""):
                        count += len(chunk)
                        if total + count > limit:
                            raise ValueError(
                                "Windows stream grew beyond inspection limit"
                            )
                        content.update(chunk)
                total += count
                rows.append((entry.name, count, content.hexdigest()))
            if not api.FindNextStreamW(handle, c.byref(entry)):
                if c.get_last_error() != 38:
                    raise c.WinError(c.get_last_error())
                break
    finally:
        api.FindClose(handle)
    return tuple(sorted(rows))


def windows_metadata(path, info=None):
    info = path.lstat() if info is None else info
    if (
        info.st_file_attributes & 0x400
        or stat.S_ISREG(info.st_mode)
        and info.st_nlink != 1
    ):
        raise ValueError("Reparse points and hardlinked protected files are unsafe")
    return (
        info.st_dev,
        info.st_ino,
        info.st_file_attributes,
        windows_security(path),
        windows_streams(
            path, MAX_FILE - info.st_size if stat.S_ISREG(info.st_mode) else MAX_FILE
        ),
    )


def trusted_tool(repo, name):
    """Read-only inspection must not run checkout-owned or CWD-discovered tools."""
    import shutil

    directories = []
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        path = Path(entry)
        if not entry or not path.is_absolute():
            continue
        path = path.resolve()
        if path.is_relative_to(repo.resolve()):
            continue
        directories.append(str(path))
    if os.name == "nt":
        for directory in directories:
            candidate = Path(directory) / (name + ".exe")
            if candidate.is_file() and not candidate.resolve().is_relative_to(
                repo.resolve()
            ):
                return str(candidate.resolve())
        raise FileNotFoundError(
            "Trusted executable unavailable outside the selected checkout"
        )
    candidate = shutil.which(name, path=os.pathsep.join(directories))
    if not candidate or Path(candidate).resolve().is_relative_to(repo.resolve()):
        raise FileNotFoundError(
            "Trusted executable unavailable outside the selected checkout"
        )
    return str(Path(candidate).resolve())


def trusted_git(repo):
    return trusted_tool(repo, "git")


def fingerprint(path, root):
    info = regular(path, root)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        size = 0
        for chunk in iter(lambda: source.read(65536), b""):
            size += len(chunk)
            if size > MAX_FILE:
                raise ValueError("File grew beyond inspection limit")
            digest.update(chunk)
    return (
        (stat.S_IMODE(info.st_mode), digest.hexdigest(), windows_metadata(path, info))
        if os.name == "nt"
        else (stat.S_IMODE(info.st_mode), digest.hexdigest())
    )


def git_read(repo, *args):
    """Bound Git reads and disable checkout-configured executable filters, including submodules."""
    deadline = time.monotonic() + 5
    try:
        executable = trusted_git(repo)
        environment = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        base = [
            executable,
            "--no-optional-locks",
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.untrackedCache=false",
        ]

        def invoke(directory, options):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Git inspection budget exhausted")
            stream = io.BytesIO()
            code = runner.execute(
                [*base, "-C", str(directory), *options],
                directory,
                stream,
                remaining,
                environment=environment,
            )
            return code, stream.getvalue()

        overrides = []
        if "status" in args:
            pending = [Path(repo)]
            seen = set()
            filters = set()
            while pending:
                directory = pending.pop()
                if directory in seen:
                    continue
                seen.add(directory)
                if len(seen) > 64:
                    raise ValueError("Git submodule inspection exceeds 64 checkouts")
                code, keys = invoke(
                    directory,
                    [
                        "config",
                        "--null",
                        "--name-only",
                        "--get-regexp",
                        r"^filter\..*\.(clean|process|smudge|required)$",
                    ],
                )
                if code not in (0, 1):
                    return 1, b""
                filters.update(
                    key.rsplit(b".", 1)[0].decode("utf-8")
                    for key in keys.split(b"\0")
                    if key
                )
                if len(filters) > 256 or any(len(key) > 256 for key in filters):
                    raise ValueError("Git filter inspection exceeds limit")
                code, index = invoke(directory, ["ls-files", "--stage", "-z"])
                if code:
                    return 1, b""
                for row in index.split(b"\0"):
                    if row.startswith(b"160000 ") and b"\t" in row:
                        child = confined(
                            directory
                            / relative_path(os.fsdecode(row.split(b"\t", 1)[1])),
                            Path(repo),
                        )
                        if child.is_dir():
                            pending.append(child)
            for key in sorted(filters):
                for suffix, value in (
                    ("clean", ""),
                    ("process", ""),
                    ("smudge", ""),
                    ("required", "false"),
                ):
                    overrides.extend(["-c", key + "." + suffix + "=" + value])
        return invoke(repo, [*overrides, *args])
    except (OSError, ValueError, TimeoutError, subprocess.TimeoutExpired):
        return 1, b""


def git_state(repo):
    code, output = git_read(repo, "rev-parse", "--show-toplevel")
    if code:
        present = (repo / ".git").exists() or (repo / ".git").is_symlink()
        return {
            "state": "unknown" if present else "not_a_git_repo",
            "tracked": [],
            "head": None,
            "index": None,
        }
    code, output = git_read(repo, "ls-files", "--stage", "-z")
    if code:
        raise ValueError("Cannot inspect the Git index")
    tracked = [
        os.fsdecode(entry.split(b"\t", 1)[1])
        for entry in output.split(b"\0")
        if b"\t" in entry
    ]
    _, head = git_read(repo, "rev-parse", "--verify", "HEAD")
    code, dirty = git_read(
        repo, "status", "--porcelain", "--untracked-files=normal", "--", "."
    )
    _, common = git_read(repo, "rev-parse", "--git-common-dir")
    config_path = repo / Path(os.fsdecode(common).strip()) / "config"
    if config_path.is_file() and config_path.stat().st_size > 1024 * 1024:
        raise ValueError("Git configuration exceeds inspection limit")
    config = (
        hashlib.sha256(config_path.read_bytes()).hexdigest()
        if config_path.is_file()
        else None
    )
    index_code, index_name = git_read(repo, "rev-parse", "--git-path", "index")
    if index_code:
        raise ValueError("Cannot locate the protected Git index")
    index_path = repo / Path(os.fsdecode(index_name).strip())
    index_identity = None
    if index_path.exists():
        info = regular(index_path, index_path.parent)
        index_identity = (
            info.st_dev,
            info.st_ino,
            fingerprint(index_path, index_path.parent),
        )
    output += json.dumps(index_identity, sort_keys=True).encode()
    if os.name == "nt" and (repo / ".git").exists():
        config = hashlib.sha256(
            json.dumps(
                (config, windows_metadata(repo / ".git")), sort_keys=True
            ).encode()
        ).hexdigest()
    return {
        "state": "unknown" if code else "dirty" if dirty else "clean",
        "tracked": tracked,
        "config": config,
        "head": hashlib.sha256(head).hexdigest(),
        "index": hashlib.sha256(output).hexdigest(),
    }


def inventory(repo):
    """Protect existing source, including Git-tracked files in generated directories."""
    git = git_state(repo)
    if git["state"] == "unknown":
        raise ValueError(
            "Cannot prove Git ownership while repository inspection is unavailable"
        )
    tracked = set(git["tracked"])
    tracked_names = (
        {windows_name(name) for name in tracked} if os.name == "nt" else tracked
    )
    result, size, visited = {}, 0, 0
    if os.name == "nt":
        result["\0root"] = windows_metadata(repo)
        size = sum(row[1] for row in result["\0root"][-1])
    for directory, folders, files in os.walk(
        repo, followlinks=False, onerror=lambda error: (_ for _ in ()).throw(error)
    ):
        parent = Path(directory)
        visited += 1
        if visited > 10000:
            raise ValueError("Source inspection exceeds 10000 entries")
        if parent != repo and ".git" in [*folders, *files]:
            nested = git_state(parent)
            result["\0git:" + parent.relative_to(repo).as_posix()] = (
                nested["head"],
                nested["index"],
                nested.get("config"),
            )
        folders[:] = sorted(
            name
            for name in folders
            if name != ".git"
            and (
                name not in GENERATED
                or any(
                    p.startswith(
                        (
                            windows_name((parent / name).relative_to(repo).as_posix())
                            if os.name == "nt"
                            else (parent / name).relative_to(repo).as_posix()
                        )
                        + "/"
                    )
                    for p in tracked_names
                )
            )
        )
        for name in [*folders, *files]:
            path = parent / name
            relative = path.relative_to(repo).as_posix()
            if (
                any(part in GENERATED for part in path.relative_to(repo).parts)
                and (windows_name(relative) if os.name == "nt" else relative)
                not in tracked_names
            ):
                continue
            info = path.lstat()
            if os.name == "nt" and info.st_file_attributes & 0x400:
                raise ValueError("Reparse point in source inventory: " + relative)
            if stat.S_ISDIR(info.st_mode):
                if os.name == "nt" or adoption_path(relative):
                    result[relative] = (
                        ("directory", windows_metadata(path, info))
                        if os.name == "nt"
                        else ("directory", stat.S_IMODE(info.st_mode))
                    )
                    if os.name == "nt":
                        size += sum(row[1] for row in result[relative][1][-1])
                        if size > MAX_TOTAL:
                            raise ValueError(
                                "Source streams exceed total inspection limit"
                            )
                continue
            visited += 1
            if visited > 10000:
                raise ValueError(
                    "Source inspection exceeds 10000 files; select a narrower repo or isolate generated files"
                )
            if stat.S_ISLNK(info.st_mode):
                result[relative] = ("symlink", os.readlink(path))
            elif stat.S_ISREG(info.st_mode):
                size += info.st_size
                if size > MAX_TOTAL:
                    raise ValueError("Source inspection exceeds 256 MiB")
                result[relative] = fingerprint(path, repo)
                if os.name == "nt":
                    size += sum(row[1] for row in result[relative][2][-1])
                    if size > MAX_TOTAL:
                        raise ValueError("Source streams exceed total inspection limit")
            else:
                result[relative] = ("special", stat.S_IFMT(info.st_mode))
    for name in tracked:
        if not (repo / name).exists() and not (repo / name).is_symlink():
            result[name] = ("missing",)
    result["\0git"] = (git["head"], git["index"], git.get("config"))
    if os.name == "nt":
        normalized = {}
        for name, value in result.items():
            key = windows_name(name)
            if key in normalized:
                raise ValueError("Ambiguous case-sensitive Windows source paths")
            normalized[key] = value
        return normalized
    return result


def adoption_path(name):
    name = name.replace("\\", "/").casefold()
    return (
        name == ".hypertext"
        or name.startswith(".hypertext/")
        or name in {"scripts/hypertext.py", ".github/workflows/hypertext-standards.yml"}
    )


def windows_name(name):
    # Unicode casefold merges distinct NTFS names (for example ß and ss).
    # Normalize ordinary Windows aliases; unusual case aliases may fail closed.
    return name.replace("\\", "/").translate(
        str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")
    )


def changes(before, after, writes):
    if os.name == "nt":
        before = {windows_name(k): v for k, v in before.items()}
        after = {windows_name(k): v for k, v in after.items()}
        writes = {windows_name(n) for n in writes}
    changed = {name for name in before if after.get(name) != before[name]}
    changed |= {name for name in set(after) - set(before) if adoption_path(name)}
    return sorted(
        name.replace("\0git", "[Git metadata]").replace("\0root", "[Repo directory]")
        for name in changed
        if name not in writes or adoption_path(name)
    )


class WindowsOutput:
    """Pin local directory ancestry without delete sharing; owner-only NTFS ACLs."""

    def __init__(self, path):
        import ctypes as c
        from ctypes import wintypes as w

        self.c, self.handles, self.acls, self.identities = c, [], {}, {}
        self.k = c.WinDLL("kernel32", use_last_error=True)
        self.a = c.WinDLL("advapi32", use_last_error=True)
        self.k.CreateFileW.argtypes = [
            w.LPCWSTR,
            w.DWORD,
            w.DWORD,
            c.c_void_p,
            w.DWORD,
            w.DWORD,
            w.HANDLE,
        ]
        self.k.CreateFileW.restype = w.HANDLE
        self.k.CloseHandle.argtypes = [w.HANDLE]
        self.k.GetCurrentProcess.restype = w.HANDLE
        self.k.LocalFree.argtypes = [c.c_void_p]
        self.k.CreateDirectoryW.argtypes = [w.LPCWSTR, c.c_void_p]
        self.a.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, c.POINTER(w.HANDLE)]
        self.a.GetTokenInformation.argtypes = [
            w.HANDLE,
            c.c_int,
            c.c_void_p,
            w.DWORD,
            c.POINTER(w.DWORD),
        ]
        self.a.ConvertSidToStringSidW.argtypes = [c.c_void_p, c.POINTER(w.LPWSTR)]
        self.a.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [
            w.LPCWSTR,
            w.DWORD,
            c.POINTER(c.c_void_p),
            c.c_void_p,
        ]
        self.a.GetFileSecurityW.argtypes = [
            w.LPCWSTR,
            w.DWORD,
            c.c_void_p,
            w.DWORD,
            c.POINTER(w.DWORD),
        ]

        class Security(c.Structure):
            _fields_ = [
                ("length", w.DWORD),
                ("descriptor", c.c_void_p),
                ("inherit", w.BOOL),
            ]

        self.descriptor = c.c_void_p()
        token = w.HANDLE()
        needed = w.DWORD()
        sid = w.LPWSTR()
        self.check(
            self.a.OpenProcessToken(self.k.GetCurrentProcess(), 8, c.byref(token))
        )
        try:
            self.a.GetTokenInformation(token, 1, None, 0, c.byref(needed))
            buffer = c.create_string_buffer(needed.value)
            self.check(
                self.a.GetTokenInformation(token, 1, buffer, needed, c.byref(needed))
            )
            self.check(
                self.a.ConvertSidToStringSidW(
                    c.cast(buffer, c.POINTER(c.c_void_p))[0], c.byref(sid)
                )
            )
            try:
                sddl = "D:P(A;OICI;FA;;;" + sid.value + ")(A;OICI;FA;;;SY)"
                self.check(
                    self.a.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                        sddl, 1, c.byref(self.descriptor), None
                    )
                )
            finally:
                self.k.LocalFree(sid)
        finally:
            self.k.CloseHandle(token)
        self.security = Security(c.sizeof(Security), self.descriptor, False)
        try:
            path = path.absolute()
            if path.drive.startswith("\\\\") or not path.drive:
                raise ValueError(
                    "Windows output requires a local drive with ACL support"
                )
            # Pin root first, then every ancestor before creating the next component.
            for directory in [*reversed(path.parents), path]:
                if directory == path or not directory.exists():
                    self.check(
                        self.k.CreateDirectoryW(str(directory), c.byref(self.security))
                    )
                # Attribute-only handles do not enforce Windows sharing restrictions.
                # Ancestors retain a pinned child, so cannot become empty junctions.
                # Permit ordinary temp-file writes there; lock writes on the output itself.
                share = 1 if directory == path else 3
                handle = self.k.CreateFileW(
                    str(directory), 0x80000000, share, None, 3, 0x02200000, None
                )
                if handle == c.c_void_p(-1).value:
                    raise c.WinError(c.get_last_error())
                self.handles.append(handle)
                if directory.lstat().st_file_attributes & 0x400:
                    raise ValueError("Reparse points are unsafe output ancestors")
            self.path = path
            self.directory_acl = self.acl(path)
            # Filesystems without persistent ACLs cannot provide private evidence.
            flags = w.DWORD()
            self.k.GetVolumeInformationW.argtypes = [
                w.LPCWSTR,
                w.LPWSTR,
                w.DWORD,
                c.c_void_p,
                c.c_void_p,
                c.POINTER(w.DWORD),
                w.LPWSTR,
                w.DWORD,
            ]
            self.check(
                self.k.GetVolumeInformationW(
                    path.anchor, None, 0, None, None, c.byref(flags), None, 0
                )
            )
            if not flags.value & 8:
                raise ValueError("Windows output volume must support persistent ACLs")
        except BaseException:
            self.close()
            raise

    def check(self, result):
        if not result:
            raise self.c.WinError(self.c.get_last_error())

    def acl(self, path):
        return windows_security(path)

    def verify(self):
        if self.acl(self.path) != self.directory_acl:
            raise ValueError("Setup output directory permissions were modified")
        if windows_streams(self.path):
            raise ValueError("Unexpected output directory streams")
        for name, acl in self.acls.items():
            info = (self.path / name).lstat()
            if (
                (info.st_dev, info.st_ino, info.st_file_attributes)
                != self.identities[name]
                or info.st_nlink != 1
                or windows_streams(self.path / name)
            ):
                raise ValueError("Setup output identity or streams were modified")
            if self.acl(self.path / name) != acl:
                raise ValueError("Setup output file permissions were modified")

    def open(self, name):
        import msvcrt

        c = self.c
        handle = self.k.CreateFileW(
            str(self.path / name),
            0xC0000000,
            1,
            c.byref(self.security),
            1,
            0x00200080,
            None,
        )
        if handle == c.c_void_p(-1).value:
            raise c.WinError(c.get_last_error())
        try:
            descriptor = msvcrt.open_osfhandle(handle, os.O_RDWR | os.O_BINARY)
        except BaseException:
            self.k.CloseHandle(handle)
            raise
        try:
            self.acls[name] = self.acl(self.path / name)
            info = (self.path / name).lstat()
            self.identities[name] = (info.st_dev, info.st_ino, info.st_file_attributes)
            return os.fdopen(descriptor, "w+b")
        except BaseException:
            os.close(descriptor)
            raise

    def replace(self, name, target, replace=True):
        # MoveFileEx opens the parent for writing, conflicting with our pin.
        # Native same-directory rename uses the source handle without reopening its parent.
        from ctypes import wintypes as w

        c = self.c

        class Rename(c.Structure):
            _fields_ = [
                ("replace", w.DWORD),
                ("root", w.HANDLE),
                ("length", w.DWORD),
                ("name", w.WCHAR * (len(target) + 1)),
            ]

        info = Rename(int(replace), None, len(target.encode("utf-16-le")), target)
        native = c.WinDLL("ntdll")

        class Status(c.Structure):
            _fields_ = [("status", c.c_void_p), ("information", c.c_size_t)]

        native.NtSetInformationFile.argtypes = [
            w.HANDLE,
            c.c_void_p,
            c.c_void_p,
            w.DWORD,
            c.c_int,
        ]
        native.NtSetInformationFile.restype = c.c_long
        native.RtlNtStatusToDosError.argtypes = [c.c_long]
        native.RtlNtStatusToDosError.restype = w.DWORD
        handle = self.k.CreateFileW(
            str(self.path / name), 0x80010000, 1, None, 3, 0x00200000, None
        )
        if handle == c.c_void_p(-1).value:
            raise c.WinError(c.get_last_error())
        try:
            status = Status()
            result = native.NtSetInformationFile(
                handle, c.byref(status), c.byref(info), c.sizeof(info), 10
            )
            if result < 0:
                raise c.WinError(native.RtlNtStatusToDosError(result))
        finally:
            self.k.CloseHandle(handle)
        self.acls[target] = self.acls.pop(name)
        self.identities[target] = self.identities.pop(name)

    def close(self):
        for handle in reversed(self.handles):
            self.k.CloseHandle(handle)
        self.handles.clear()
        if self.descriptor:
            self.k.LocalFree(self.descriptor)
            self.descriptor = None


class Output:
    """Exclusive logs and anchored atomic reports; commands cannot redirect our writes."""

    def __init__(self, path):
        self.windows = None
        if os.name not in {"posix", "nt"}:
            raise ValueError("Host has no safe output backend")
        self.path = path
        if os.name == "nt":
            self.windows = WindowsOutput(path)
        else:
            path.mkdir(parents=True, exist_ok=False)
        self.identity = (path.stat().st_dev, path.stat().st_ino)
        self.fd = (
            os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            if os.name == "posix"
            else None
        )
        self.ledger = {}

    def close(self):
        if self.windows is not None:
            self.windows.close()
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def identity_check(self):
        if (
            self.path.is_symlink()
            or not self.path.is_dir()
            or (self.path.stat().st_dev, self.path.stat().st_ino) != self.identity
        ):
            raise ValueError(
                "Setup output directory was replaced; no further writes are safe"
            )

    def verify(self):
        self.identity_check()
        if self.windows is not None:
            self.windows.verify()
        actual = {p.name for p in self.path.iterdir()}
        if actual != set(self.ledger):
            raise ValueError("Unexpected or missing files in setup output")
        for name, digest in self.ledger.items():
            mode, actual_digest = fingerprint(self.path / name, self.path)[:2]
            if actual_digest != digest or os.name == "posix" and mode != 0o600:
                raise ValueError(f"Setup output was modified: {name}")

    def open(self, name):
        relative_path(name)
        if Path(name).name != name or name in {".", ".."}:
            raise ValueError("Output name must be a single file name")
        if self.windows is not None:
            return self.windows.open(name)
        flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(
            name if self.fd is not None else self.path / name,
            flags,
            0o600,
            **({"dir_fd": self.fd} if self.fd is not None else {}),
        )
        return os.fdopen(descriptor, "w+b")

    def log(self, name):
        self.verify()
        return Log(self, name)

    def quarantine(self, name="results.json"):
        """Preserve suspect published bytes under an invalid name without overwriting them."""
        self.identity_check()
        if name not in self.ledger or not (self.path / name).exists():
            return
        regular(self.path / name, self.path)
        target = "invalid-" + uuid.uuid4().hex + ".json"
        if self.windows is not None:
            self.windows.replace(name, target, replace=False)
        else:
            os.link(
                name,
                target,
                src_dir_fd=self.fd,
                dst_dir_fd=self.fd,
                follow_symlinks=False,
            )
            os.unlink(name, dir_fd=self.fd)
        if name in self.ledger:
            self.ledger[target] = self.ledger.pop(name)

    def save(self, data, target="results.json"):
        relative_path(target)
        if Path(target).name != target:
            raise ValueError("Report name must be a single file name")
        self.verify()
        name = ".report-" + uuid.uuid4().hex
        encoded = (json.dumps(data, indent=2) + "\n").encode("utf-8")
        with self.open(name) as stream:
            stream.write(encoded)
        # Use the original directory descriptor even if its pathname was swapped.
        kwargs = (
            {"src_dir_fd": self.fd, "dst_dir_fd": self.fd}
            if self.fd is not None
            else {}
        )
        if self.windows is not None:
            self.windows.replace(name, target)
        else:
            os.replace(name, target, **kwargs)
        self.ledger[target] = hashlib.sha256(encoded).hexdigest()
        self.verify()


class Log:
    def __init__(self, output, name):
        self.output, self.name = output, name
        self.stream = output.open(name)
        info = os.fstat(self.stream.fileno())
        self.identity = (info.st_dev, info.st_ino)
        self.digest = hashlib.sha256()

    def write(self, content):
        self.stream.write(content)
        self.digest.update(content)

    def __enter__(self):
        return self

    def __exit__(self, *error):
        self.stream.close()
        self.output.identity_check()
        path = self.output.path / self.name
        info = regular(path, self.output.path, 16 * 1024 * 1024)
        if (info.st_dev, info.st_ino) != self.identity:
            raise ValueError("Setup command log was replaced")
        self.output.ledger[self.name] = self.digest.hexdigest()
        self.output.verify()
