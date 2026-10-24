"""Native backup payloads; dependencies are supplied by the entry point."""

import copy
import hashlib
import io
import tarfile


def is_user_content_path(h, path):
    path = path.decode() if isinstance(path, bytes) else path
    return any(path == root or path.startswith(root) for root in h.USER_CONTENT_PATHS)


def contains_user_content(h, files):
    return any(h.is_user_content_path(path) for path in files)


def contains_media_files(h, files):
    media_roots = (
        "files/media/",
        "files/user_media/",
        "files/audio_notes/",
        "files/camera-captures/",
    )
    return any(
        (path.decode() if isinstance(path, bytes) else path)
        .removeprefix("./")
        .startswith(media_roots)
        for path in files
    )


def verify_restored_snapshot(
    h, restored_files, source_files, *, credentials_only, allow_database_changes=False
):
    """Validate durable restore contents and report the intended acceptance failure."""
    database_path = "databases/logdate"
    database = restored_files.get(database_path)
    if credentials_only:
        # Application startup can create an empty SQLite database on a fresh installation. The
        # instrumentation probe verifies it contains no source journals or notes; the tar filter
        # separately proves no database entry was transported.
        if restored_files.get("files/db_recovery/passphrase_backup") is None:
            raise RuntimeError(
                "credentials-only restore omitted the journal recovery key"
            )
        identity_files = (
            "files/identity_recovery/phrase_backup",
            "files/identity_recovery/account_key_backup",
        )
        if not any(restored_files.get(path) is not None for path in identity_files):
            raise RuntimeError(
                "credentials-only restore omitted portable identity material"
            )
        if restored_files.get("files/identity_recovery/media_key_backup") is None:
            raise RuntimeError(
                "credentials-only restore omitted the media recovery key"
            )
        if h.contains_media_files(restored_files):
            raise RuntimeError(
                "credentials-only restore unexpectedly delivered media data"
            )
        return

    if database is None:
        raise RuntimeError(
            "Android completed restore without delivering the journal database"
        )
    source_database = source_files.get(database_path)
    if source_database is None:
        raise RuntimeError("synthetic source snapshot is missing the journal database")
    if not allow_database_changes and database["sha256"] != source_database["sha256"]:
        raise RuntimeError(
            "Android completed restore, but the destination database differs before app startup"
        )


def verify_missing_keys_snapshot(h, restored_files):
    """An invalid selected bundle must leave no source journal or portable recovery material."""
    if "files/logdate.preferences_pb" not in restored_files:
        raise RuntimeError("missing-key restore did not deliver the source preferences")
    rejected_roots = (
        "databases/logdate",
        "files/db_recovery/",
        "files/identity_recovery/",
        "files/native_journal_transfer/",
    )
    if any(path.startswith(rejected_roots) for path in restored_files):
        raise RuntimeError(
            "missing-key restore exposed an incomplete journal or portable recovery key"
        )
    if h.contains_media_files(restored_files):
        raise RuntimeError(
            "missing-key restore exposed original media without its recovery keys"
        )


def without_portable_recovery_keys(h, payload):
    """Remove portable keys from LogDate's nested full backup, retaining the transport set."""
    return h.rewrite_native_full_backup(
        payload, h.without_portable_recovery_keys_from_full_backup
    )


def credentials_only(h, payload):
    """Keep LogDate's portable recovery keys while removing journal and media data."""
    return h.rewrite_native_full_backup(payload, h.credentials_only_from_full_backup)


def without_privacy_metadata(h, payload):
    """Retain owner, drafts, keys, and content while omitting only the original privacy policy."""
    preference_path = f"apps/{h.PACKAGE}/f/logdate.preferences_pb"

    def remove_policy(full_backup):
        with tarfile.open(fileobj=io.BytesIO(full_backup), mode="r:") as archive:
            preferences = archive.extractfile(preference_path)
            if preferences is None:
                raise RuntimeError("native fixture is missing privacy metadata")
            original = preferences.read()
            h.canonical_preferences(
                original
            )  # Validate without dropping installation-local wire fields.
            filtered = b"".join(
                encoded
                for _, _, encoded, entry in h.protobuf_fields(original)
                if next(
                    value.decode()
                    for number, wire, _, value in h.protobuf_fields(entry)
                    if number == 1 and wire == 2
                )
                not in {"is_onboarded", "security_level"}
            )
            if filtered == original:
                raise RuntimeError("native fixture never contained privacy metadata")
            paths = {member.name for member in archive.getmembers()}
            if any(
                name.startswith(f"apps/{h.PACKAGE}/f/native_journal_transfer/")
                for name in paths
            ):
                raise RuntimeError(
                    "Missing privacy requires cloud selection without an authenticated journal snapshot"
                )
        return h.rewrite_app_backup(full_backup, paths, {preference_path: filtered})

    return h.rewrite_native_full_backup(payload, remove_policy)


