package app.logdate.client.domain.location.history

import app.logdate.client.device.crypto.activityHistoryRecordId
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.ManualVisit
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.location.VisitMemoryLink
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class HistoryFailedSection { NOTES, PLACES, ACTIVITY }

data class LocationHistorySnapshot(
    val items: List<LocationDayItem>,
    val places: List<SemanticPlace>,
    val notes: List<JournalNote>,
    val recordingDevices: List<String>,
    val selectedDeviceId: String,
    val conflicts: List<HistoryCorrection>,
    val placeAliases: Map<String, String> = emptyMap(),
    val failedSections: Set<HistoryFailedSection> = emptySet(),
)

/** Local-first history; opening a day never requires current-location permission or a network call. */
class LocationHistoryService(
    private val rawHistory: LocationHistoryRepository,
    private val store: HistoryRecordStore,
    private val notes: JournalNotesRepository,
    private val owner: CanonicalOwnerProvider,
    private val device: DeviceIdProvider,
    private val config: LogDateConfigRepository,
    private val userPlaces: UserPlacesRepository,
    private val activityHistory: ActivityHistoryRepository? = null,
    private val reconstructionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun observeDay(
        date: LocalDate,
        sourceDeviceId: String? = null,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Flow<LocationHistorySnapshot> = observeRange(date, date, sourceDeviceId, zone)

    fun observeRange(
        first: LocalDate,
        last: LocalDate,
        sourceDeviceId: String? = null,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Flow<LocationHistorySnapshot> =
        flow {
            val ownerId = owner.getCanonicalOwnerId()
            val localDevice = device.getDeviceId().value.toString()
            val origin = config.getCurrentBackendUrl().trimEnd('/')
            val start = first.atStartOfDayIn(zone)
            val end = last.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
            val contextStart = start - 24.hours
            val contextEnd = end + 24.hours
            var rawRevision = 0L
            var recordRevision = 0L
            val boundaryCache = BoundaryCache(ownerId, origin, localDevice)
            val memoryCache = LinkedMemoryCache()
            emitAll(
                combine(
                    rawHistory.observeLocationHistoryBetween(contextStart, contextEnd).map { it to ++rawRevision },
                    store.observeRange(ownerId, origin, contextStart.toEpochMilliseconds(), contextEnd.toEpochMilliseconds()).map {
                        it to
                            ++recordRevision
                    },
                    optionalHistorySection(HistoryFailedSection.NOTES) { notes.observeNotesInRange(contextStart, contextEnd) },
                    optionalHistorySection(HistoryFailedSection.PLACES) { userPlaces.observeAllPlaces() },
                    optionalHistorySection(HistoryFailedSection.ACTIVITY) {
                        activityHistory?.observeActivityHistoryBetween(contextStart, contextEnd) ?: flowOf(emptyList())
                    },
                ) { localSource, recordSource, memorySource, placeSource, activitySource ->
                    val local = localSource.first
                    val records = recordSource.first
                    val savedPlaces = placeSource.items
                    val localActivities = activitySource.items
                    val evidence =
                        rangeEvidence(records, savedPlaces, local, localActivities, ownerId, localDevice, contextStart, contextEnd)
                    val payloads = evidence.payloads
                    val key = Triple(localSource.second, recordSource.second, activitySource.revision)
                    val carried = boundaryCache.read(key, evidence)
                    val enriched = applyActivityEvidence(carried.observations, carried.activities)
                    val inferredByDevice = enriched.groupBy { it.deviceId }.mapValues { (_, samples) -> ReconstructLocationDay()(samples) }
                    val memories =
                        memoryCache.resolve(
                            inferredByDevice,
                            payloads,
                            memorySource.items,
                            memorySource.revision,
                            contextStart,
                            contextEnd,
                        )

                    val selection = selectDaySources(inferredByDevice, payloads, first, last, zone, sourceDeviceId, localDevice)
                    buildSnapshot(selection.items, payloads, memories, selection.devices, selection.preferred, start, end).copy(
                        failedSections =
                            listOfNotNull(
                                memorySource.failure,
                                placeSource.failure,
                                activitySource.failure,
                                HistoryFailedSection.ACTIVITY.takeIf { carried.activityFailed },
                                HistoryFailedSection.NOTES.takeIf { memoryCache.linkedNotesFailed },
                            ).toSet(),
                    )
                },
            )
        }.flowOn(reconstructionDispatcher)

    private inner class BoundaryCache(
        private val ownerId: String,
        private val origin: String,
        private val localDevice: String,
    ) {
        private var boundaryKey: Triple<Long, Long, Long>? = null
        private var boundary: BoundaryEvidence? = null
        private val reader = HistoryBoundaryReader(rawHistory, store, activityHistory, ::decode)

        suspend fun read(
            key: Triple<Long, Long, Long>,
            evidence: RangeEvidence,
        ): BoundaryEvidence {
            if (boundaryKey != key) {
                boundary =
                    reader.read(
                        ownerId,
                        origin,
                        localDevice,
                        evidence.observations,
                        evidence.activities,
                        evidence.deletedSamples,
                        evidence.deletedActivities,
                    )
                boundaryKey = key
            }
            return checkNotNull(boundary)
        }
    }

    private inner class LinkedMemoryCache {
        val linkedNotes = mutableMapOf<String, JournalNote>()
        var previousLinkIds: Set<String>? = null
        var previousNotesRevision = -1L
        var linkedNotesFailed = false
            private set

        suspend fun resolve(
            inferredByDevice: Map<String, List<LocationDayItem>>,
            payloads: List<HistoryPayload>,
            boundedNotes: List<JournalNote>,
            revision: Long,
            contextStart: Instant,
            contextEnd: Instant,
        ): List<JournalNote> {
            val contextEvidenceIds =
                inferredByDevice.values
                    .flatten()
                    .filter { it.overlaps(contextStart, contextEnd) }
                    .flatMap { it.evidenceIds }
                    .toSet() +
                    payloads
                        .filterIsInstance<HistoryPayload.Manual>()
                        .filter {
                            it.value.start < contextEnd && it.value.end >= contextStart
                        }.map { it.value.id }
            val boundedNoteIds = boundedNotes.map { it.uid.toString() }.toSet()
            val linkedIds =
                payloads
                    .filterIsInstance<HistoryPayload.MemoryLink>()
                    .filter {
                        it.value.targetEvidenceId in contextEvidenceIds && it.value.noteId !in boundedNoteIds
                    }.map { it.value.noteId }
                    .toSet()
            if (linkedIds != previousLinkIds || revision != previousNotesRevision) {
                linkedNotes.keys.retainAll(linkedIds)
                linkedNotesFailed = false
                for (id in linkedIds) {
                    val noteId = runCatching { Uuid.parse(id) }.getOrNull() ?: continue
                    try {
                        val note = notes.getNoteById(noteId)
                        if (note == null) linkedNotes.remove(id) else linkedNotes[id] = note
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        linkedNotesFailed = true
                        Napier.w("Linked location memory unavailable", error)
                    }
                }
                previousLinkIds = linkedIds
                previousNotesRevision = revision
            }
            return boundedNotes + linkedNotes.values
        }
    }

    private data class RangeEvidence(
        val payloads: List<HistoryPayload>,
        val observations: List<LocationObservation>,
        val activities: List<ActivityObservation>,
        val deletedSamples: Set<String>,
        val deletedActivities: Set<String>,
    )

    private fun rangeEvidence(
        records: List<HistoryRecord>,
        savedPlaces: List<app.logdate.shared.model.Place>,
        local: List<LocationHistoryItem>,
        localActivities: List<app.logdate.client.repository.location.ActivityHistoryItem>,
        ownerId: String,
        localDevice: String,
        contextStart: Instant,
        contextEnd: Instant,
    ): RangeEvidence {
        val deletedPlaces = records.filter { it.deleted && it.recordType == "place" }.map { it.id }.toSet()
        val payloads =
            records.filterNot { it.deleted }.mapNotNull(::decode) +
                savedPlaces
                    .filterNot { it.uid.toString() in deletedPlaces }
                    .map {
                        HistoryPayload.Place(
                            SemanticPlace(it.uid.toString(), it.name, it.latitude, it.longitude, userConfirmed = true),
                        )
                    }
        val deletedSamples =
            records
                .filter { it.deleted && it.recordType == "observation" }
                .map { it.id.removePrefix("observation:") }
                .toSet()
        val observations =
            (
                local
                    .filter { it.userId == ownerId || (it.userId == "default_user" && it.deviceId == localDevice) }
                    .map { it.toObservation(ownerId) } +
                    payloads.filterIsInstance<HistoryPayload.Observation>().map { it.value }
            ).filter {
                it.ownerId == ownerId &&
                    it.id !in deletedSamples &&
                    it.timestamp >= contextStart &&
                    it.timestamp < contextEnd
            }
        val deletedActivities =
            records
                .filter { it.deleted && it.recordType == "activity" }
                .map { it.id }
                .toSet()
        val activities =
            (
                localActivities.map {
                    ActivityObservation(
                        it.id,
                        it.userId,
                        it.deviceId,
                        it.timestamp,
                        it.activityType,
                        it.transitionType,
                        it.timeZoneId,
                    )
                } + payloads.filterIsInstance<HistoryPayload.Activity>().map { it.value }
            ).filterNot { activityHistoryRecordId(it.id) in deletedActivities }
        return RangeEvidence(payloads, observations, activities, deletedSamples, deletedActivities)
    }

    private data class DaySources(
        val items: List<LocationDayItem>,
        val devices: List<String>,
        val preferred: String,
    )

    private fun selectDaySources(
        inferredByDevice: Map<String, List<LocationDayItem>>,
        payloads: List<HistoryPayload>,
        first: LocalDate,
        last: LocalDate,
        zone: TimeZone,
        sourceDeviceId: String?,
        localDevice: String,
    ): DaySources {
        val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
        val correctedByDevice = inferredByDevice.mapValues { (_, items) -> ApplyHistoryEdits()(items, edits, emptyList()) }
        // Collections union each day's chosen source while preserving original item identities and intervals.
        val selectedItems = linkedMapOf<String, LocationDayItem>()
        val availableDevices = mutableSetOf<String>()
        var preferred = ""
        var day = first
        while (day <= last) {
            val dayStart = day.atStartOfDayIn(zone)
            val nextDay = day.plus(DatePeriod(days = 1))
            val dayEnd = nextDay.atStartOfDayIn(zone)
            val available =
                correctedByDevice
                    .filterValues { items ->
                        items.any { it.overlaps(dayStart, dayEnd) }
                    }.keys
                    .sorted()
            availableDevices.addAll(available)
            preferred = sourceDeviceId?.takeIf { it in available }
                ?: localDevice.takeIf { it in available } ?: available.firstOrNull().orEmpty()
            val selectedIds =
                correctedByDevice[preferred]
                    .orEmpty()
                    .filter { it.overlaps(dayStart, dayEnd) }
                    .map { it.id }
                    .toSet()
            inferredByDevice[preferred].orEmpty().filter { it.id in selectedIds }.forEach { selectedItems[it.id] = it }
            day = nextDay
        }
        return DaySources(selectedItems.values.toList(), availableDevices.sorted(), preferred)
    }

    private fun buildSnapshot(
        inferred: List<LocationDayItem>,
        payloads: List<HistoryPayload>,
        notes: List<JournalNote>,
        devices: List<String>,
        selectedDevice: String,
        start: Instant,
        end: Instant,
    ): LocationHistorySnapshot {
        val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
        val places = applyPlaceLabels(payloads.filterIsInstance<HistoryPayload.Place>().map { it.value }.distinctBy { it.id }, edits)
        val links = payloads.filterIsInstance<HistoryPayload.MemoryLink>().map { it.value }
        val manual = manualVisits(payloads, places)
        val corrected = ApplyHistoryEdits()(inferred + manual, edits, places)
        val dated = corrected.filter { it.overlaps(start, end) }.sortedBy { it.start }
        val visits =
            AssociateVisitMemories()(
                dated.filterIsInstance<PlaceVisit>().map { visit ->
                    visit.copy(
                        place =
                            visit.place ?: places
                                .filter {
                                    geographicDistance(visit.latitude, visit.longitude, it.latitude, it.longitude) <= 75
                                }.singleOrNull(),
                    )
                },
                notes,
                links,
            ).associateBy { it.id }
        val linkedNoteIds = visits.values.flatMap { it.memoryIds }.toSet()
        val datedNotes =
            notes.filter {
                (it.creationTimestamp >= start && it.creationTimestamp < end) || it.uid.toString() in linkedNoteIds
            }
        val superseded = edits.flatMap { it.supersedes }.toSet()
        val aliases = resolvePlaceAliases(edits)
        val displayedPlaces =
            visits.values.mapNotNull { it.place?.id } +
                datedNotes.mapNotNull {
                    it.location
                        ?.place
                        ?.id
                        ?.toString()
                }
        val displayedEvidence = dated.flatMap { it.evidenceIds }.toSet() + displayedPlaces.map { "place:${aliases[it] ?: it}" }
        val conflicts =
            edits
                .filter { it.id !in superseded && it.targetEvidenceId in displayedEvidence }
                .groupBy {
                    it.targetEvidenceId to
                        it.field
                }.values
                .filter { it.map { edit -> edit.value }.distinct().size > 1 }
                .flatten()
        return LocationHistorySnapshot(
            dated.map {
                visits[it.id] ?: it
            },
            places,
            datedNotes,
            devices,
            selectedDevice,
            conflicts,
            resolvePlaceAliases(edits),
        )
    }

    private fun manualVisits(
        payloads: List<HistoryPayload>,
        places: List<SemanticPlace>,
    ): List<PlaceVisit> =
        payloads.filterIsInstance<HistoryPayload.Manual>().mapNotNull { payload ->
            val place = places.firstOrNull { it.id == payload.value.placeId } ?: return@mapNotNull null
            payload.value.let { PlaceVisit(it.id, it.start, it.end, listOf(it.id), place.latitude, place.longitude, true, place) }
        }

    suspend fun savePlace(place: SemanticPlace) {
        val scope = scope()
        val records = store.records(scope.first, scope.second).filterNot { it.deleted }
        val payloads = records.mapNotNull(::decode)
        val previous =
            payloads.filterIsInstance<HistoryPayload.Place>().firstOrNull { it.value.id == place.id }?.value
                ?: userPlaces.observeAllPlaces().first().firstOrNull { it.uid.toString() == place.id }?.let {
                    SemanticPlace(it.uid.toString(), it.name, it.latitude, it.longitude, userConfirmed = true)
                }
        if (previous == null) {
            put(place.id, "place", HistoryPayload.Place(place))
            return
        }
        val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
        val effective = applyPlaceLabels(listOf(previous), edits).single()
        val hasUserLabel = edits.any { it.field == HistoryField.LABEL && it.targetEvidenceId == "place:${place.id}" }
        if (!place.userConfirmed && (effective.userConfirmed || hasUserLabel)) return
        if (place.userConfirmed && place.name != effective.name) correct("place:${place.id}", HistoryField.LABEL, place.name)
        val retained = if (place.userConfirmed) place.copy(name = previous.name) else place
        put(place.id, "place", HistoryPayload.Place(retained))
    }

    suspend fun correct(
        evidenceId: String,
        field: HistoryField,
        value: String,
    ) {
        val scope = scope()
        val old =
            store
                .records(scope.first, scope.second)
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
        noteId: String,
    ) {
        val scope = scope()
        val link = VisitMemoryLink(noteId, evidenceId)
        val existing =
            store.records(scope.first, scope.second).any {
                !it.deleted && (decode(it) as? HistoryPayload.MemoryLink)?.value == link
            }
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
        val scope = scope()
        val currentDevice = device.getDeviceId().value.toString()
        val raw =
            rawHistory
                .getLocationHistoryBetween(item.start, item.end + 1.milliseconds)
                .filter { it.userId == scope.first || (it.userId == "default_user" && it.deviceId == currentDevice) }
                .associateBy { it.sampleId }
        val changes = mutableListOf<HistoryRecord>()
        item.evidenceIds.distinct().forEach { evidenceId ->
            val correctionId = Uuid.random().toString()
            val correction = HistoryPayload.Correction(HistoryCorrection(correctionId, evidenceId, HistoryField.DELETE, "true"))
            changes +=
                HistoryRecord(correctionId, "correction", json.encodeToString(HistoryPayload.serializer(), correction), currentDevice)
            val direct = store.record(scope.first, scope.second, evidenceId)
            val manual = direct?.let(::decode) is HistoryPayload.Manual || direct?.recordType == "manual"
            val id = if (manual) evidenceId else "observation:$evidenceId"
            val previous = if (manual) direct else store.record(scope.first, scope.second, id)
            val originalDevice =
                previous?.let(::decode)?.let { (it as? HistoryPayload.Observation)?.value?.deviceId }
                    ?: previous?.deviceId ?: raw[evidenceId]?.deviceId ?: currentDevice
            changes +=
                HistoryRecord(
                    id,
                    if (manual) "manual" else "observation",
                    null,
                    originalDevice,
                    (previous?.deviceVersion ?: 0) + 1,
                    previous?.serverVersion ?: 0,
                    deleted = true,
                    dirty = true,
                )
        }
        store.putBatch(scope.first, scope.second, changes)
    }

    suspend fun mergePlaces(
        fromId: String,
        into: SemanticPlace,
    ) {
        savePlace(into)
        if (fromId == into.id) return
        val scope = scope()
        val records = store.records(scope.first, scope.second)
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
            scope.first,
            scope.second,
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
        val scope = scope()
        val previous = store.record(scope.first, scope.second, id)
        store.put(
            scope.first,
            scope.second,
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

    private suspend fun scope() = owner.getCanonicalOwnerId() to config.getCurrentBackendUrl().trimEnd('/')

    private fun decode(record: HistoryRecord): HistoryPayload? =
        record.payload?.let {
            runCatching { json.decodeFromString(HistoryPayload.serializer(), it) }.getOrNull()
        }
}

private fun LocationDayItem.overlaps(
    start: Instant,
    end: Instant,
): Boolean = this.start < end && (this.end > start || (this.start == this.end && this.start >= start))

internal fun applyActivityEvidence(
    observations: List<LocationObservation>,
    activities: List<ActivityObservation>,
): List<LocationObservation> {
    val byDevice =
        activities
            .distinctBy { Triple(it.ownerId, it.deviceId, it.id) }
            .groupBy { it.ownerId to it.deviceId }
            .mapValues { (_, values) -> values.sortedBy { it.timestamp } }
    return observations.map { point ->
        if (point.activity != TravelMode.UNKNOWN) return@map point
        val events = byDevice[point.ownerId to point.deviceId] ?: return@map point
        var low = 0
        var high = events.size
        while (low < high) {
            val middle = (low + high) / 2
            if (events[middle].timestamp <= point.timestamp) low = middle + 1 else high = middle
        }
        if (low == 0) return@map point
        val latest = events[low - 1]
        if (point.timestamp - latest.timestamp > 10.minutes) return@map point
        var firstAtTime = low - 1
        while (firstAtTime > 0 && events[firstAtTime - 1].timestamp == latest.timestamp) firstAtTime--
        val atTime = events.subList(firstAtTime, low).distinctBy { it.activityType to it.transitionType }
        if (atTime.size != 1 || latest.transitionType != "ENTER") return@map point
        point.copy(activity = latest.activityType.toTravelMode())
    }
}

internal fun LocationHistoryItem.toObservation(owner: String) =
    LocationObservation(
        sampleId,
        owner,
        deviceId,
        timestamp,
        location.latitude,
        location.longitude,
        accuracyMeters,
        speedMetersPerSecond,
        bearingDegrees,
        activityType.toTravelMode(),
        timeZoneId,
        captureSource.name,
        isMock,
    )

private fun String?.toTravelMode(): TravelMode =
    when (this) {
        "WALKING", "ON_FOOT" -> TravelMode.WALKING
        "RUNNING" -> TravelMode.RUNNING
        "ON_BICYCLE" -> TravelMode.CYCLING
        "IN_VEHICLE" -> TravelMode.VEHICLE
        "STILL" -> TravelMode.STILL
        else -> TravelMode.UNKNOWN
    }

private fun resolvePlaceAliases(edits: List<HistoryCorrection>): Map<String, String> {
    val superseded = edits.flatMap { it.supersedes }.toSet()
    val candidates =
        edits
            .filter {
                it.id !in superseded && it.field == HistoryField.PLACE && it.targetEvidenceId.startsWith("place:")
            }.groupBy { it.targetEvidenceId.removePrefix("place:") }
    val direct =
        candidates
            .mapNotNull { (source, values) ->
                values
                    .map { it.value }
                    .distinct()
                    .singleOrNull()
                    ?.let { source to it }
            }.toMap()
    return direct.keys
        .mapNotNull { source ->
            val visited = mutableSetOf<String>()
            var target = source
            while (target in direct && visited.add(target)) target = direct.getValue(target)
            if (target in visited || target in candidates) null else source to target
        }.toMap()
}

private fun applyPlaceLabels(
    places: List<SemanticPlace>,
    edits: List<HistoryCorrection>,
): List<SemanticPlace> {
    val superseded = edits.flatMap { it.supersedes }.toSet()
    val labels =
        edits
            .filter { it.id !in superseded && it.field == HistoryField.LABEL }
            .groupBy { it.targetEvidenceId }
    return places.map { place ->
        val label =
            labels["place:${place.id}"]
                .orEmpty()
                .map { it.value }
                .distinct()
                .singleOrNull()
        if (label == null) place else place.copy(name = label, userConfirmed = true)
    }
}

private data class OptionalHistorySection<T>(
    val items: List<T>,
    val failure: HistoryFailedSection? = null,
    val revision: Long = 0,
)

private fun <T> optionalHistorySection(
    section: HistoryFailedSection,
    source: () -> Flow<List<T>>,
): Flow<OptionalHistorySection<T>> =
    flow {
        var latest: List<T> = emptyList()
        var revision = 0L
        emitAll(
            flow { emitAll(source()) }
                .map { items ->
                    latest = items
                    OptionalHistorySection(items, revision = ++revision)
                }.catch { error ->
                    if (error is CancellationException) throw error
                    Napier.w("Location history section unavailable: $section", error)
                    emit(OptionalHistorySection(latest, section, revision))
                },
        )
    }
