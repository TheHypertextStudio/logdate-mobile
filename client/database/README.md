# `:client:database`

This module defines LogDate's on-device Room database: the schema, the data access objects, the type converters, every schema migration, and the code that opens the database safely on each platform. It holds the journals, notes, location history, rewinds, people, places, transcripts, the full-text search index and the sync outbox. [`:client:data`](../data/README.md) wraps the DAOs in repository implementations, and [`:client:sync`](../sync), `:client:location`, `:client:feature:core`, the three app modules and `:integration:server-client-e2e` depend on it as well.

## What it contains

Everything is under `app.logdate.client.database`.

| Package      | Contents                                                                                                                                                                                                                                                                                                                                                                                                 |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| (root)       | `LogDateDatabase`, the Room database (version 62, `DATABASE_VERSION`, with exported schemas), `LogDateDatabaseConstructor`, `databaseModule` (an `expect` Koin module) and `daosModule`, which exposes each DAO as a Koin singleton                                                                                                                                                                      |
| `entities`   | Room entities. Notes are split by kind (`TextNoteEntity`, `ImageNoteEntity`, `AudioNoteEntity`, `VideoNoteEntity`), next to `JournalEntity`, `LocationLogEntity`, `HistoryRecordEntity`, `PlaceEntity`, `UserPlaceEntity`, `TranscriptionEntity`, `EventEntity`, `StickerEntity`, `PostcardEntity` and others. Sub-packages group `journals`, `media`, `moments`, `people`, `rewind` and `sync` entities |
| `dao`        | One DAO interface per area, for example `JournalDao`, `JournalNotesDao`, `TextNoteDao`, `LocationHistoryDao`, `SearchDao`, `TranscriptionDao`, `PlaceDao`. Sub-packages hold `journals` (`JournalContentDao`, `JournalMergeDao`), `maintenance` (`IntegrityDao`), `media`, `moments`, `people`, `rewind` and `sync` (`SyncMetadataDao`, `DownloadInboxDao`, `PeerDeliveryDao`, `SpaceRecordStateDao`)    |
| `converters` | Room type converters: `TimestampConverter`, `UuidConverter`, `DurationConverter`, `StringListConverter`, `LocationDataConverter`, `MediaDimensionsConverter`                                                                                                                                                                                                                                             |
| `migrations` | One file per version step from `MIGRATION_12_13` to `MIGRATION_61_62`, plus `AppDatabaseMigrations.kt` for the earlier steps                                                                                                                                                                                                                                                                             |
| `encryption` | `generateDatabaseKey`, an `expect` function with a per-platform implementation, and on Android the passphrase storage described below                                                                                                                                                                                                                                                                    |

`SearchIndexBootstrapper` maintains an FTS5 virtual table (`entries_fts`) through SQL triggers defined in `SearchIndexContentTriggers.kt` and `SearchIndexContextTriggers.kt`. The triggers watch the note tables and the tables for journals, media captions, transcriptions, audio tags, places, postcards, rewinds and stickers. The bootstrapper creates the index and refreshes the triggers when the search index schema version differs from the one in the code. `SearchDao.rebuildIfNeeded` refills the index from existing content when an older version built it or when it is empty while content exists. If the SQLite build has no FTS5, it logs a warning and leaves search disabled instead of failing.

`NewerDatabaseVersionException` is thrown when the journal on disk was written by a newer app version than this build supports. The files and keys are left untouched.

## Schema and migrations

Room exports each schema version as JSON into `schemas/app.logdate.client.database.LogDateDatabase/`, configured by `schemaDirectory` in `build.gradle.kts`. A change to an entity needs a new `DATABASE_VERSION`, a matching `MIGRATION_<from>_<to>` file in `migrations`, and the exported JSON for the new version. The migration tests in `jvmTest` run against the bundled SQLite driver.

## Platform storage

Each platform opens the database differently.

- **Android** opens the database with SQLCipher (`sqlcipher-android`). The passphrase is stored in secure storage, with a recovery copy written by `PassphraseBackupStore` under `filesDir/db_recovery/` so a restored device can still open the journal. Installs that predate encryption have their plaintext database migrated to SQLCipher, and the original files are moved to timestamped backups first. The source set also contains the helpers that the native backup and transfer code in `:client:data` relies on: `openRetainedDatabaseCopy`, `captureNativeJournalDatabaseSnapshot`, `verifyNativePrivacyBeforeDatabaseOpen` and `requirePortableCloudBackupKey`.
- **Desktop (JVM)** keeps the database under the user's home directory. `DesktopDatabaseVault` decrypts a sealed copy at launch and `scheduleDatabaseEncryption` seals the closed database when the app exits. A sealed copy that cannot be decrypted stops startup with `EncryptedDatabaseUnavailableException` and is never replaced.
- **iOS** creates the database in the user's documents directory.

## How it fits with other modules

The module depends on `:client:device`, `room-runtime`, `sqlite-bundled`, Koin and Napier. It exposes `room-runtime` and kotlinx-datetime to its consumers through `api`. Room's compiler runs through KSP for the Android, JVM and both iOS targets.

## Platforms

Android, JVM (`jvm()`), `iosArm64` and `iosSimulatorArm64`.

## Tests

```bash
./gradlew :client:database:jvmTest
./gradlew :client:database:testAndroidHostTest
```

`jvmTest` holds the migration tests and the DAO tests that run against SQLite on the JVM, including the desktop vault. `commonTest` contains structural checks of the entities, DAO interfaces and configuration, and these run as part of `jvmTest`. `testAndroidHostTest` runs the Android host tests for the passphrase recovery, the privacy preflight, the journal fence, the cloud backup key and database startup recovery.

The `androidDeviceTest` source set contains instrumented tests (`DatabaseIntegrationTest`, `DatabaseMigrationIntegrationTest`) that need an Android device. Run device tests only on Gradle Managed Devices, as described under [Devices](../../docs/DEVELOPMENT.md#devices) in the development guide.
