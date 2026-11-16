# `:client:sync`

This module keeps a person's journals, notes, drafts, media and location history in step across their devices and LogDate Cloud. It owns the upload and download engines, the outbox bookkeeping that remembers what is waiting to be sent, conflict resolution, end-to-end encryption of what leaves the device, the paired phone and watch protocol, and the platform schedulers that decide when a sync runs. Feature and app modules call it through the `SyncManager` interface and the Koin `syncModule`.

## What it contains

Everything is under `app.logdate.client.sync`. The package root holds the engine itself, and subpackages hold the pieces around it.

**Engine (package root)**

- `SyncManager` is the interface the rest of the app uses. It exposes `syncStatusFlow`, `sync`, `requestBackup`, `uploadPendingChanges`, `downloadRemoteChanges`, a method per kind of data (`syncContent`, `syncJournals`, `syncAssociations`, `syncDrafts`), `fullSync`, dead letter handling (`observeDeadLetters`, `retryDeadLetter`, `discardDeadLetter`) and `whilePaused`. `SyncResult` and `SyncStatus` are its result types.
- `DefaultSyncManager` coordinates a run through `SyncUploader`, `SyncDownloader`, the media recovery coordinator and the retry coordinator. `NoOpSyncManager` is the implementation for environments where sync is unavailable.
- `SyncDownloadEngine` runs the download, paginate and resolve sequence shared by journals and notes through a `ConflictResolver`. Drafts (`DraftDownloader`) and associations (`AssociationDownloader`) have their own download loops.
- `SyncRetryCoordinator` decides when a failed upload is tried again and when it becomes a dead letter, using the delay schedule in `SyncBackoff` (package `metadata`).
- `FullSyncScope` pins the account and server a full run started with, so each phase can check it is still working for the same owner.
- `SyncTransactionManager` wraps remote applies in a transaction. Android, desktop and iOS each have a `RoomSyncTransactionManager`, and desktop and iOS also have a no-op variant.

**Subpackages**

| Package       | What it holds                                                                                                                                                                                                                                                                                                                                          |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `cloud`       | `CloudApiClient` and `LogDateCloudApiClient`, the per-kind data sources (`CloudContentDataSource`, `CloudJournalDataSource`, `CloudMediaDataSource` and others), `SafeCloudTransport`, which records only allowlisted diagnostics, and conditional-create requests. `cloud/account` has `DefaultCloudAccountRepository` and the server account stores. |
| `metadata`    | `SyncMetadataService` and `EntityType`, the outbox of pending uploads, plus the dead letter, retry schedule, media reference and last error stores.                                                                                                                                                                                                    |
| `conflict`    | `ConflictResolver` and the journal, note and marked moment resolvers, `SyncConflictStore`, and deletion conflict recovery.                                                                                                                                                                                                                             |
| `recovery`    | `DownloadInbox`, where downloaded records wait to be applied, and the media recovery queues that fetch files after their notes arrive.                                                                                                                                                                                                                 |
| `crypto`      | `SyncPayloadCipher`, which seals records under the account's identity key and tags each with a key fingerprint, and `MediaPayloadCrypto`, which writes media in the chunked LDCE2 format and also reads LDCE1.                                                                                                                                         |
| `space`       | The personal space transport, described below.                                                                                                                                                                                                                                                                                                         |
| `location`    | `LocationHistorySyncEngine` and its stager, which move encrypted location history records.                                                                                                                                                                                                                                                             |
| `moments`     | Upload, download and apply for marked moments.                                                                                                                                                                                                                                                                                                         |
| `quota`       | `LogDateCloudQuotaManager`, the implementation of the shared `CloudQuotaManager`, and the calculators for cloud storage usage.                                                                                                                                                                                                                         |
| `migration`   | `MigrationManager`, which compares the cloud identity with the local one and migrates local data when they differ.                                                                                                                                                                                                                                     |
| `diagnostics` | Sync diagnostic recording, the consent-gated diagnostic report and private log files.                                                                                                                                                                                                                                                                  |
| `peer`        | The phone and watch pair: link exchange (`PhoneLinkExchange`, `WatchLinkExchange`), the shared state document and its `StateMerger`, delivery routing (`WatchSyncRouter`), and watch removal.                                                                                                                                                          |
| `datalayer`   | The Wear data layer message mappers and paths, with `datalayer/v2` holding the second protocol version (`WearProtocol`, `WearProtocolGate`).                                                                                                                                                                                                           |
| `continuity`  | The watch recording and playback companions.                                                                                                                                                                                                                                                                                                           |
| `di`          | `syncModule` (an `expect` module with one `actual` per platform), `conflictResolverModule`, `cloudModule`, `spaceSyncModule` and `diagnosticsModule`.                                                                                                                                                                                                  |

