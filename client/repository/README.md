# `:client:repository`

This module is the contract between LogDate's client features and the storage and network code behind them. It declares the repository interfaces, the models that only those interfaces use, and a few small pieces of pure logic that sit next to them. Feature modules, `:client:domain`, `:client:sync` and the app modules depend on these interfaces, and [`:client:data`](../data/README.md) provides the implementations, so a feature never imports Room, DataStore or a network client.

The module has no platform code. All of it is `commonMain`.

## What it contains

Everything is under `app.logdate.client.repository`, and each package covers one area.

| Package                                   | Main types                                                                                                                                                                                                                                                                                                                                                                         |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `journals`                                | `JournalRepository`, `JournalNotesRepository` (with `JournalNote`, `NoteType`, `NoteCounts`), `JournalContentRepository` (which journals a note belongs to), `JournalUserDataRepository`, `ExportableJournalContentRepository`, the draft interfaces `DraftRepository`, `EntryDraftRepository` and `EntryDraftRecoveryRepository`, and the merge models in `JournalMergeModels.kt` |
| `journals` (sync)                         | `SyncableJournalRepository`, `SyncableJournalNotesRepository`, `SyncableJournalContentRepository` and `SyncableDraftRepository` add operations that apply server changes without queueing a new upload, such as `createFromSync` and `updateSyncMetadata`                                                                                                                          |
| `journals` (capture)                      | `CaptureDevice`, `CaptureActivity` and `CaptureTimeZoneProvider`. Each enum stores a permanent `wireName`, and `LenientCaptureDeviceSerializer` and `LenientCaptureActivitySerializer` read an unknown name as `null`, so a note written by a newer app version still decodes                                                                                                      |
| `account`                                 | `AccountRepository` (profile), `PasskeyAccountRepository` (sign-up and sign-in with passkeys), `AccountIdentityRepository` (AT Protocol identity and recovery keys), `PasskeyConfirmations`                                                                                                                                                                                        |
| `user`                                    | `UserStateRepository`, `RemoteUserAccountRepository`, and in `user.devices` `UserDeviceRepository` with `UserDevice` and `DeviceType`                                                                                                                                                                                                                                              |
| `profile`                                 | `ProfileRepository` for the local profile, which is available without a cloud account                                                                                                                                                                                                                                                                                              |
| `location`                                | `LocationHistoryRepository`, `ActivityHistoryRepository`, `HistoryRecordStore`, `HistoryOwnerAdoptionRepair`                                                                                                                                                                                                                                                                       |
| `places`, `moments`, `events`, `timeline` | `UserPlacesRepository`, `MarkedMomentRepository` (with `MarkedMoment` and `MarkState`) and its syncable variant, `EventRepository`, `ActivityTimelineRepository`                                                                                                                                                                                                                   |
| `knowledge`                               | `PeopleRepository`, `PeopleProfileRepository`, `PeopleContactsRepository`, `InferredPeopleRepository`, `PersonLinkRepository`, `DeviceContactsReader`                                                                                                                                                                                                                              |
| `media`, `audio`                          | `IndexedMediaRepository` (with `IndexedMedia`), `AudioTagRepository`                                                                                                                                                                                                                                                                                                               |
| `transcription`                           | `TranscriptionRepository`, `TranscriptDocument` and its segment, word and speaker types                                                                                                                                                                                                                                                                                            |
| `rewind`                                  | `RewindRepository`, `RewindGenerationManager`, `ReflectionPromptResponseRepository`                                                                                                                                                                                                                                                                                                |
| `search`, `streak`, `quota`               | `SearchRepository` with `SearchResult` and `SearchContentType`, `RecentSearchesRepository`, `StreakSettingsRepository`, `RemoteQuotaDataSource` with `QuotaResult`                                                                                                                                                                                                                 |
| `assistants`                              | The request and response types of the connected-assistant protocol (`AssistantProtocol`, `AssistantGrant`, `AssistantCapability`, `AssistantContentScope`, `AssistantProtocolLimits`)                                                                                                                                                                                              |

Repositories expose reads as `Flow` and writes as `suspend` functions. Some interface methods have default bodies, so an implementation that has nothing to add can skip them.

One type contains logic. `PasskeyConfirmations` holds the latest passkey confirmation from a sign-in and hands it out for four minutes by default, since the server accepts it for five. The assistant types validate and bound their input, and their `toString` implementations redact request contents.

## How it fits with other modules

The module depends on `:shared:model` and `:client:util`, plus kotlinx coroutines, datetime and serialization. Because it depends on no storage module, any module can use it. More than twenty modules depend on it, including `:client:data`, `:client:domain`, `:client:sync`, `:client:ui`, the `:client:feature:*` modules and the app modules.

## Platforms

Android, JVM (`jvm()`), `iosArm64` and `iosSimulatorArm64`.

## Tests

```bash
./gradlew :client:repository:jvmTest
```

The `commonTest` sources run as part of `jvmTest`. They cover the assistant protocol and metadata bounds, the capture device, activity and time zone types, video duration and `TranscriptDocument`.
