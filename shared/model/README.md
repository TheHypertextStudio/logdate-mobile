# `:shared:model`

This module defines the data types that the LogDate clients and the server share: journals, entry drafts, places and locations, rewinds, account and passkey payloads, sync requests and responses, and diagnostics. Because the server and every client compile the same classes, a change to a wire format is made once and seen by both sides.

## What it contains

Most types are `@Serializable` and use `kotlinx.serialization`. The source is under `src/commonMain/kotlin/app/logdate`.

### `app.logdate.shared.model`

| Area                      | Main types                                                                                                                                                                                                                                                  |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Journaling content        | `Journal`, `EditorDraft` with the `SerializableEntryBlock` family (text, handwriting, image, video, audio and camera blocks), `HandwritingDocument` with `HandwritingStroke` and `HandwritingPoint`, `LibraryItem`, `PhotoPresentation`, `EditorInstanceId` |
| People, places and events | `Person` and the inferred-person types (`InferredPersonCluster`, `PersonLink`), `Place` (a sealed class with a `UserDefined` case), `Location`, `Event`, `Audience` (`LimitedAudience`, `PublicAudience`)                                                   |
| Rewinds                   | `Rewind` and `RewindMetadata`, the sealed `RewindContent`, `RewindGenerationRequest`, `WeekNarrative`, `YearNarrative`, `NarrativeOrigin`, `ReflectionPromptResponse`                                                                                       |
| Activity                  | `ActivityTimelineItem` and `ActivityUpdate`                                                                                                                                                                                                                 |
| Accounts and sign-in      | `LogDateAccount`, `CloudAccount`, `CloudAccountRepository`, the passkey option and response types in `Passkey.kt`, the email verification types in `EmailVerification.kt`, `RegisteredDevice`, `AccountStorageMode`                                         |
| Servers and plans         | `ServerDescriptor`, `ServerCapability`, `ServerProtocolFeature`, `DeploymentKind`, `PlanOption`, `EntitlementResponse`                                                                                                                                      |
| Cloud storage             | `CloudStorageQuota`, `CloudQuotaManager`, `CloudQuotaSnapshot`, `QuotaUsage`                                                                                                                                                                                |

### Sub-packages

| Package         | Contents                                                                                                                                                                                                           |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `devices`       | Device linking: `DeviceLinkCodeResponse`, `DeviceLinkRedeemRequest`, `DeviceLinkRedeemResponse`, `LinkedDevice`, and the `DeviceLinkErrorCodes` and `DeviceLinkPlatform` constants.                                |
| `diagnostics`   | Privacy-limited diagnostic reports: `SyncDiagnosticEvent`, `SyncDiagnosticReport`, `DiagnosticContext`, `DiagnosticRoute`, and `DiagnosticReportCodec`, which rejects unknown fields when decoding.                |
| `location`      | Location history: `LocationObservation`, `ActivityObservation`, `SemanticPlace`, `PlaceVisit`, `JourneyLeg`, `HistoryGap`, `HistoryCorrection`.                                                                    |
| `profile`       | `LogDateProfile` and the birthday helper `asBirthdayDateInstant`.                                                                                                                                                  |
| `sync`          | Sync wire types: `ContentUploadRequest`, `ContentUpdateRequest` and their responses, `DeviceId`, `VersionConstraint`, journal merge, location history sync, and the space migration types (`SpaceMigrationPhase`). |
| `transcription` | `CloudTranscriptionSessionRequest`, `CloudTranscriptionSessionResponse` and `CloudTranscriptionMode`.                                                                                                              |
| `user`          | `UserData` and `AppSecurityLevel`.                                                                                                                                                                                 |

### `app.logdate.util`

`UuidSerializer` serializes `kotlin.uuid.Uuid`. Annotate a `Uuid` property with `@Serializable(with = UuidSerializer::class)`, as `Journal.id` does.

## Conventions worth knowing

- Enums that cross the wire, such as `AccountStorageMode` and `SpaceMigrationPhase`, carry a `wireValue` and have an `UNRECOGNIZED` case. A value from a newer server decodes to `UNRECOGNIZED` instead of failing the whole payload.
- `ServerProtocolFeature` flags are additive, so older clients can ignore ones they do not know.
- `HandwritingDocument` stores ink in page coordinates and throws `UnsupportedHandwritingVersionException` for a version it cannot read.
- The diagnostics types carry only finite categories, counters and random correlation references, never hostnames, paths or message text.
- The module opts in to `kotlin.uuid.ExperimentalUuidApi` for all source sets.

## How it fits with other modules

The module has no project dependencies. It exposes `kotlinx-datetime` and `kotlinx-coroutines` as `api` dependencies and uses `kotlinx-serialization` internally. Many modules declare a dependency on it, among them `:shared:config`, `:app:compose-main`, `:client:data` and `:server`.

## Platforms

Kotlin Multiplatform with `commonMain` only, for `iosArm64`, `iosSimulatorArm64`, `jvm` and `wasmJs` (browser).

## Tests

The `commonTest` source set contains `HandwritingDocumentTest` and `UuidSerializerTest`. Run them on the JVM target with:

```
./gradlew :shared:model:jvmTest
```