def without_one_native_media_file(h, payload):
    """Remove one original from the nested Android full backup and return its app path."""
    removed = []
    prefix = f"apps/{h.PACKAGE}/f/"

    def remove_one(full_backup):
        with tarfile.open(fileobj=io.BytesIO(full_backup), mode="r:") as archive:
            media = next(
                (
                    member
                    for member in archive.getmembers()
                    if member.isfile()
                    and member.name.startswith(prefix + "media/objects/")
                    and member.name.lower().endswith(".png")
                ),
                None,
            )
        if media is None:
            raise RuntimeError(
                "native source backup did not contain the PNG original used by the image note"
            )
        start = media.offset
        end = media.offset_data + ((media.size + 511) // 512) * 512
        removed.append("files/" + media.name.removeprefix(prefix))
        return full_backup[:start] + full_backup[end:]

    rewritten = h.rewrite_native_full_backup(payload, remove_one)
    if len(removed) != 1:
        raise RuntimeError(
            "native partial-media fixture did not remove exactly one original"
        )
    return rewritten, removed[0]


def rewrite_native_full_backup(h, payload, transform):
    """Replace the nested app archive while preserving Android's legacy outer tar headers."""
    full_backup_name = f"1/_full/{h.PACKAGE}"
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as source:
        item = next(
            (
                member
                for member in source.getmembers()
                if member.name == full_backup_name
            ),
            None,
        )
        if item is None:
            raise RuntimeError(
                "native transport did not contain the LogDate full backup"
            )
        full_backup = source.extractfile(item)
        if full_backup is None:
            raise RuntimeError(
                "native transport did not contain the LogDate full backup"
            )
        filtered_full_backup = transform(full_backup.read())

    # Keep the legacy outer transport headers intact; Android parses these as backup records.
    header = bytearray(payload[item.offset : item.offset + 512])
    header[124:136] = f"{len(filtered_full_backup):011o}\0".encode()
    header[148:156] = b"        "
    header[148:156] = f"{sum(header):06o}\0 ".encode()
    old_payload_end = item.offset_data + ((item.size + 511) // 512) * 512
    padding = b"\0" * ((-len(filtered_full_backup)) % 512)
    return (
        payload[: item.offset]
        + bytes(header)
        + filtered_full_backup
        + padding
        + payload[old_payload_end:]
    )


def selected_database_snapshot(h, payload, *, cloud_backup=False):
    """Hash the journal file selected by the backup agent, not the live WAL database."""
    selected_database = f"apps/{h.PACKAGE}/" + (
        "db/logdate"
        if cloud_backup
        else "f/native_journal_transfer/current/database/logdate"
    )
    contents = h.selected_backup_contents(
        payload, selected_database, description="selected journal database"
    )
    return {"bytes": len(contents), "sha256": hashlib.sha256(contents).hexdigest()}


def selected_backup_contents(
    h, payload, selected_path, *, description="selected backup file"
):
    """Read the exact selected file from Android's nested immutable source archive."""
    full_backup_name = f"1/_full/{h.PACKAGE}"
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as transport:
        full_backup_item = next(
            (
                item
                for item in transport.getmembers()
                if item.name == full_backup_name and item.isfile()
            ),
            None,
        )
        full_backup = (
            transport.extractfile(full_backup_item) if full_backup_item else None
        )
        if full_backup is None:
            raise RuntimeError(
                "native transport did not contain the LogDate full backup"
            )
        with tarfile.open(
            fileobj=io.BytesIO(full_backup.read()), mode="r:"
        ) as app_backup:
            database_item = next(
                (
                    item
                    for item in app_backup.getmembers()
                    if item.name == selected_path and item.isfile()
                ),
                None,
            )
            database = app_backup.extractfile(database_item) if database_item else None
            if database is None:
                raise RuntimeError(
                    "native transport did not contain the " + description
                )
            return database.read()


def verify_privacy_repair_snapshot(
    h, before, after, before_preferences, after_preferences, source_preferences
):
    """A late policy repair preserves the occupied journal and changes exactly two fields."""
    policy_names = {"is_onboarded", "security_level"}
    h.assert_snapshot_preserved(
        before, after, ignored_paths={"files/logdate.preferences_pb"}
    )
    changes = h.preference_changes(before_preferences, after_preferences)
    if changes != {"added": sorted(policy_names), "removed": [], "changed": []}:
        raise RuntimeError(
            "privacy repair changed fields beyond the two missing policy values"
        )
    if h.canonical_preferences(
        after_preferences, allowed_names=policy_names
    ) != h.canonical_preferences(source_preferences, allowed_names=policy_names):
        raise RuntimeError(
            "privacy repair did not restore the authenticated source policy"
        )


def assert_cloud_backup_contents(h, payload):
    """Cloud delivery must preserve owner/preferences and the journal's portable recovery keys."""
    full_backup_name = f"1/_full/{h.PACKAGE}"
    file_prefix = f"apps/{h.PACKAGE}/f/"
    required = {
        f"apps/{h.PACKAGE}/db/logdate",
        file_prefix + "db_recovery/passphrase_backup",
        file_prefix + "logdate.preferences_pb",
        file_prefix + "identity_recovery/media_key_backup",
    }
    identity_keys = {
        file_prefix + "identity_recovery/" + name
        for name in ("phrase_backup", "account_key_backup")
    }
    excluded_roots = (
        file_prefix + "native_journal_transfer/current/",
        file_prefix + "retained_journals/",
        file_prefix + "archive_recovery/sources/",
        *(
            file_prefix + path
            for path in ("media/", "user_media/", "audio_notes/", "camera-captures/")
        ),
    )
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as transport:
        full_backup_item = next(
            (
                item
                for item in transport.getmembers()
                if item.name == full_backup_name and item.isfile()
            ),
            None,
        )
        full_backup = (
            transport.extractfile(full_backup_item) if full_backup_item else None
        )
        if full_backup is None:
            raise RuntimeError("cloud backup did not contain the LogDate full backup")
        with tarfile.open(
            fileobj=io.BytesIO(full_backup.read()), mode="r:"
        ) as app_backup:
            present = {
                item.name
                for item in app_backup.getmembers()
                if item.isfile() and item.size > 0
            }
    if not required.issubset(present) or not identity_keys.intersection(present):
        raise RuntimeError(
            "cloud backup omitted the journal database, owner preferences, or portable recovery material"
        )
    if any(path.startswith(excluded_roots) for path in present):
        raise RuntimeError(
            "cloud backup included device-transfer data or original media"
        )


def credentials_only_from_full_backup(h, payload):
    """Model a partial delivery of portable credentials without journal content."""
    app_prefix = f"apps/{h.PACKAGE}"
    prefix = app_prefix + "/f/"
    database_key = prefix + "db_recovery/passphrase_backup"
    owner_preferences = prefix + "logdate.preferences_pb"
    manifest = app_prefix + "/_manifest"
    identity_keys = {
        prefix + "identity_recovery/phrase_backup",
        prefix + "identity_recovery/account_key_backup",
        prefix + "identity_recovery/media_key_backup",
    }
    retained = {manifest, owner_preferences, database_key, *identity_keys}
    retained_directories = {
        app_prefix + "/f",
        prefix + "db_recovery",
        prefix + "identity_recovery",
    }
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as source:
        members = source.getmembers()
        files = {item.name: item for item in members if item.isfile()}
        if manifest not in files:
            raise RuntimeError(
                "credentials-only fixture is missing Android's app backup manifest"
            )
        has_identity_key = any(
            path in files
            for path in identity_keys - {prefix + "identity_recovery/media_key_backup"}
        )
        if (
            database_key not in files
            or prefix + "identity_recovery/media_key_backup" not in files
            or not has_identity_key
        ):
            raise RuntimeError(
                "credentials-only fixture is missing required portable recovery keys"
            )
        preferences_item = files.get(owner_preferences)
        preferences = source.extractfile(preferences_item) if preferences_item else None
        if preferences is None:
            raise RuntimeError(
                "credentials-only fixture is missing required portable recovery keys"
            )
        owner = h.canonical_preferences(
            preferences.read(),
            allowed_names={
                "identity.canonical_owner_id",
                "identity.canonical_owner_bound",
            },
        )
        if not owner:
            raise RuntimeError(
                "credentials-only fixture is missing the canonical journal owner"
            )
        output = io.BytesIO()
        with tarfile.open(
            fileobj=output, mode="w:", format=tarfile.USTAR_FORMAT
        ) as filtered:
            emitted_directories = set()
            for item in members:
                if (
                    item.isdir()
                    and item.name.rstrip("/") in retained_directories
                    and item.name.rstrip("/") not in emitted_directories
                ):
                    filtered.addfile(copy.copy(item))
                    emitted_directories.add(item.name.rstrip("/"))
                elif item.name in retained:
                    if (
                        item.name == database_key
                        and prefix + "db_recovery" not in emitted_directories
                    ):
                        directory = tarfile.TarInfo(prefix + "db_recovery/")
                        directory.type = tarfile.DIRTYPE
                        directory.mode = 0o700
                        filtered.addfile(directory)
                        emitted_directories.add(prefix + "db_recovery")
                    retained_item = copy.copy(item)
                    retained_item.pax_headers = {}
                    if item.name == owner_preferences:
                        retained_item.size = len(owner)
                        filtered.addfile(retained_item, io.BytesIO(owner))
                    else:
                        filtered.addfile(retained_item, source.extractfile(item))
    return output.getvalue()


def rewrite_app_backup(h, payload, allowed_paths, replacements=None):
    """Retain selected Android app-backup entries, preserving tar metadata and terminators."""
    replacements = replacements or {}
    app_prefix = f"apps/{h.PACKAGE}"
    output = bytearray()
    cursor = 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as source:
        members = source.getmembers()
    for item in members:
        end = item.offset_data + ((item.size + 511) // 512) * 512
        if item.name not in allowed_paths and not (
            item.isdir() and item.name.startswith(app_prefix + "/")
        ):
            cursor = end
            continue
        output.extend(payload[cursor : item.offset])
        replacement = replacements.get(item.name)
        if replacement is None:
            output.extend(payload[item.offset : end])
        else:
            header = bytearray(payload[item.offset : item.offset_data])
            header[124:136] = f"{len(replacement):011o}\0".encode()
            header[148:156] = b"        "
            header[148:156] = f"{sum(header):06o}\0 ".encode()
            output.extend(header)
            output.extend(replacement)
            output.extend(b"\0" * ((-len(replacement)) % 512))
        cursor = end
    output.extend(payload[cursor:])
    return bytes(output)


def without_portable_recovery_keys_from_full_backup(h, payload):
    """Remove only this app's portable keys from Android's full-backup tar."""
    found_database_key = False
    found_identity_key = False
    database_key = f"apps/{h.PACKAGE}/f/native_journal_transfer/current/files/db_recovery/passphrase_backup"
    identity_keys = {
        f"apps/{h.PACKAGE}/f/identity_recovery/phrase_backup",
        f"apps/{h.PACKAGE}/f/identity_recovery/account_key_backup",
        f"apps/{h.PACKAGE}/f/identity_recovery/media_key_backup",
    }
    removed_ranges = []
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as source:
        for item in source.getmembers():
            if item.name == database_key:
                found_database_key = True
            elif item.name in identity_keys:
                found_identity_key = True
            else:
                continue
            end = item.offset_data + ((item.size + 511) // 512) * 512
            removed_ranges.append((item.offset, end))
    if not found_database_key or not found_identity_key:
        raise RuntimeError(
            "LogDate full backup did not contain both expected portable key files"
        )
    output = bytearray()
    cursor = 0
    for start, end in sorted(removed_ranges):
        output.extend(payload[cursor:start])
        cursor = end
    output.extend(payload[cursor:])
    return bytes(output)


FUNCTIONS = {
    "selected_backup_contents": selected_backup_contents,
    "verify_privacy_repair_snapshot": verify_privacy_repair_snapshot,
    "is_user_content_path": is_user_content_path,
    "contains_user_content": contains_user_content,
    "contains_media_files": contains_media_files,
    "verify_restored_snapshot": verify_restored_snapshot,
    "verify_missing_keys_snapshot": verify_missing_keys_snapshot,
    "without_portable_recovery_keys": without_portable_recovery_keys,
    "credentials_only": credentials_only,
    "without_privacy_metadata": without_privacy_metadata,
    "without_one_native_media_file": without_one_native_media_file,
    "rewrite_native_full_backup": rewrite_native_full_backup,
    "selected_database_snapshot": selected_database_snapshot,
    "assert_cloud_backup_contents": assert_cloud_backup_contents,
    "credentials_only_from_full_backup": credentials_only_from_full_backup,
    "rewrite_app_backup": rewrite_app_backup,
    "without_portable_recovery_keys_from_full_backup": without_portable_recovery_keys_from_full_backup,
}
