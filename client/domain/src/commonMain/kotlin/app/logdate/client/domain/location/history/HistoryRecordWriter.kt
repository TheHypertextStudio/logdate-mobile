package app.logdate.client.domain.location.history

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.ManualVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.VisitMemoryLink
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persists user edits to location history as local encrypted-sync [HistoryRecord] mutations (`place`,
 * `correction`, `memory-link`, `manual`, and tombstones).
 *
 * Auto-enrichment writes never overwrite a user-confirmed place or active `LABEL` correction, and deletions
 * emit both a `DELETE` correction and an underlying record tombstone preserving the original recording device.
 */
internal class HistoryRecordWriter(
    private val rawHistory: LocationHistoryRepository,
    private val store: HistoryRecordStore,
    private val owner: CanonicalOwnerProvider,
    private val device: DeviceIdProvider,
    private val config: LogDateConfigRepository,
    private val userPlaces: UserPlacesRepository,
    private val placeResolutionCache: PlaceResolutionCache?,
    private val json: Json,
) {
    suspend fun savePlace(place: SemanticPlace) {
        val (ownerId, origin) = scope()
        val payloads = store.records(ownerId, origin).filterNot { it.deleted }.mapNotNull(::decode)
        val previous = resolveExistingPlace(payloads, place.id)
        if (previous == null) {
            put(place.id, "place", HistoryPayload.Place(place))
            placeResolutionCache?.invalidateNear(place.latitude, place.longitude)
            return
        }
        val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
        val effective = applyPlaceLabels(listOf(previous), edits).single()
        val hasUserLabel = edits.any { it.field == HistoryField.LABEL && it.targetEvidenceId == "place:${place.id}" }
        if (!place.userConfirmed && (effective.userConfirmed || hasUserLabel)) return
        recordPlaceCorrections(place, effective)
        // Preserve previous.name on the base record when user-confirmed so name changes travel exclusively as
        // explicit LABEL corrections that participate in cross-device conflict detection.
        val retained = if (place.userConfirmed) place.copy(name = previous.name) else place
        put(place.id, "place", HistoryPayload.Place(retained))
        placeResolutionCache?.invalidateNear(retained.latitude, retained.longitude)
    }

    private suspend fun resolveExistingPlace(
        payloads: List<HistoryPayload>,
        placeId: String,
    ): SemanticPlace? =
        payloads.filterIsInstance<HistoryPayload.Place>().firstOrNull { it.value.id == placeId }?.value
            ?: userPlaces
                .observeAllPlaces()
                .first()
                .firstOrNull { it.uid.toString() == placeId }
                ?.toSemanticPlace()

    private suspend fun recordPlaceCorrections(
        place: SemanticPlace,
        effective: SemanticPlace,
    ) {
        if (!place.userConfirmed) return
        if (place.name != effective.name) correct("place:${place.id}", HistoryField.LABEL, place.name)
        if (place.radiusMeters != effective.radiusMeters) {
            correct("place:${place.id}", HistoryField.RADIUS, place.radiusMeters.coerceIn(30.0, 500.0).toString())
        }
        if (place.category != effective.category) correct("place:${place.id}", HistoryField.CATEGORY, place.category.orEmpty())
    }

    suspend fun correct(
        evidenceId: String,
        field: HistoryField,
        value: String,
    ) {
        val (ownerId, origin) = scope()
        val old =
            store
                .records(ownerId, origin)
                .mapNotNull(::decode)
                .filterIsInstance<HistoryPayload.Correction>()
                .map { it.value }
                .filter { it.targetEvidenceId == evidenceId && it.field == field }
                .map { it.id }
        val id = Uuid.random().toString()
        put(id, "correction", HistoryPayload.Correction(HistoryCorrection(id, evidenceId, field, value, old)))
    }

    suspend fun linkMemory(
        evidenceId: String,
        noteId: Uuid,
    ) {
        val (ownerId, origin) = scope()
        val link = VisitMemoryLink(noteId.toString(), evidenceId)
        val existing = store.records(ownerId, origin).any { !it.deleted && (decode(it) as? HistoryPayload.MemoryLink)?.value == link }
        if (!existing) put(Uuid.random().toString(), "memory-link", HistoryPayload.MemoryLink(link))
    }

    suspend fun addVisit(
        start: Instant,
        end: Instant,
        place: SemanticPlace,
    ) {
        require(end >= start) { "The visit must end after it starts." }
        savePlace(place)
        val id = Uuid.random().toString()
        put(id, "manual", HistoryPayload.Manual(ManualVisit(id, start, end, place.id)))
    }

    suspend fun deleteVisit(item: LocationDayItem) {
        val (ownerId, origin) = scope()
        val currentDevice = device.getDeviceId().value.toString()
        val raw =
            rawHistory
                .getLocationHistoryBetween(item.start, item.end + 1.milliseconds)
                .filter { it.userId == ownerId || (it.userId == "default_user" && it.deviceId == currentDevice) }
                .associateBy { it.sampleId }
        val changes = buildDeleteChanges(ownerId, origin, currentDevice, item.evidenceIds.distinct(), raw)
        store.putBatch(ownerId, origin, changes)
    }

    private suspend fun buildDeleteChanges(
        ownerId: String,
        origin: String,
        currentDevice: String,
        evidenceIds: List<String>,
        rawBySampleId: Map<String, app.logdate.client.repository.location.LocationHistoryItem>,
    ): List<HistoryRecord> =
        buildList {
            for (evidenceId in evidenceIds) {
                val correctionId = Uuid.random().toString()
                val correction = HistoryPayload.Correction(HistoryCorrection(correctionId, evidenceId, HistoryField.DELETE, "true"))
                add(HistoryRecord(correctionId, "correction", json.encodeToString(HistoryPayload.serializer(), correction), currentDevice))
                val direct = store.record(ownerId, origin, evidenceId)
                val manual = direct?.let(::decode) is HistoryPayload.Manual || direct?.recordType == "manual"
                val id = if (manual) evidenceId else "observation:$evidenceId"
                val previous = if (manual) direct else store.record(ownerId, origin, id)
                // Deletion records the correction and tombstones the underlying manual or observation record using
                // the original recording device so synced observation devices stay consistent on other clients.
                val originalDevice =
                    previous?.let(::decode)?.let { (it as? HistoryPayload.Observation)?.value?.deviceId }
                        ?: previous?.deviceId
                        ?: rawBySampleId[evidenceId]?.deviceId
                        ?: currentDevice
                add(
                    HistoryRecord(
                        id = id,
                        recordType = if (manual) "manual" else "observation",
                        payload = null,
                        deviceId = originalDevice,
                        deviceVersion = (previous?.deviceVersion ?: 0) + 1,
                        serverVersion = previous?.serverVersion ?: 0,
                        deleted = true,
                        dirty = true,
                    ),
                )
            }
        }

    suspend fun mergePlaces(
        fromId: String,
        into: SemanticPlace,
    ) {
        savePlace(into)
        placeResolutionCache?.invalidateNear(into.latitude, into.longitude)
        if (fromId == into.id) return
        val (ownerId, origin) = scope()
        val records = store.records(ownerId, origin)
        val payloads = records.filterNot { it.deleted }.mapNotNull(::decode)
        payloads.filterIsInstance<HistoryPayload.Manual>().filter { it.value.placeId == fromId }.forEach {
            put(it.value.id, "manual", HistoryPayload.Manual(it.value.copy(placeId = into.id)))
        }
        payloads
            .filterIsInstance<HistoryPayload.Correction>()
            .filter { it.value.field == HistoryField.PLACE && it.value.value == fromId }
            .forEach { correct(it.value.targetEvidenceId, HistoryField.PLACE, into.id) }
        correct("place:$fromId", HistoryField.PLACE, into.id)
        val previous = records.firstOrNull { it.id == fromId }
        store.put(
            ownerId,
            origin,
            HistoryRecord(
                id = fromId,
                recordType = "place",
                payload = null,
                deviceId = device.getDeviceId().value.toString(),
                deviceVersion = (previous?.deviceVersion ?: 0) + 1,
                serverVersion = previous?.serverVersion ?: 0,
                deleted = true,
                dirty = true,
            ),
        )
    }

    private suspend fun put(
        id: String,
        type: String,
        payload: HistoryPayload,
    ) {
        val (ownerId, origin) = scope()
        val previous = store.record(ownerId, origin, id)
        store.put(
            ownerId,
            origin,
            HistoryRecord(
                id,
                type,
                json.encodeToString(HistoryPayload.serializer(), payload),
                device.getDeviceId().value.toString(),
                (previous?.deviceVersion ?: 0) + 1,
                previous?.serverVersion ?: 0,
            ),
        )
    }

    fun decode(record: HistoryRecord): HistoryPayload? = decodeHistoryRecord(json, record)

    private suspend fun scope() = owner.getCanonicalOwnerId() to config.getCurrentBackendUrl().trimEnd('/')
}
