package app.logdate.client.sync.location

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.device.crypto.activityHistoryRecordId
import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Copies bounded ingestion pages into the durable outbox before advancing import cursors. */
class LocationHistoryStager(
    private val locationPage: suspend (String, String, Instant, String, Int) -> List<LocationHistoryItem>,
    private val activityPage: suspend (String, String, Instant, String, Int) -> List<ActivityHistoryItem>,
    private val store: HistoryRecordStore,
    private val cursors: KeyValueStorage,
    private val deviceId: () -> String,
    private val locationPageBySequence: (suspend (String, String, Long, String, Int) -> List<LocationHistoryItem>)? = null,
) {
    suspend fun stage(
        ownerId: String,
        origin: String,
    ): Boolean {
        val device = deviceId()
        var more = false
        for (sourceOwner in listOf(ownerId, "default_user").distinct()) {
            val locationsRemain =
                if (locationPageBySequence != null) {
                    stageBySequence(ownerId, origin, sourceOwner, device)
                } else {
                    stageSource(ownerId, origin, sourceOwner, device, "observation") { cursor ->
                        locationPage(sourceOwner, device, Instant.fromEpochMilliseconds(cursor.millis), cursor.id, PAGE_SIZE)
                            .map { item ->
                                check(item.userId == sourceOwner && item.deviceId == device)
                                Capture(item.sampleId, item.loggedAt, HistoryPayload.Observation(item.toObservation(ownerId)))
                            }
                    }
                }
            val activitiesRemain =
                stageSource(ownerId, origin, sourceOwner, device, "activity") { cursor ->
                    activityPage(sourceOwner, device, Instant.fromEpochMilliseconds(cursor.millis), cursor.id, PAGE_SIZE)
                        .map { item ->
                            check(item.userId == sourceOwner && item.deviceId == device)
                            Capture(
                                item.id,
                                item.recordedAt,
                                HistoryPayload.Activity(
                                    ActivityObservation(
                                        item.id,
                                        ownerId,
                                        device,
                                        item.timestamp,
                                        item.activityType,
                                        item.transitionType,
                                        item.timeZoneId,
                                    ),
                                ),
                            )
                        }
                }
            more = more || locationsRemain || activitiesRemain
        }
        return more
    }

    private suspend fun stageBySequence(
        owner: String,
        origin: String,
        sourceOwner: String,
        device: String,
    ): Boolean {
        val pageLoader = locationPageBySequence ?: return false
        val key = "history.import.v2:${origin.length}:$origin:$owner:$sourceOwner:$device:observation"
        var cursor = cursors.getString(key)?.let { Json.decodeFromString<SequenceCursor>(it) } ?: SequenceCursor()
        repeat(PAGES_PER_SOURCE) {
            val page = pageLoader(sourceOwner, device, cursor.seq, cursor.id, PAGE_SIZE)
            check(page.size <= PAGE_SIZE)
            page.forEach { item ->
                check(item.userId == sourceOwner && item.deviceId == device)
                val id = "observation:${item.sampleId}"
                val existing = store.record(owner, origin, id)
                if (existing?.deleted == true) {
                    store.put(owner, origin, existing)
                } else if (existing == null) {
                    val payload = HistoryPayload.Observation(item.toObservation(owner))
                    store.put(
                        owner,
                        origin,
                        HistoryRecord(
                            id,
                            "observation",
                            Json.encodeToString<HistoryPayload>(payload),
                            device,
                        ),
                    )
                }
            }
            if (page.isNotEmpty()) {
                val last = page.last()
                cursor = SequenceCursor(last.ingestionSeq, last.sampleId)
                cursors.putString(key, Json.encodeToString(cursor))
            }
            if (page.size < PAGE_SIZE) return false
        }
        return true
    }

    private suspend fun stageSource(
        owner: String,
        origin: String,
        sourceOwner: String,
        device: String,
        kind: String,
        load: suspend (ImportCursor) -> List<Capture>,
    ): Boolean {
        val key = "history.import.v1:${origin.length}:$origin:$owner:$sourceOwner:$device:$kind"
        var cursor = cursors.getString(key)?.let { Json.decodeFromString<ImportCursor>(it) } ?: ImportCursor()
        repeat(PAGES_PER_SOURCE) {
            val page = load(cursor)
            check(page.size <= PAGE_SIZE)
            page.forEach { capture ->
                val id = if (kind == "activity") activityHistoryRecordId(capture.id) else "$kind:${capture.id}"
                val existing = store.record(owner, origin, id)
                if (existing?.deleted == true) {
                    // Reapply deletion if an in-flight capture arrived after the original tombstone.
                    store.put(owner, origin, existing)
                } else if (existing == null) {
                    store.put(
                        owner,
                        origin,
                        HistoryRecord(
                            id,
                            kind,
                            Json.encodeToString<HistoryPayload>(capture.payload),
                            device,
                        ),
                    )
                }
            }
            // A later sweep must revisit rows behind the watermark (clock rollback or late same-ms inserts).
            // Indexed ID lookups preserve prior edits and tombstones without loading the complete history.
            cursor =
                if (page.size < PAGE_SIZE) {
                    ImportCursor()
                } else {
                    val last = page.last()
                    ImportCursor(last.ingestedAt.toEpochMilliseconds(), last.id)
                }
            cursors.putString(key, Json.encodeToString(cursor))
            if (page.size < PAGE_SIZE) return false
        }
        return true
    }

    private data class Capture(
        val id: String,
        val ingestedAt: Instant,
        val payload: HistoryPayload,
    )

    @Serializable
    private data class ImportCursor(
        val millis: Long = Instant.DISTANT_PAST.toEpochMilliseconds(),
        val id: String = "",
    )

    @Serializable
    private data class SequenceCursor(
        val seq: Long = 0L,
        val id: String = "",
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val PAGES_PER_SOURCE = 2
    }
}

private fun LocationHistoryItem.toObservation(ownerId: String) =
    LocationObservation(
        id = sampleId,
        ownerId = ownerId,
        deviceId = deviceId,
        timestamp = timestamp,
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        activity =
            when (activityType?.uppercase()) {
                "STILL" -> TravelMode.STILL
                "WALKING", "ON_FOOT" -> TravelMode.WALKING
                "RUNNING" -> TravelMode.RUNNING
                "ON_BICYCLE", "CYCLING" -> TravelMode.CYCLING
                "IN_VEHICLE" -> TravelMode.VEHICLE
                else -> TravelMode.UNKNOWN
            },
        timeZoneId = timeZoneId,
        source = captureSource.name,
        isMock = isMock,
        provider = provider,
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
        verticalAccuracyMeters = verticalAccuracyMeters,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )
