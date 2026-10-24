package app.logdate.client.sync.location

import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.client.sync.space.mappers.LocationHour
import app.logdate.client.sync.space.mappers.PlaceEntryLinkRecordMapper
import app.logdate.shared.model.location.HistoryPayload
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Which personal-space item a location history record belongs to. */
internal sealed interface HistoryRoute {
    /** A sample or activity change, which belongs to a part of the hour it happened in. */
    class Hour(
        val device: String,
        val hourStart: Instant,
    ) : HistoryRoute

    class Item(
        val type: EntityType,
        val id: String,
        val operation: PendingOperation,
    ) : HistoryRoute

    /** A record the space has no copy of and can't be told about, such as a deleted record whose time was never known. */
    data object NothingToUpload : HistoryRoute

    /** A kind of record that only the location history endpoints carry. */
    data object NotSyncedHere : HistoryRoute

    /** A record that no record key can name. */
    data object Unroutable : HistoryRoute
}

/** Finds the personal-space item each location history record belongs to. */
internal object HistoryRecordRoutes {
    private val json = Json { ignoreUnknownKeys = true }

    fun routeOf(record: HistoryRecord): HistoryRoute {
        val operation = if (record.deleted) PendingOperation.DELETE else PendingOperation.UPDATE
        return when (record.recordType) {
            "observation", "activity" -> {
                val at = record.observedAtMillis ?: payloadTime(record) ?: return HistoryRoute.NothingToUpload
                try {
                    val hour = LocationHour.hourOf(Instant.fromEpochMilliseconds(at))
                    LocationHour.itemId(record.deviceId, hour)
                    HistoryRoute.Hour(record.deviceId, hour)
                } catch (_: SpaceRecordMappingException) {
                    HistoryRoute.Unroutable
                }
            }

            "place" -> {
                HistoryRoute.Item(EntityType.PLACE, record.id, operation)
            }

            "manual" -> {
                HistoryRoute.Item(EntityType.PLACE_VISIT, record.id, operation)
            }

            "correction" -> {
                HistoryRoute.Item(EntityType.PLACE_CORRECTION, record.id, operation)
            }

            "journey" -> {
                HistoryRoute.Item(EntityType.PLACE_JOURNEY, record.id, operation)
            }

            "memory-link" -> {
                linkRoute(record, operation)
            }

            else -> {
                HistoryRoute.NotSyncedHere
            }
        }
    }

    private fun linkRoute(
        record: HistoryRecord,
        operation: PendingOperation,
    ): HistoryRoute {
        val link = payloadOf(record)?.let { (it as? HistoryPayload.MemoryLink)?.value } ?: return HistoryRoute.NothingToUpload
        return try {
            HistoryRoute.Item(
                EntityType.PLACE_ENTRY_LINK,
                PlaceEntryLinkRecordMapper.keyOf(link.noteId, link.targetEvidenceId).lowercase(),
                operation,
            )
        } catch (_: SpaceRecordMappingException) {
            HistoryRoute.Unroutable
        }
    }

    private fun payloadOf(record: HistoryRecord): HistoryPayload? =
        record.payload?.let { runCatching { json.decodeFromString(HistoryPayload.serializer(), it) }.getOrNull() }

    private fun payloadTime(record: HistoryRecord): Long? =
        when (val payload = payloadOf(record)) {
            is HistoryPayload.Observation -> payload.value.timestamp.toEpochMilliseconds()
            is HistoryPayload.Activity -> payload.value.timestamp.toEpochMilliseconds()
            else -> null
        }
}
