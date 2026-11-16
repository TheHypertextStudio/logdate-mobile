# Sync on LogDate's schemas

Part of the Migrate sync and export to the lexicons project in the LogDate on the AT Protocol
initiative. This design covers the task "Sync every kind of journal data as LogDate schema
records". It builds on [Private storage](../../explanation/private-storage.md),
which added the sealed personal space and the shared `DataHome` interface. Moving existing
accounts over is the next task and has its own design.

## What changes

Today each kind of data has its own REST endpoint, cursor and wire format (`/contents`, `/journals`,
`/associations`, `/drafts`, `/moments`, `/location-history`, `/media`), and the server can read
each record's kind, timestamps, lengths and journal membership. After this change, an account in
**personal-space mode** syncs everything as `app.logdate.*` records sealed into its personal space:

- one change feed with one cursor for every kind of data;
- whole-record writes with a swap check, so the server never merges anything;
- media uploaded as space blobs, still chunk-encrypted with the account media key;
- conflict resolution, journal merges and per-kind storage counts on the device.

Accounts that aren't in personal-space mode keep syncing exactly as they do today. Nothing in this
task moves an existing account; the next task does that.

## Storage mode is per account

The server records each account's storage mode: `legacy` or `personalSpaceV1`.

- When `LOGDATE_PERSONAL_SPACE_ENABLED` is on, **new accounts start in `personalSpaceV1`**.
  Existing accounts stay `legacy` until the migration task moves them.
- The account endpoint the apps already read returns the mode. Apps pick the transport from it,
  never from a local guess, and only when the server also advertises `personalSpaceV1`.
- A client feature flag, `PERSONAL_SPACE`, off by default, lets a build ignore the mode entirely.

## The space transport on the device

The existing sync machinery stays: the outbox (`pending_uploads`), retry and dead letters, the
download inbox, conflict resolvers, the apply steps for each kind, status counts, and token
refresh. Only the transport underneath changes.

- **Record mappers.** Each kind gets a mapper between its Room/domain model and its
  `app.logdate.*` record, with round-trip tests that prove nothing is lost:

  | Kind             | Records                                                                                  |
  | ---------------- | ---------------------------------------------------------------------------------------- |
  | Note             | `journal.entry` (one single-block entry per note; rkey = note uid), `journal.transcript` |
  | Journal          | `journal.journal` (including `favorite` and `mergedInto`)                                |
  | Association      | `journal.membership` (rkey `<journalId>:<entryId>`)                                      |
  | Draft            | `journal.draft`                                                                          |
  | Marked moment    | the moment record                                                                        |
  | Location history | `place.sample` batches, `place.place`, `place.correction`, memory links, manual visits   |

  The lexicons were revised so every synced field has a home; see the lexicon reference.

- **Upload.** For each outbox row, the mapper builds the record, the sealing code seals it, and
  `DataHome.putRecord` writes it. Creates use `RecordSwap.Absent`; updates use
  `RecordSwap.Matching(cid)` with the CID from the last write or read. A conflict re-reads the
  record, runs the kind's existing resolver, and writes again. Deletions use `deleteRecord`.
- **One change feed.** Download pages `DataHome.listChanges` from one stored cursor (a `SPACE`
  checkpoint in the existing download checkpoints table). Each change is opened, checked against its
  schema with `LexiconRecordValidator`, and staged in the existing inbox under its kind, by inner
  collection. Records that fail to open or validate are reported as unreadable, never dropped. A
  `CursorTooOld` answer resets the cursor to zero and replaces local state with the full listing,
  removing local records the server no longer has.
- **Swap state.** A new table records, for each record the device knows, its collection, inner
  key, sealed key, last known CID and sequence number. It is the only new persistent state, and it
  is scoped by account and server origin like every other sync table.
- **Media.** Media is chunk-encrypted exactly as today (LDCE2, account media key), streamed to
  `uploadBlob`, and referenced from the record's `defs#media` with its real MIME type. `DataHome`
  gains a streaming upload and download so files are never held whole in memory.
- **Journal merges.** The device performs the merge itself: it rewrites the memberships to the
  surviving journal and writes the merged journal as a `mergedInto` record. Other devices apply the
  same redirect when they read it. The server's merge endpoint isn't used in personal-space mode.
- **Errors.** Space errors are translated into the sync layer's existing error types: an expired
  token triggers the existing refresh path; rate limiting backs off with the server's
  `Retry-After`; quota answers surface the existing quota state; transport failures retry. Nothing
  is swallowed.
- **Quota.** Settings shows storage by kind, computed on the device from local data. The total and
  the limit come from `GET /quota`, which counts space records and blobs.

## The watch

The watch keeps routing through the phone over the Wear Data Layer by default. When a linked watch
uploads directly, it uses the same space transport:

- the server accepts linked-device tokens (sync scope) on the space methods for that account's own
  personal space, as owner devices, while still refusing third-party OAuth and AT Protocol session
  tokens;
- the phone passes the account DID to the watch when linking, next to the identity key it already
  passes.

## Location history and moments

Both move into the personal space with everything else, so the server stops seeing their record
types, device ids and timestamps. Their dedicated endpoints remain for `legacy` accounts only.

## Turning it on

The Docket task lists what must be true before `LOGDATE_PERSONAL_SPACE_ENABLED` is turned on in
staging and then production: rate limits, quota, streamed uploads, the database tests in CI, and
this task's end-to-end coverage for every kind of data on Android, iOS and desktop, plus the watch.

## Decisions

| Decision                                                      | Why                                                                                                    |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| One note becomes one single-block entry                       | Notes aren't grouped today; merging them would lose each note's own time, location and capture context |
| Storage mode lives on the server, per account                 | Every device of an account must agree on the transport                                                 |
| New accounts start in personal-space mode once the flag is on | They have nothing to migrate                                                                           |
| The device performs journal merges                            | A sealed space can't be merged on the server                                                           |
| Linked watches may use the space directly                     | A linked watch is one of the owner's own devices                                                       |
