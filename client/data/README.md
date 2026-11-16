# `:client:data`

This module implements the repository interfaces declared in [`:client:repository`](../repository/README.md). Each implementation reads and writes the Room database from [`:client:database`](../database/README.md), the preferences and key-value storage from [`:client:datastore`](../datastore/README.md), or both, and hands changes to [`:client:sync`](../sync) so they reach the server. The Koin modules in this module bind the implementations to their interfaces. The app modules, `:client:feature:core` and the end-to-end integration tests load those modules.

Most repositories here are "offline-first": a write lands in local storage first, the pending upload is recorded in the same transaction, and the sync layer sends it later. Features depend on the interfaces in `:client:repository`, so they work with local data whether or not a server is reachable.

## What it contains

The code lives under `app.logdate.client.data`. Each package holds the implementations for one area of the product.

| Package                                                                      | Main types                                                                                                                                                                                                                                                                                                                                                                          |
| ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `journals`                                                                   | `OfflineFirstJournalRepository` (journals, plus the sync-side operations of `SyncableJournalRepository` and `SyncableDraftRepository`), `OfflineFirstJournalContentRepository`, `OfflineFirstJournalUserDataRepository`, `LocalFirstDraftRepository` (drafts stored independently of sign-in), `RemoteJournalDataSource` and its `NoOpJournalDataSource`, `ArchiveRecoveryReceipts` |
| `notes`                                                                      | `OfflineFirstJournalNotesRepository` (text, image, audio and video notes with captions, places, transcripts and journal memberships), `JournalNoteExporter` and `JournalContentBackup` (JSON export of notes and memberships), `StoredMediaReferenceMigration` (rewrites stored file paths into `logdate-media://` references)                                                      |
| `notes.drafts`                                                               | `OfflineFirstEntryDraftRepository` and the `LocalEntryDraftStore` interface it persists through                                                                                                                                                                                                                                                                                     |
| `account`                                                                    | `DefaultPasskeyAccountRepository` and its coordinators for passkey, Google and restore-credential sign-in, `DefaultAccountRepository`, `DefaultAccountIdentityRepository`, `CanonicalOwnerBindingGuard`, `HistoryOwnerAdoption`, `ServerScopedAccounts`                                                                                                                             |
| `location`                                                                   | `OfflineFirstLocationHistoryRepository`, `OfflineFirstActivityHistoryRepository`, `RoomHistoryRecordStore`                                                                                                                                                                                                                                                                          |
| `people`, `places`, `profile`                                                | `OfflineFirstPeopleRepository`, `OfflineFirstPeopleGraphRepository`, `OfflineFirstUserPlacesRepository`, `OfflineFirstProfileRepository`                                                                                                                                                                                                                                            |
| `rewind`                                                                     | `OfflineFirstRewindRepository`, `DefaultRewindGenerationManager`, `OfflineFirstReflectionPromptResponseRepository`                                                                                                                                                                                                                                                                  |
| `search`                                                                     | `OfflineFirstSearchRepository` (FTS5 queries through `SearchDao`), `DataStoreRecentSearchesRepository`                                                                                                                                                                                                                                                                              |
| `transcription`                                                              | `OfflineFirstTranscriptionRepository`                                                                                                                                                                                                                                                                                                                                               |
| `audio`, `events`, `media`, `moments`, `streak`, `user`, `quota`, `timeline` | One implementation per repository interface, for example `OfflineFirstAudioTagRepository`, `OfflineFirstEventRepository`, `OfflineIndexedMediaRepository`, `OfflineFirstMarkedMomentRepository`, `DefaultStreakSettingsRepository`, `OfflineFirstUserStateRepository`                                                                                                               |
| `maintenance`                                                                | `DataIntegrityService` audits and repairs sync-related inconsistencies in local storage, and `LocationOwnershipRepair` reassigns location rows that still carry placeholder owner ids                                                                                                                                                                                               |
| `di`                                                                         | `dataModule` (an `expect` Koin module with one `actual` per platform) and `appDataModule`, which adds `syncModule`                                                                                                                                                                                                                                                                  |

`OfflineFirstEntryDraftRepository` keeps drafts in memory as a `StateFlow` and writes each change through a `LocalEntryDraftStore`. The store is per platform: `AndroidLocalEntryDraftStore` uses DataStore, `DesktopLocalEntryDraftStore` writes files under `~/.logdate/drafts`, and `IosLocalEntryDraftStore` uses `NSUserDefaults`. `OfflineFirstActivityTimelineRepository` keeps its items in memory only.

### Android restore

The Android source set also contains the `restore` package, which handles restoring a journal after a reinstall or a device transfer. `NativeAccountBackupAgent` is the backup agent, and it records a request that a system job later turns into a sign-in. `NativeJournalTransferSnapshot` and `NativeJournalTransferRestore` capture and reinstall the encrypted journal. `RetainedJournalDatabaseMerger` replays a preserved encrypted database into the active repositories, and `scheduleRetainedJournalReconciliation` runs that work as a persisted `JobScheduler` job that needs no network. `AndroidMediaOwnerCensus` lists the media files that notes, journals and drafts still reference.

Android also has its own search and contacts implementations: `AndroidPlatformSearchRepository` queries the platform AppSearch index and falls back to `OfflineFirstSearchRepository`, and `AndroidDeviceContactsReader` reads device contacts.

## How it fits with other modules

`build.gradle.kts` declares these project dependencies: `:shared:model`, `:shared:config`, `:shared:atproto-plc`, `:client:domain`, `:client:repository`, `:client:database`, `:client:datastore`, `:client:device`, `:client:networking`, `:client:permissions`, `:client:sync`, `:client:util` and `:client:media`. Android adds AndroidX AppSearch, and desktop adds `kotlinx-coroutines-swing`.

## Platforms

The module targets Android, desktop (the `desktop` JVM target), `iosArm64` and `iosSimulatorArm64`. Platform behaviour differs where the source sets say so. Desktop builds bind `UnavailableAccountRepository` and `UnavailablePasskeyAccountRepository`, iOS wraps passkey sign-in in `PasskeyBackedAccountRepository`, and Android supplies the restore, AppSearch and contacts code described above.

## Tests

Shared tests live in `commonTest`, with fakes under `fakes`. Run them with:

```bash
./gradlew :client:data:desktopTest
./gradlew :client:data:testAndroidHostTest
```

`desktopTest` runs the `commonTest` and `desktopTest` sources on the JVM, and the SQLite-backed tests there use the bundled SQLite driver. `testAndroidHostTest` runs the `androidHostTest` sources, which cover the native restore and Android draft store code, with Robolectric. The `iosTest` source set holds a draft store test.
