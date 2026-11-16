# Journal moves reference

This page lists the switches, server calls, codes, metrics, log messages and stored state involved
in moving existing journals into personal spaces. [Moving journals into personal
spaces](../explanation/journal-moves.md) explains how a move works and why, and the [journal moves
runbook](../runbook/journal-moves.md) describes rehearsing and operating moves.

## Switches

A move needs the server and the app to agree, so each side has its own switches and all of them are
off by default.

The server offers moves only when both `LOGDATE_PERSONAL_SPACE_ENABLED` and
`LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED` are `true`. It then advertises the protocol feature
`personalSpaceMigrationV1` in its server description and answers the `/api/v1/space-migration`
calls; otherwise the calls that start or advance a move answer `404 FEATURE_DISABLED`. Turning the
migration switch off later only stops new moves: accounts that already moved keep their storage mode,
the legacy routes keep answering them as moved, and they can still switch back until their legacy
copies are deleted 30 days after each switch. `LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED`, on
by default, pauses that deletion when set to `false`, without pausing other maintenance.
`LOGDATE_SPACE_MIGRATION_HOLD_GRACE_SECONDS` sets how long a hold on legacy writes must stand before
a device may switch, 120 seconds by default. [Server environment
variables](../../server/docs/environment-variables.md) describes all four settings.

The app moves a journal only when the feature flags `personal_space_enabled` and
`personal_space_migration_enabled` are both on and the server advertises `personalSpaceMigrationV1`.
Developer tools has a Sealed storage previews section that turns both flags on for a test device.

## Timings

The server fixes the timings of a move in `SpaceMigrationConfig`. A device's claim on a move lapses
after one hour without a call from it, after which another device may take the move over. A hold on
writes ends on its own 30 minutes after it starts. The legacy copy is kept for 30 days after the
switch, and switching back is possible until then. The maintenance loop that deletes due copies runs
with the tombstone purge, every `SYNC_TOMBSTONE_PURGE_INTERVAL_MINUTES`, and only when
`SYNC_TOMBSTONE_PURGE_ENABLED` isn't `false` and `LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED`
isn't set to anything but `true`. When several server instances share the database, one at a time runs
the loop: each waits a random moment of up to five minutes after it starts, and skips a run while
another instance is in the middle of one.

On the device, a move that stops waits before trying again on its own. It tries again on the next
sync when the device was offline or is missing items from the server, after one hour when another
device is moving the journal, after 24 hours when storage is full or the window for switching back
has closed, and after six hours for anything else. The person can always try again at once from
Settings.

## Server calls

