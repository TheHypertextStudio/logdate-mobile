# Personal space sync

An account's private data lives in its **personal space**: a store on the server that holds only
encrypted records, which the owner's devices write and read. This page describes how a LogDate app
moves data to and from that space as the code does it: how it decides to use the space, what
it sends, what it stores locally, how it tracks its position in the change feed, and how it settles
two devices changing the same thing.

It covers the client. The server's side is in the [sync API contract](../../server/docs/sync-v1-api.md),
the [personal-space methods](./lexicons.md#personal-space-methods) and the
[`LOGDATE_PERSONAL_SPACE_ENABLED` settings](../../server/docs/environment-variables.md#logdate_personal_space_enabled).
The reasons for the design are in [Private storage in sealed personal spaces](../explanation/private-storage.md),
and the wider sync engine that this transport plugs into is described in
[Sync architecture](./sync-architecture.md).

## The idea in brief

On a device, a change made locally is queued in an
**outbox**. Uploads drain the outbox, retrying failures and setting aside the ones that can never
succeed. Downloads are staged in an **inbox** and then applied to local storage by the code that
owns each kind of data, which resolves conflicts as it goes.

The personal space replaces what is underneath. Instead of one server route, wire format and
cursor for each kind of data, every kind becomes one or more AT Protocol **records**, each record
is encrypted into a sealed envelope, and all of them travel through one small interface with one
change feed. The outbox, retries, the inbox, the conflict resolvers and each kind's apply step are
shared with the per-kind routes.

A few terms are used throughout.

| Term                     | Meaning                                                                                                                                                                                        |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DID                      | An account's permanent AT Protocol identifier, such as `did:plc:abcdefghijklmnopqrstuvwx`. It names the account's personal space                                                               |
| XRPC                     | The AT Protocol's convention for calling a server over HTTP. Each method has a name such as `app.logdate.space.putRecord` and is served at `/xrpc/{method name}`                               |
| Record                   | One structured document, such as an `app.logdate.journal.entry`. Its type is its _collection_ and it is stored under a _record key_ (rkey)                                                     |
| Sealed record            | A record encrypted into an `app.logdate.crypto.sealed` envelope. The server stores only these                                                                                                  |
| Inner key and sealed key | The inner key is the record's real key, such as a note's id. The sealed key is the opaque 26-character key the server stores it under, derived from the collection and inner key with a secret |
| CID                      | A content identifier: a hash of a stored record or file. The server returns one for every write                                                                                                |
| Sequence number          | A number the server assigns to each change in a space, always increasing. A device asks for the changes after the last one it saw                                                              |
| Kind                     | One kind of data the app syncs, such as notes or journals. A kind owns one or more record types                                                                                                |
| Data home                | The server that holds a space: LogDate's hosted service or a self-hosted LogDate server                                                                                                        |

## Where the code lives

| Path                                                                                                                     | What it holds                                                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`shared/space/`](../../shared/space/src/commonMain/kotlin/app/logdate/space/)                                           | The space reference, key derivation, padding, the envelope codec, the `DataHome` interface and its XRPC implementation. Shared by the apps and the server's tests |
| [`client/sync/.../space/`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/)                       | The transport: the gate, uploader, downloader, staged records, media, error translation and the local state store                                                 |
| [`client/sync/.../space/mappers/`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/mappers/)       | One mapper for each record type and one `SpaceKind` for each kind of data                                                                                         |
| [`client/sync/.../space/migration/`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/migration/)   | Moving an existing account from the per-kind routes into its space                                                                                                |
| [`client/sync/.../di/CloudModule.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/di/CloudModule.kt) | Where the kinds, the gate and the data home are wired together (`spaceSyncModule`)                                                                                |

## Choosing between the personal space and the per-kind routes

Every device of an account has to sync the same way, or the account's data would be split between
two stores. The server therefore decides how each account is stored and every device follows it.

The server records a **storage mode** for each account and reports it at `GET /auth/me`. The app
reads the account again each time sync starts, before anything else syncs, and saves the answer.

| Storage mode on the wire | Meaning                                                                                                |
| ------------------------ | ------------------------------------------------------------------------------------------------------ |
| `legacy`                 | The account syncs through the per-kind routes: one route, cursor and wire format for each kind of data |
| `personalSpaceV1`        | Every kind of data is sealed records in the account's personal space                                   |
| Anything else            | A mode added after this app was built. It reads as unrecognized                                        |

A server that predates storage modes sends nothing, which reads as `legacy`.

[`SpaceTransportGate`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceTransportGate.kt)
turns the mode into a route, using two more facts: whether the connected server advertises the
`personalSpaceV1` protocol feature in its server description, and whether this build has the
`PERSONAL_SPACE` feature flag on.

| Storage mode                                                      | Server advertises `personalSpaceV1` | `PERSONAL_SPACE` flag | Route           |
| ----------------------------------------------------------------- | ----------------------------------- | --------------------- | --------------- |
| `legacy`                                                          | Either                              | Either                | Per-kind routes |
| `personalSpaceV1`                                                 | Yes                                 | On                    | Personal space  |
| `personalSpaceV1`                                                 | No                                  | Either                | Blocked         |
| `personalSpaceV1`                                                 | Yes                                 | Off                   | Blocked         |
| Unrecognized, or not yet reported for this account on this server | Either                              | Either                | Blocked         |
| None, because nobody is signed in                                 | Either                              | Either                | Blocked         |

A blocked route syncs nothing at all. The run fails with the non-retryable sync error
`ACCOUNT_STORAGE_ERROR` and a reason that says why, and Settings shows it as a sync issue. Syncing
through either store in those cases could put changes where the account's other devices never
look, so stopping is the safe answer.

A device never assumes `legacy` when it has not heard from the server. A signed-in account whose
mode the server has not reported yet reads as unrecognized
([`ServerAccountStorageMode`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/cloud/account/ServerAccountStore.kt)),
so those two cases share one row and one reason.

| Why the route is blocked                                                                 | Reason given                                                                                         |
| ---------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| The account is in its personal space and the server does not advertise `personalSpaceV1` | "This account's journal is stored in a personal space, and the server doesn't offer personal spaces" |
| The account is in its personal space and the `PERSONAL_SPACE` flag is off                | "This account's journal is stored in a personal space, which this version of LogDate doesn't sync"   |
| The mode is unrecognized or not yet reported                                             | "The server stores this account in a way this version of LogDate doesn't know"                       |
| Nobody is signed in                                                                      | "The server hasn't said how it stores this account"                                                  |

### Flags and switches

| Switch                                                          | Where                                                                                                                                          | Default                                           | Effect                                                                                                                                                                    |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PERSONAL_SPACE` (`personal_space_enabled`)                     | Client feature flag, [`FeatureFlag.kt`](../../client/datastore/src/commonMain/kotlin/app/logdate/client/datastore/featureflags/FeatureFlag.kt) | On                                                | Lets this build sync an account stored in its personal space. Off, such an account is blocked, with the reason that this version of LogDate doesn't sync a personal space |
| `PERSONAL_SPACE_MIGRATION` (`personal_space_migration_enabled`) | Client feature flag                                                                                                                            | On                                                | Lets this device move an existing journal into its personal space. It takes effect only with `PERSONAL_SPACE` on                                                          |
| `personalSpaceV1`                                               | Protocol feature in the server description                                                                                                     | Advertised when the server has personal spaces on | Tells apps the `app.logdate.space.*` methods are served                                                                                                                   |
| `personalSpaceMigrationV1`                                      | Protocol feature in the server description                                                                                                     | Advertised when the server offers moves           | Tells apps the journal-move calls are served                                                                                                                              |
| `LOGDATE_PERSONAL_SPACE_ENABLED`                                | Server environment variable                                                                                                                    | `false`                                           | Serves the space methods, advertises `personalSpaceV1`, and starts new accounts in `personalSpaceV1`                                                                      |
| `LOGDATE_PERSONAL_SPACE_MIGRATION_ENABLED`                      | Server environment variable                                                                                                                    | `false`                                           | Offers journal moves, together with the variable above                                                                                                                    |

Both client flags can be changed on a device from the Developer tools screen in Settings. Turning
`PERSONAL_SPACE` off there also turns the move off, and turning the move on turns `PERSONAL_SPACE`
on. The server variables are documented in the
[environment variables reference](../../server/docs/environment-variables.md).

A new account starts in `personalSpaceV1` when its server has personal spaces on, because it has
nothing to convert. An existing account stays `legacy` until one of its devices moves it; see
[Moving an existing account](#moving-an-existing-account-into-its-personal-space).

## The transport

Apps reach a space through one interface,
[`DataHome`](../../shared/space/src/commonMain/kotlin/app/logdate/space/DataHome.kt). It only ever
carries sealed envelopes and encrypted files.

| `DataHome` operation         | What it does                                                                                                               | XRPC method it calls             |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| `putRecord`                  | Creates or replaces one sealed record, subject to a swap condition                                                         | `app.logdate.space.putRecord`    |
| `deleteRecord`               | Deletes one sealed record and returns the sequence number of the deletion                                                  | `app.logdate.space.deleteRecord` |
| `getRecord`                  | Reads one sealed record, or returns nothing if there is none                                                               | `app.logdate.space.getRecord`    |
| `listChanges`                | Reads a page of changes after a sequence number, oldest first, deletions included                                          | `app.logdate.space.listRepoOps`  |
| `uploadBlob`                 | Stores encrypted bytes and returns their CID. One form takes a byte array, the other streams from a source of a known size | `app.logdate.space.uploadBlob`   |
| `getBlob` and `downloadBlob` | Read a file by CID, whole or streamed into a sink                                                                          | `app.logdate.space.getBlob`      |

Each method is served at `{server origin}/xrpc/{method name}`. The space methods are at the
server's origin, not under the REST API's base path. [`XrpcDataHome`](../../shared/space/src/commonMain/kotlin/app/logdate/space/XrpcDataHome.kt)
is the implementation. The record methods send JSON through the shared XRPC client, and the blob
methods send raw bytes with the size declared up front, reading the file in 64 KiB pieces so it is
never held whole in memory.

Every call carries the account's LogDate access token as a bearer token. A data home is opened
anew for each call rather than kept
([`SpaceDataHomes`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceDataHomes.kt)),
because a device can change servers and a refreshed token replaces the old one.

### Swap conditions

A write or delete states the condition under which it may go ahead (`RecordSwap`).

| Condition       | Sent as            | Meaning                                               |
| --------------- | ------------------ | ----------------------------------------------------- |
| `Absent`        | `createOnly: true` | Write only if no record exists under the key          |
| `Matching(cid)` | `swapCid`          | Write or delete only if the record still has this CID |
| `Unconditional` | Neither field      | Apply whatever is there                               |

A condition that is not met comes back as the server's `InvalidSwap` error, which the client raises
as `DataHomeConflictException`. A delete cannot use `Absent`.

### Upload receipts

`XrpcDataHome` also implements `BlobUploadReceipts`, which lets a device find out whether an upload
it started has already been stored, without sending the file again. Before sending any bytes the
device saves an upload identity, a random UUID, for the file. It sends that identity with the
upload in the `X-LogDate-Upload-Intent` header, and can later ask
`app.logdate.space.getBlobUploadReceipt` for the CID and size stored under it. This matters when a
large upload finishes on the server but the response never reaches the device. A server without
the method answers that receipts are unsupported, and the device uploads normally.

## What identifies the space and its keys

Two values that come from the account are needed before anything can be sent
([`SpaceSyncDependencies`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceSync.kt)).

- **The account's DID**, as the server reports it. It names the space:
  `at://{DID}/space/app.logdate.space.personal/self`. The device is also the author of every
  record, under the same DID. An account without a DID has no personal space, and sync reports
  that instead of guessing.
- **The account's identity key**, a 32-byte secret held on the device. The space's content key,
  index key and key id are derived from it each time a sync pass starts. A device without its
  identity key reports a key recovery error.

The envelope format, the key derivation and the padding rules are specified in
[Encryption formats](./encryption-formats.md) and pinned by the
[client conformance suite](../../conformance/README.md).

## What is stored in the space

### Records

Each kind of data is registered as a `SpaceKind` in
[`SpaceRecordRegistry`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceRecordRegistry.kt).
A kind has a **main** record, which is the item itself, and may own further record types whose
keys name the item they belong to.

| Kind (outbox entity type) | Record types                                                            | Record key                                                                  |
| ------------------------- | ----------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `JOURNAL`                 | `app.logdate.journal.journal`                                           | The journal's id                                                            |
| `NOTE`                    | `app.logdate.journal.entry` (main) and `app.logdate.journal.transcript` | The note's id. The transcript's key is `<note id>:<note id>`                |
| `ASSOCIATION`             | `app.logdate.journal.membership`                                        | `<journal id>:<entry id>`                                                   |
| `DRAFT`                   | `app.logdate.journal.draft`                                             | The draft's id                                                              |
| `MOMENT`                  | `app.logdate.journal.markedMoment`                                      | The moment's id                                                             |
| `PLACE`                   | `app.logdate.place.place`                                               | The place's id                                                              |
| `PLACE_VISIT`             | `app.logdate.place.visit`                                               | The visit's id                                                              |
| `PLACE_CORRECTION`        | `app.logdate.place.correction`                                          | The correction's id                                                         |
| `PLACE_ENTRY_LINK`        | `app.logdate.place.entryLink`                                           | `<entry id>:<SHA-256 of the evidence id, in hex>`                           |
| `PLACE_JOURNEY`           | `app.logdate.place.journey`                                             | The journey's id. No device writes one                                      |
| `LOCATION_SAMPLES`        | `app.logdate.place.sample`                                              | `<UTC hour>:<device id>`, with `:2` to `:20` for later parts of a full hour |

Only the kinds in this table are stored in the space. Other private data that the
[lexicon reference](./lexicons.md) assigns to the personal space, such as records about people,
events, rewinds and health snapshots, has record schemas but no registered kind and is not synced
there.

Record keys match regardless of case, and every client writes them in lowercase. The field-by-field
mapping between each kind of data and its records is in
[the lexicon reference](./lexicons.md#how-todays-synced-data-becomes-records).

A mapper is pure: it converts a value to a record and back, and reads or stores nothing. Each
record a device builds is checked against its schema before it is sealed. A value that cannot be
written as a record without losing something is refused with `SpaceRecordMappingException`, whose
message names the record and field and never the value. The registry refuses a kind whose download
side is not wired, so records can never be staged with nothing to apply them.

### Media

Photos, videos, recordings and journal covers are stored as **blobs** in the space, not inside
records ([`SpaceMediaDataSource`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceMediaDataSource.kt)).

- A file is encrypted on the device with the account's media key, in the chunked `LDCE2` format,
  while it is read from disk and streamed to `uploadBlob`. A file that is already client-encrypted
  is sent as it is.
- A blob can be at most 100 MiB (104,857,600 bytes) after encryption. A larger file is refused on
  the device before anything is sent.
- The record that refers to the file carries the blob's CID, the size of the stored ciphertext, and
  the real media type and size of the decrypted file. The decrypted size is left out when the file
  was already encrypted, because the only size the uploader has for such a file is the size of its
  ciphertext. The blob itself is always typed `application/octet-stream`. The CIDs a record refers to are also sent alongside the write, so the
  server can tell which files are still in use.
- A file is uploaded before the record that names it. A note whose file is not uploaded yet cannot
  be written as a record, and that failure is retryable.
- On download, the blob is streamed to a temporary file while it is hashed. The device checks that
  the size and the hash match the reference before decrypting, a chunk at a time, into a second
  file. Neither step holds more than one chunk in memory.

Locally, a note or draft refers to an uploaded file with a
[`SpaceMediaReference`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceMediaReference.kt):

```text
https://space.logdate.invalid/media/{cid}?contentId=…&type=…&storedSize=…&size=…&alt=…&ratio=…
```

| Part         | Meaning                                                             | Always present                                                     |
| ------------ | ------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `{cid}`      | The CID of the stored ciphertext                                    | Yes                                                                |
| `contentId`  | The id of the note or draft block the file belongs to               | Yes                                                                |
| `type`       | The media type of the decrypted file, such as `image/jpeg`          | Yes                                                                |
| `storedSize` | The size of the stored ciphertext in bytes                          | Yes                                                                |
| `size`       | The size of the decrypted file in bytes                             | No. It is absent when the file was already encrypted before upload |
| `alt`        | A text alternative another device wrote for the file                | No                                                                 |
| `ratio`      | The width and height another device recorded, as `{width}x{height}` | No                                                                 |

A note has no field of its own for the text alternative or the aspect ratio, so the reference keeps
them and the next upload of the note writes them back into the record unchanged.

The shape is deliberate. It is `https` with a `/media/{id}` path because that is how the sync engine
recognizes a file that already lives on a server. Its host ends in `.invalid`, a name reserved never
to resolve, so nothing can send it to a real server. The file is always fetched through the
signed-in server's space, which is why the same reference keeps working after an account moves to
another server.

`SpaceRoutingMediaDataSource` sends uploads to the space while notes sync there and reads each file
from wherever its reference says it lives, so a file uploaded before an account moved can still be
read afterwards.

## What the device stores

The transport uses three tables, one of them its own (`sync_space_records`). All three are scoped
by account and server origin, so signing in to another account or another server never mixes state.

| Table                       | Used for                                                                     |
| --------------------------- | ---------------------------------------------------------------------------- |
| `sync_space_records`        | The last version of each record this device wrote or applied                 |
| `sync_download_checkpoints` | The change-feed cursor, in the row whose `entityType` is `SPACE`             |
| `sync_download_inbox`       | Changes staged for applying, and changes kept because they could not be read |

`sync_space_records` ([`SpaceRecordStateEntity`](../../client/database/src/commonMain/kotlin/app/logdate/client/database/entities/sync/SpaceRecordStateEntity.kt))
has the primary key `(ownerId, serverOrigin, collection, innerRkey)` and a unique index on
`(ownerId, serverOrigin, sealedRkey)`.

| Column                    | Meaning                                                                                                                                               |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `collection`, `innerRkey` | The real record type and key                                                                                                                          |
| `sealedRkey`              | The opaque key the record is stored under. A deletion in the change feed names nothing else, so this is how the device knows which record was deleted |
| `cid`                     | The CID of the stored envelope. The next write sends it back as its swap condition                                                                    |
| `seq`                     | The sequence number of that version                                                                                                                   |

In the inbox, a kind's staged records are filed under the type `SPACE_` followed by the kind's
name, such as `SPACE_NOTE`, with one row for each record, keyed by its sealed key. Their payloads
start with `SPACE1:` so nothing mistakes them for a per-kind wire format. Changes that could not be
read are filed under `SPACE_UNREADABLE` with payloads starting `SPACE1-SEALED:`, which keep the
whole envelope so it can be opened again later.

## Uploading

[`SpaceUploader`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceUploader.kt)
handles one kind's outbox entries at a time. For each create or update it does the following.

1. It loads the item and builds every record the item is stored as, main record first, checking
   each against its schema.
2. It looks up the version it last saw of each record in `sync_space_records`.
3. It seals each record and writes it. A record the device has no version for is written with
   `Absent`. A record it has a version for is written with `Matching` and that version's CID.
4. It saves the CID and sequence number of each write.
5. It deletes any record the item used to have and no longer does, such as the transcript of a
   recording whose transcript was removed.
6. It marks the outbox entry as settled.

The main record is written first so that another device never sees an item's other records before
the item itself.

A deletion deletes each record of the item that the device holds a version for in
`sync_space_records`, each with `Matching` and that version's CID. It does not enumerate every
record type the item could have. If the device holds no version at all, it reads the item's
records from the space first, because anything stored there is an edit it has not seen: if nothing
is there the deletion is already done, and otherwise the rules under [Conflicts](#conflicts)
decide whether the edit or the deletion wins.

An entry queued for a different account or server is left alone until that account is signed in
again. A failure belongs to its entry alone: the rest of the outbox keeps uploading.

## Downloading and the cursor

[`SpaceDownloader`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceDownloader.kt)
reads the change feed into the inbox. There is one cursor for the whole space: the highest
sequence number the device has staged, saved in the `SPACE` checkpoint. Within one download pass
the feed is pulled once, however many kinds read from it.

The downloader asks for pages of 100 changes after the cursor and continues until the server says
there are no more. For each change in a page:

- **A change the device already has is skipped.** If `sync_space_records` holds the same CID for
  that sealed key, the device wrote or applied exactly this version.
- **A readable change is staged.** The envelope is opened, the record is checked against its
  schema, and it is staged under the kind that owns its record type.
- **An unreadable change is kept, never dropped.** It is staged under `SPACE_UNREADABLE` with its
  envelope and the reason it could not be read.
- **A deletion is staged against the record it names**, whether the device applied that record or
  still has it waiting. A deletion also settles any unreadable version of the same record, so it
  does not come back.

| Why a change is unreadable                                                           | Recorded reason         |
| ------------------------------------------------------------------------------------ | ----------------------- |
| The envelope's format version is not one this build reads                            | `UNSUPPORTED_FORMAT`    |
| It was sealed with a key this device does not hold                                   | `KEY_RECOVERY_REQUIRED` |
| It fails authentication, its padding or payload is malformed, or it fails its schema | `CORRUPT_PAYLOAD`       |
| Its record type is one this build does not sync                                      | `UNSUPPORTED_FORMAT`    |

Unreadable changes are opened again at the start of a later download, but only when something that
could make them readable has changed: the device holds a different sealing key, or the build reads
a different envelope version, set of record types or set of schemas. A later readable version of
the same record replaces the unreadable one.

If a page reports more changes but its cursor has not advanced, the download stops with
`SPACE_CURSOR_STALLED` rather than looping.

### When the cursor is too old

The server purges deletion markers after a retention period, 30 days unless the operator changes
`SYNC_TOMBSTONE_RETENTION_DAYS`. A device whose cursor is older than a purged deletion can no
longer learn from the feed what was removed, so the server answers `CursorTooOld`.

The device then reconciles. It resets the cursor to zero, lists the whole space again and stages
everything it finds. When the listing ends, it stages a deletion for every record it knows, either
applied or waiting, that the listing no longer contains. Local data is not wiped first. Records
that still exist are recognized by their CIDs and skipped.

### Applying staged records

[`SpaceStagedRecords`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceStagedRecords.kt)
hands a kind's staged rows to that kind's download path. It groups the rows by the item
they belong to and builds each item from its changed records on top of the local copy, so a new
transcript changes a note's transcript and leaves the rest of the note alone. Records that cannot
be joined yet wait: a transcript whose entry has not arrived is not applied until it does.

The version of each record is written to `sync_space_records` only when the item has been applied,
inside the same database transaction. The ordering matters. If the device recorded a version as
soon as it downloaded it, an edit made locally before the download was applied would be uploaded
with the new version as its swap condition and would overwrite a change the person had not seen
yet. Recording it late makes that edit conflict instead.

## Conflicts

The server never merges anything. It replaces a whole record if the swap condition holds and
refuses otherwise. All resolution happens on the device, with the conflict resolver of each kind.

When a write is refused, the uploader reads every record of the item as other devices left them,
opens and checks them, builds the item they make, and asks the kind's resolver to decide.

| Resolver's answer               | What the uploader does                                                      |
| ------------------------------- | --------------------------------------------------------------------------- |
| Keep this device's version      | Writes again over the versions it just read                                 |
| Merge                           | Stores the merged item locally and writes it over the versions it just read |
| Keep the other device's version | Stores it locally, records its versions and settles the outbox entry        |
| The person has to decide        | Lists the conflict for review and stops retrying this entry                 |

An item is written at most three times in one attempt. If other devices keep changing it, the
entry fails with a conflict and is retried later.

Two further cases have their own rules.

- **Edited here, deleted elsewhere.** The local edit is kept and the conflict is listed for
  review.
- **Deleted here, edited elsewhere.** The kind's resolver decides. If the edit wins, it is stored
  locally and the conflict is listed. Otherwise the delete is retried over the versions just read.

Some kinds avoid conflicts by construction, and those rules are part of the record design:

- **Journal merges** are done by the device that merges
  ([`JournalMergeUploader`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/JournalMergeUploader.kt)).
  It writes the merged-away journal's own record again with `mergedInto` naming the surviving
  journal, and every device that reads that record applies the same merge. A merge made elsewhere
  wins over an edit made here. The server's merge route is not used for an account in its space.
- **Records two devices may both create** are keyed by what they connect, so both devices write
  the same key and the second write meets the first.
- **Location samples** are only ever added to and deleted from, so two copies of an hour are
  merged: samples from either, minus ids deleted in either.

The reasoning behind those three is in the [lexicon decisions](./lexicons.md#decisions).

## Errors

Every failure of a space call is translated into the sync engine's own terms
([`SpaceFailures.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/space/SpaceFailures.kt)),
so the same paths that handle per-kind sync failures handle these. Nothing is swallowed.

| What happened                                                                         | How the sync engine sees it                                                  | What follows                                                                                                   |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| The access token expired (`401`)                                                      | Authentication error                                                         | The token is refreshed and the call is made again                                                              |
| Rate limited (`429 RateLimitExceeded`)                                                | Server error, retryable                                                      | Uploads and downloads both stop until the server's `Retry-After` has passed, or for one minute if it gave none |
| The server is moving as many files as it can (`ServerBusy`)                           | Server error that carries the server's `Retry-After`                         | Retried later                                                                                                  |
| The storage quota is full (`QuotaExceeded`, or status `507`)                          | `STORAGE_QUOTA_EXCEEDED`, a storage error                                    | Shown as a full quota. Deleting still works                                                                    |
| The file is too large (`BlobTooLarge`, or `413` on an upload)                         | Media-too-large error                                                        | Permanent for that file                                                                                        |
| The record is too large for the server (`413` on a record write)                      | Server error with the server's code, or `RECORD_TOO_LARGE` when it sent none | Reported for that entry. The record stays on the device                                                        |
| The request never got a response                                                      | `NETWORK_ERROR`                                                              | Retried                                                                                                        |
| The response could not be read                                                        | `INVALID_RESPONSE`                                                           | Reported as a server error                                                                                     |
| The server rejected the request as malformed (`400`)                                  | Server error                                                                 | Permanent. The entry is set aside in Sync Issues                                                               |
| A record this device built fails its schema, or a value cannot be written as a record | Permanent, unless the mapper marked it retryable                             | Set aside in Sync Issues, with the record and field named                                                      |
| The identity key is missing                                                           | Key recovery error                                                           | The person is asked to recover their key                                                                       |
| The signed-in account or server changed mid-sync                                      | Authentication error                                                         | The work stops and runs again for the new account                                                              |

A full quota is counted by the server across records and files in the space, and the space shares
the account's one storage limit with its other stored files. The apps read the used total and
the limit from the server's `GET /quota`. Any breakdown by kind shown in Settings is
computed on the device from local data, because the server cannot tell kinds apart.

## Moving an existing account into its personal space

An account created before personal spaces is stored in `legacy` mode. The server cannot convert
it, because it has no keys, so one of the account's devices does. The move is described from start
to finish in [Moving journals into personal spaces](../explanation/journal-moves.md), and its
calls, timings, refusal codes and stored state are in the
[journal moves reference](./journal-moves.md). This section covers only how it connects to the
transport above.

- **When it runs.** `SpaceJournalMigration` is tried after every complete sync. It goes ahead once
  both client flags are on, the server advertises `personalSpaceMigrationV1`, the device has
  finished a full download from the per-kind routes and has nothing waiting to upload, and it holds
  the identity key.
- **What it reuses.** It builds every record with the same `SpaceKind` code the uploader uses, and
  writes each one unconditionally, since nothing else writes to the space before the switch.
  Rewriting a record that is already there is harmless because a write replaces it whole.
- **What changes at the switch.** The server changes the account's storage mode. Each device sees
  the new mode the next time sync starts and its gate picks the space.
- **Version numbers are forgotten.** The per-kind routes number versions with large account-wide
  counters, and a space numbers them with small per-space sequence numbers. A version kept from the
  other store would make every incoming change look older than what the device has. When a device
  notices its route changed, it resets the sync versions it kept for notes and journals, so the
  next change is compared by content and time
  ([`SyncRouteRebase.kt`](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/SyncRouteRebase.kt)).
  The route last used is remembered per account and server.
- **An older app keeps its changes.** The per-kind routes answer an account that moved with
  `403 SYNC_FORMAT_UPGRADE_REQUIRED`, and any other non-legacy account with
  `403 SYNC_STORAGE_MODE_MISMATCH`. Both leave queued changes queued. The
  [sync API contract](../../server/docs/sync-v1-api.md#storage-modes) explains why these are `403`.
- **Switching back.** Until the server deletes the legacy copy, 30 days after the switch, the
  account can be switched back to the per-kind routes from Settings. The device downloads the
  space, queues every item for the per-kind routes and resets versions again.

## A linked watch

A watch linked to an account is one of the owner's devices, so it is allowed to write to the
account's personal space. It uses only the upload half of this transport, and only as a fallback.

By default a watch hands what it captures to its phone over the watch-to-phone connection, and the
phone syncs it like anything else. The watch uploads to the server itself only when the phone is
out of reach or has not backed a capture up in time, under the routing rules in the
[watch sync protocol](./wear/sync-protocol.md#routing-rules). When it does, it runs the same sync
engine with its own wiring
([`WearCloudSyncModule.kt`](../../app/wear/src/main/kotlin/app/logdate/wear/di/WearCloudSyncModule.kt))
and the `LinkedWatch` [sync profile](../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/SyncProfile.kt),
which narrows the transport as follows.

| Behavior                      | On a linked watch                                                                                                                                   | Why                                                                                    |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| Creations                     | Uploaded. A record the watch has no version for is written with `Absent`, as on any device, so a copy the phone uploaded first is never overwritten | The phone and the watch may both try to upload the same capture                        |
| Deletions                     | Uploaded                                                                                                                                            | A deletion needs no merge                                                              |
| Edits                         | Never uploaded from the watch. An edit stays in the watch's outbox for the phone to relay                                                           | Only the phone resolves an edit against what other devices wrote                       |
| Media with downloaded records | Not downloaded                                                                                                                                      | The phone supplies the watch's library                                                 |
| Drafts and location history   | Not synced                                                                                                                                          | The watch has neither                                                                  |
| Identity key                  | Never created on the watch. It uses the key its phone installed, and without one sync waits for the link                                            | A key the watch made itself would not open anything the account's other devices sealed |

The watch signs its calls with its own device token and passes through the same
`SpaceTransportGate`, with the same `PERSONAL_SPACE` flag and the same check of the server
description. It reads the account's DID and storage mode from `GET /auth/me` once, after its link
is installed. The upload-only pass it runs afterwards does not read the account again, so a watch
keeps the storage mode it saved at that point.

The server accepts a linked device's token on the space methods while its device session is active
and refuses third-party OAuth tokens and AT Protocol session tokens. The session rules are in the
[device sessions API](../../server/docs/device-sessions-api.md).
