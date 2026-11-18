# Sync V1 API (Launch Contract)

This document is the human-readable launch reference for sync endpoints.

Canonical sources:
- `GET /docs` (interactive reference)
- `GET /openapi.json` and `GET /openapi.yaml` (machine-readable)
- `server/docs/openapi.md` (how the reference is maintained)

## Endpoint reference

Every sync endpoint, its parameters, multipart parts, examples and error codes are documented in
the interactive reference at `/docs` (Contents, Journals, Associations, Drafts, Media, Backups and
Sync status sections), and the sync model is explained in its overview. This page keeps only the
hidden maintenance contracts, which are not published.

## Maintenance Purge Contract
`POST /api/v1/ops/sync/tombstones:purge?retentionDays=30`

Rules:
- `retentionDays` defaults to `30` when omitted.
- `retentionDays` must be greater than `0`; otherwise `400 INVALID_PARAMETER`.
- `retentionDays` is clamped to `3650` max.

Response:
```json
{
  "contentPurged": 0,
  "journalPurged": 0,
  "associationPurged": 0,
  "mediaPurged": 0,
  "cutoff": 1730932569000,
  "retentionDaysApplied": 30
}
```

## Error Codes

Every code each endpoint can return is listed on that endpoint in `/docs`, with what it means and
what to do about it. The overview's Errors section has the cross-cutting table.

## Storage Modes

Each account has a storage mode: `legacy`, or `personalSpaceV1` for accounts whose journal data lives
in their personal space. The mode is chosen when the account is created and only the server's
migration task changes it afterwards; signing in, saving the profile and setting up the account's
identity never do. Apps read it from `GET /auth/me` when they sign in and each time sync starts.

The per-kind sync routes serve only `legacy` accounts. An account in any other mode, including a mode
this server does not recognize, gets `403 SYNC_STORAGE_MODE_MISMATCH` on every one of them and
nothing is read or written. An app that sees this code reads `/auth/me` again and syncs the way the
mode says. An account in `personalSpaceV1` keeps that mode when `LOGDATE_PERSONAL_SPACE_ENABLED` is
turned off.

An account whose journal a device moved from legacy storage into its personal space gets
`403 SYNC_FORMAT_UPGRADE_REQUIRED` instead, so an app that predates personal spaces can ask the
person to update. It is a `403` rather than a `409` because released apps treat a `409` on an upload
as a conflict and stop sending the change, while a `403` leaves it queued until the app is updated.
While a device checks the journal just before the switch, legacy writes answer
`503 SYNC_MIGRATION_IN_PROGRESS` with `Retry-After`, and reads keep working. The
[journal moves reference](../../docs/reference/journal-moves.md) lists the calls a moving device
makes.

## Linked Watch Removal

A removed or expired watch session is refused with `401 DEVICE_SESSION_REVOKED`. The watch's next
token refresh tells the two apart: only a removed session answers `DEVICE_SESSION_REVOKED` there, and
an expired one answers `DEVICE_SESSION_EXPIRED`. Each server instance
caches whether a session is active for 30 seconds and only the instance that handled the removal
clears its cache at once, so a removed watch can keep reaching the sync routes and the personal space
methods for up to 30 seconds on the other instances.

## Backup Purge Contract
`POST /api/v1/ops/backups:purge?keepPerDevice=3&maxAgeDays=90`

Rules:
- `keepPerDevice` (optional) is how many of each device's newest backups to keep; it defaults to
  the server's retention policy and must be `0` or more.
- `maxAgeDays` (optional) deletes backups older than this many days; it must be greater than `0`
  and is clamped to `3650`.
- Invalid values answer `400 INVALID_PARAMETER`.
- `503 BACKUP_STORAGE_UNAVAILABLE` when no backup storage is configured; `500 BACKUP_DELETE_FAILED`
  when a delete fails part-way.
- The response is `{ "deleted", "remaining" }` counts.