**Platform code**

- Android: `AndroidSyncManager` schedules WorkManager jobs, with a periodic sync every 15 minutes while the user is signed in, and immediate syncs for user-triggered work. `AndroidLogDateSyncWorker` runs them. `PeriodicSyncScheduleController` and `resumeMediaReadiness` re-schedule a full sync when a media policy change allows media to upload again, and the worker publishes `SyncResult.recoveredMediaScope` only when the same `FullSyncScope` is still current.
- Desktop and iOS: `ForegroundSyncManager` wraps `DefaultSyncManager`. It syncs while the app is running and signed in, retries a full sync when the network returns after a failed sync that was not an authentication error (connectivity changes are debounced by two seconds), and repeats on a 30 minute interval, or 60 minutes when the data usage policy is restricted. Both platforms bind it as the `SyncManager`.

## Personal space transport

An account can keep its data in a sealed personal space instead of the per-kind sync endpoints. Every kind of data then becomes an `app.logdate.*` record, encrypted on the device before it leaves, so the server stores only ciphertext. The code lives in `space/`, underneath the sync engine: the outbox, retries, dead letters, the download inbox and each kind's conflict resolver and apply step stay the same.

**When a device uses it.** `SpaceTransportGate` chooses a `SpaceRoute` from three facts: the `PERSONAL_SPACE` feature flag is on in this build, the connected server advertises the `personalSpaceV1` protocol feature, and the server reports the signed-in account's `AccountStorageMode`. A `PERSONAL_SPACE_V1` account syncs through the space, a `LEGACY` account syncs through the per-kind endpoints, and anything else blocks sync and shows the `ACCOUNT_STORAGE_ERROR` issue. Nothing syncs on a blocked route, so an account's data is never split between two stores.

**How a kind plugs in.** A `SpaceKind` pairs a `SpaceRecordMapper`, which converts between a local item and its record and does no storage or network work, with a `SpaceLocalRecords`, which loads the item an outbox entry names, offers the kind's conflict resolver and records an upload. Kinds are registered in `SpaceRecordRegistry`, which accepts only the entity types listed in `WIRED_ENTITY_TYPES`: journals, notes, associations, drafts, moments, places, place visits, place corrections, place entry links, place journeys and location samples. The mappers are in `space/mappers`. A kind can be added only after its downloader reads `SpaceStagedRecords`, so staged records always have something to apply them.

**Upload.** For each outbox entry, `SpaceUploader` builds the record, checks it against its schema, seals it and writes it with a swap condition: a create only if the record is absent, an update only if it still has the content identifier this device last saw. When another device changed the record first, the uploader reads that version, runs the kind's resolver and writes again. `SpaceSwapStore` keeps each record's last known version in the `sync_space_records` table.

**Download.** `SpaceDownloader` pages the space's change feed from one cursor, the `SPACE` checkpoint. It opens each change, checks it against its schema and stages it in the download inbox under its kind. A change this device cannot open or check is staged under `SPACE_UNREADABLE` with the reason (a missing key, a corrupt record or a record type from a newer version) and retried on later downloads. When the server reports that the cursor is older than the deletions it has purged, the downloader lists the whole space and stages a deletion for every record the device knows that the listing lacks.

