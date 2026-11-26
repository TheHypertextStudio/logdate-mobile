# Data Models, Room Migrations, and Sync Contracts

This specification governs schema and wire-format changes for storage, sync, and server engineers across `logdate-mobile`, `logdate-apple`, and `logdate-linux`. Maintain strict backward compatibility with `server` migration `V29` ([V29__Add_encrypted_location_history.sql](../../../server/src/main/resources/db/migration/V29__Add_encrypted_location_history.sql)) and older clients decrypting `LDSE2` records at `payloadSchemaVersion = 1`.

## Server Contract Compatibility (`/api/v1/location-history`)

The server contract in [Location history API](../../../server/docs/location-history-api.md) requires zero database migrations or route changes:

- `LocationHistoryRoutes.kt` validates only the outer envelope (`id`, `recordType`, `LDSE2:` prefix, `payloadSchemaVersion > 0`, `deviceVersion`, `expectedServerVersion`, `deleted`) and keeps `payload` opaque.
- Because `payloadSchemaVersion` stays `1` and all new `HistoryPayload` fields use nullable or default-valued properties with `Json { ignoreUnknownKeys = true }`, existing Android installs continue syncing without conflict errors or server upgrades.

## Additive `LDSE2` Payload Evolutions

Apply these additive changes to [LocationHistory.kt](../../../shared/model/src/commonMain/kotlin/app/logdate/shared/model/location/LocationHistory.kt):

### `LocationObservation` Additions

```kotlin
@Serializable
data class LocationObservation(
    val id: String,
    val ownerId: String,
    val deviceId: String,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val activity: TravelMode = TravelMode.UNKNOWN,
    val timeZoneId: String? = null,
    val source: String = "UNKNOWN",
    val isMock: Boolean = false,
    val provider: String? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val elapsedRealtimeNanos: Long? = null,
)
```

### `SemanticPlace` Additions

```kotlin
@Serializable
enum class PlaceCategory {
    HOME,
    WORK,
    FOOD,
    COFFEE,
    SHOPPING,
    OUTDOORS,
    FITNESS,
    TRANSIT,
    LODGING,
    HEALTH,
    ENTERTAINMENT,
    OTHER,
}

@Serializable
data class SemanticPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val locality: String? = null,
    val externalId: String? = null,
    val userConfirmed: Boolean = false,
    val radiusMeters: Double = 75.0,
    val category: String? = null,
)
```

Store `category` as a nullable `String?` mapped via `PlaceCategory.entries.firstOrNull { it.name == category }` rather than a closed enum in `SemanticPlace` so unknown categories from future clients never throw `SerializationException`.

### Forward-Compatible `HistoryCorrection` Fields

Older clients decode `HistoryPayload.Correction` using `kotlinx.serialization`'s default enum serializer, which throws on unknown enum names and causes `decode(record)` to silently drop the payload—or worse, causes `LocationHistorySyncEngine` to fail a page if strict checks apply. Prevent crashes by wrapping `HistoryField` in a custom serializer or adding an `UNKNOWN` fallback (`HistoryCorrection.fieldKey: String` or `@Serializable(with = SafeHistoryFieldSerializer::class)`) and rolling out the decoder update in Stage 1B before any writer emits the five new correction types:

| Field Key | Target Prefix | Value Format | Semantics |
| --- | --- | --- | --- |
| `SUGGESTED_PLACE` | Visit anchor ID | `SemanticPlace.id` | Unconfirmed enrichment link; yields to any explicit `PLACE` correction |
| `RADIUS` | `place:<id>` | Double string (`"150.0"`) | Updates `SemanticPlace.radiusMeters` via immutable correction log |
| `CATEGORY` | `place:<id>` | Category name (`"HOME"`) | Updates `SemanticPlace.category` |
| `MERGE_WITH` | Visit anchor ID | Target visit anchor ID | Combines two adjacent fragmented stays into one visit spanning `[minStart, maxEnd]` |
| `SPLIT_AT` | Visit anchor ID | ISO-8601 `Instant` | Splits one reconstructed stay into two visits at the chosen wall time |

## Room Migration `52 -> 53` (`client/database`)