Every call lives under `/api/v1/space-migration`, needs a full-access token of the account's own,
and is documented with its request and response schemas under the "Moving to a personal space" tag
in `/docs`. A refusal answers `409` with one of the codes in [Refusal codes](#refusal-codes), except
a media file that doesn't exist, which answers `404 MEDIA_NOT_FOUND`.

- `GET /api/v1/space-migration` returns the account's move status: its phase, its ID, when a hold is
  ready and when it ends, when the account switched and at which personal-space sequence number, and
  until when the legacy copy is kept.
- `POST /begin` claims the move for the device's move ID, or resumes the device's own claim.
- `POST /hold` holds writes to the store the account is leaving, and `POST /release` ends the hold.
- `GET /legacy-inventory` returns a watermark and, for each legacy kind, a count and a SHA-256
  digest of its IDs; `?ids=<kind>` adds that kind's IDs.
- `POST /media/{mediaId}` copies one app-encrypted legacy file into the personal space and returns
  its content identifier and size. It answers `503 SERVER_BUSY` with `Retry-After` when the server
  is already copying as many files as it can.
- `POST /switch` switches the account to `personalSpaceV1` when the proof in its body matches both
  stores, and `POST /switch-back` returns the account to `legacy` while the copy is kept.

## Legacy route answers

While a move holds legacy writes, every legacy sync write for the account answers `503
SYNC_MIGRATION_IN_PROGRESS` with `Retry-After: 60`, and reads keep working. After the switch, every
legacy sync route answers `403 SYNC_FORMAT_UPGRADE_REQUIRED`. An account in its personal space that
never moved still answers `403 SYNC_STORAGE_MODE_MISMATCH`. While a device switches back, personal
space writes answer `503 ServerBusy`, and a write that arrives after the account returned to
legacy storage answers `409 AccountStorageChanged`.

## Refusal codes

Each refusal names the condition the server found, and the app turns it into a problem the person
can see in Settings.

- `SPACE_MIGRATION_HELD_ELSEWHERE`: another device holds an active claim on the move.
- `SPACE_MIGRATION_NOT_ACTIVE`: no move with this ID is in the phase the call needs.
- `SPACE_MIGRATION_WRONG_STORAGE_MODE`: the account isn't in the storage mode the call starts from.
- `SPACE_MIGRATION_HOLD_NOT_READY`: writes aren't held yet, or the hold hasn't stood for the grace
  period.
- `SPACE_MIGRATION_LEGACY_CHANGED`: legacy storage changed after the device checked it.
- `SPACE_MIGRATION_SPACE_CHANGED`: the personal space doesn't hold what the device checked.
- `SPACE_MIGRATION_WINDOW_CLOSED`: the legacy copy is no longer kept, so the account can't switch back.
- `SPACE_MIGRATION_MEDIA_NOT_CLIENT_ENCRYPTED`: the server encrypted this file itself, so the device
  uploads it again instead.
- `SPACE_MIGRATION_NO_IDENTITY`: the account has no DID, so it has no personal space.

## Problems shown in Settings

When a move stops, the Backup page in Settings shows an Upgrade paused row with one of these
problems and a Try again row, or an Enter recovery phrase row when the identity key is missing.

- The server has items of a kind that the device doesn't have yet.
- Items of a kind on the device haven't reached the server yet.
- The sealed copies of some items didn't match the device's, even after uploading them again.
- The device doesn't sync a kind the account has, such as location history.
- Media files are on neither the device nor the server.
- Another device is moving the journal.
- The account's storage is full.
- The device doesn't have the identity key.
- The server couldn't be reached.
- The server refused the move with a code, shown in parentheses.
- The legacy copy is no longer kept, so the journal can't be switched back.

## Metrics

The server records these operations in its sync metrics, which `/ops/sync/metrics/prometheus`
exports as `logdate_sync_operation_success_total`, `logdate_sync_operation_error_total` and
`logdate_sync_operation_duration_ms_total` with an `operation` label. A refusal counts as a success,
because the server answered; only a call that failed with a server error counts as an error.

- `space_migration.begin`, `space_migration.media_copy`, `space_migration.switch` and
  `space_migration.switch_back` time the calls of the same names.
- `maintenance.legacy_copy_cleanup` times each run of the cleanup, which counts as an error when
  any copy it started on couldn't be deleted.

`docs/observability/sync-alerts.yaml` alerts on `LogDateSpaceMigrationServerErrors` after 15
minutes of server errors on the move calls, and on `LogDateLegacyCopyCleanupFailures` after an hour
of failed cleanup runs.

## Log messages

Production logs replace free-form messages with codes, and these are the codes moves produce. None
of them names an account.

- `SERVER_SPACE_MIGRATION_SWITCHED` and `SERVER_SPACE_MIGRATION_SWITCHED_BACK` mark each switch.
- `SERVER_SPACE_MIGRATION_COPY_MISMATCH` means the server copied a legacy file whose stored bytes
  didn't match what it read, and refused the copy.
- `SERVER_LEGACY_COPY_CLEANUP_FAILED` means deleting one legacy copy failed; the next run tries it
  again. `SERVER_LEGACY_COPY_CLEANUP_RUN_FAILED` means a whole run failed.
- `SERVER_LEGACY_COPY_CLEANUP_PAUSED` means `LOGDATE_PERSONAL_SPACE_OLD_DATA_PURGE_ENABLED` pauses
  deleting legacy copies; maintenance logs it when it starts.
- `SERVER_LEGACY_COPY_CLEANUP_SKIPPED` means a move was due for cleanup but its account isn't in its
  personal space, so its copy stays.
- `SERVER_MAINTENANCE_LEASE_FAILED` means an instance couldn't check whether another one was running
  maintenance, so it skipped that run of the cleanup; the next interval tries again.
- `Deleted the legacy copies of <n> moved journals` reports each run that deleted something.

## Stored state

Each move is one row of the `space_migrations` table, keyed by account. Its `phase` is `uploading`,
`switched`, `reverted` or `cleaned`; `version` increases with every change, and every update is a
compare-and-set on it. `hold_started_at` is set while writes are held, `switched_at` and
`switched_at_seq` record the switch, `reverted_at` records a switch back, and `cleaned_at` records
when the legacy copy was deleted. A `cleaned` row with no `cleaned_at` is a cleanup that hasn't
finished.

On the device, each account's move keeps a checkpoint with its move ID, its stage, the last record
it uploaded, the kinds it finished, its current problem and when it tries again, and the personal
space reference of every media file it moved.