**Media.** `SpaceMediaDataSource` encrypts media in LDCE2 with the account media key and streams it to the space as a blob. A note refers to the blob through a `SpaceMediaReference` that carries its content identifier, and a download checks that the bytes match it before decrypting.

**Errors.** `spaceCall` reports failures in the sync engine's own terms: an expired token is the `401` that refreshes the session, rate limiting keeps the server's `Retry-After`, a full quota is `STORAGE_QUOTA_EXCEEDED`, and a failure before any response is a retried network error.

**Moving an existing journal.** An account created before personal spaces starts on legacy storage, and one of its devices moves the journal into the space. The code is in `space/migration`, and [Moving journals into personal spaces](../../docs/explanation/journal-moves.md) explains the whole move. `SpaceJournalMigration` runs after every complete sync when the `PERSONAL_SPACE` and `PERSONAL_SPACE_MIGRATION` flags are on, the server advertises `personalSpaceMigrationV1`, and this device has downloaded everything and has nothing waiting to upload. `SpaceMigrationUpload` writes each record unconditionally and saves a `SpaceMigrationCheckpoint` every 25 records so a later run resumes. `MigrationMediaPreparer` gives each media file a space reference and stores the references in the checkpoint store rather than the real `MediaSyncRefStore`, because other devices still read legacy references until the switch. `SpaceMigrationRun` reads the space back (`SpaceMigrationReadBackCheck`, `SpaceMigrationMediaCheck`), compares legacy storage with this device (`LegacyComparison`) and asks the server to switch with a proof of both. After the switch, `settleAfterSwitch` adopts the moved media references and `rebaseForRoute` resets the sync versions kept for the other store. `JournalStorageMove` is what the app sees: a `JournalMoveState` for Settings, with `retry` and `switchBack` for the person's actions, and a `JournalMoveProblem` on every stop that names the kind of data and what fixes it.

## How it fits with other modules

The module depends on `:shared:model`, `:shared:config`, `:shared:space`, `:shared:atproto-repo`, `:shared:atproto-xrpc`, `:client:networking`, `:client:repository`, `:client:database`, `:client:datastore`, `:client:device`, `:client:media`, `:client:text` and `:client:util`, with `:client:notifications` added on Android. It uses Ktor for HTTP, the `cryptography-kotlin` library's optimal provider for encryption, Koin and Napier. Android adds WorkManager and Room.

`:client:domain`, `:client:data`, `:client:feature:core`, `:client:feature:journal`, `:client:screenshot-scenes`, `:integration:server-client-e2e` and the three app modules depend on it.

## Platforms

Android, desktop (the JVM target is named `desktop`) and iOS (`iosArm64` and `iosSimulatorArm64`). Source sets are `commonMain`, `androidMain`, `desktopMain` and `iosMain`, with tests in `commonTest`, `desktopTest` and `androidHostTest`.

## Running the tests

Shared and desktop tests run on the JVM, and Android scheduling tests run as host tests.

```bash
./gradlew :client:sync:desktopTest
./gradlew :client:sync:testAndroidHostTest
```

## Things to know

- The conversion tests in `commonTest` check number rules against known answers from `tools/lexicon-check/conversion-vectors.json`. The `generateConversionVectors` task embeds them as generated Kotlin in the `app.logdate.client.sync.space` package, because common tests also run on iOS, where there is no repository to read files from.
- `desktopTest` declares the server's error documentation directory (`server/src/main/kotlin/app/logdate/server/routes/docs`) as an input, because the error code contract test reads it.
- The Android module owns string resources for the backup worker's foreground notification, since Android holds this module responsible for that service.
- Download scope identifiers (owner and origin) are operational state and stay out of diagnostic reports.
- Giving a new record kind a mapper needs round-trip tests that prove every synced field survives.