`LogDateDatabase` is currently at version `52` ([LogDateDatabase.kt](../../../client/database/src/commonMain/kotlin/app/logdate/client/database/LogDateDatabase.kt#L220)). Add `MIGRATION_52_53` (`52 -> 53`) covering capture metadata, place categories, persistent place caching, and fast calendar date lookups:

```sql
ALTER TABLE location_logs ADD COLUMN provider TEXT;
ALTER TABLE location_logs ADD COLUMN speed_accuracy_mps REAL;
ALTER TABLE location_logs ADD COLUMN vertical_accuracy_meters REAL;
ALTER TABLE location_logs ADD COLUMN elapsed_realtime_nanos INTEGER;
ALTER TABLE location_logs ADD COLUMN ingestion_seq INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS index_location_logs_user_device_seq
    ON location_logs(user_id, device_id, ingestion_seq, sample_id);

ALTER TABLE user_places ADD COLUMN category TEXT;

CREATE TABLE IF NOT EXISTS place_resolution_cache (
    cell_x INTEGER NOT NULL,
    cell_y INTEGER NOT NULL,
    result_kind TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    resolved_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    PRIMARY KEY (cell_x, cell_y)
);
CREATE INDEX IF NOT EXISTS index_place_resolution_cache_expires
    ON place_resolution_cache(expires_at_ms);
```

Update `LocationLogEntity` ([LocationLogEntity.kt](../../../client/database/src/commonMain/kotlin/app/logdate/client/database/entities/LocationLogEntity.kt)) so inserts populate `ingestionSeq` from an atomic monotonic counter or SQLite trigger/sequence, and add a Room `Query` over `history_records` and `location_logs` for `ObserveRecordedDaySummariesUseCase` so the 7-day strip and calendar picker fetch recorded dates without decoding full day payloads.

## Eliminating Repeated Full-History Backfill Sweeps (`LocationHistoryStager`)

[LocationHistoryApi.md](../../../server/docs/location-history-api.md#L19-L21) notes that [LocationHistoryStager.kt](../../../client/sync/src/commonMain/kotlin/app/logdate/client/sync/location/LocationHistoryStager.kt#L102-L111) resets its import cursor to `Instant.DISTANT_PAST` after every completed sweep and re-reads all local history on the next sync pass.

Fix that O(N^2) re-scan while preserving clock-rollback safety:

1. Add a local `ingestion_seq` column (`INTEGER`) populated strictly monotonically at `insert` time (either via an `AUTOINCREMENT` side sequence or `MAX(ingestion_seq) + 1` inside the insert transaction, independent of `Clock.System.now()`).
2. Advance `LocationHistoryStager` pages ordered by `(ingestion_seq, sample_id)` and persist the highest committed `ingestion_seq` watermark instead of resetting to `DISTANT_PAST`.
3. Keep an explicit manual/weekly integrity reconciliation only on user-triggered full backup verification; routine sync runs stay strictly O(new rows).

## Shared Cross-Platform Reconstruction Test Vectors

To guarantee that Kotlin (`logdate-mobile`), Swift (`logdate-apple` in Stages 2–3), and Rust (`logdate-linux` in Stage 4) compute identical visit boundaries, centroids, and travel modes, check in a shared JSON fixture directory at `shared/test-vectors/location-history-v4/` (mirrored or submoduled into `logdate-apple` tests).

Each fixture file (`01_desk_wifi_drift.json` through `12_multi_device_fusion.json`) follows one strict schema:

```json
{
  "schemaVersion": 4,
  "zoneId": "America/Los_Angeles",
  "places": [
    { "id": "place-cafe", "name": "Cedar Street Coffee", "latitude": 37.7749, "longitude": -122.4194, "radiusMeters": 60.0, "userConfirmed": true }
  ],
  "activities": [
    { "id": "act-1", "ownerId": "user-1", "deviceId": "phone-1", "timestamp": "2026-10-12T15:00:00Z", "activityType": "STILL", "transitionType": "ENTER" }
  ],
  "observations": [
    { "id": "obs-1", "ownerId": "user-1", "deviceId": "phone-1", "timestamp": "2026-10-12T15:02:00Z", "latitude": 37.77491, "longitude": -122.41941, "accuracyMeters": 14.0 }
  ],
  "corrections": [],
  "expected": {
    "items": [
      {
        "kind": "PlaceVisit",
        "id": "user-1:phone-1:obs-1",
        "start": "2026-10-12T15:02:00Z",
        "end": "2026-10-12T15:42:00Z",
        "confirmedStay": true,
        "placeId": "place-cafe",
        "latitude": 37.77491,
        "longitude": -122.41941,
        "toleranceMeters": 5.0
      }
    ]
  }
}
```

Stage 2 (`logdate-apple`) cannot be marked verified until its Swift `ReconstructLocationDay` suite passes every vector in `shared/test-vectors/location-history-v4/`.
