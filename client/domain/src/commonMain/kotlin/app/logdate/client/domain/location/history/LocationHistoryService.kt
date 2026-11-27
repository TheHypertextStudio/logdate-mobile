package app.logdate.client.domain.location.history

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.recommendation.PlaceFamiliarityRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.Place
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.SemanticPlace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class HistoryFailedSection { NOTES, PLACES, ACTIVITY }

data class RecordedDaySummary(
    val date: LocalDate,
    val hasVisits: Boolean,
    val hasMemories: Boolean,
)

data class LocationHistorySnapshot(
    val items: List<LocationDayItem>,
    val places: List<SemanticPlace>,
    val notes: List<JournalNote>,
    val recordingDevices: List<String>,
    val selectedDeviceId: String,
    val conflicts: List<HistoryCorrection>,
    val placeAliases: Map<String, String> = emptyMap(),
    val failedSections: Set<HistoryFailedSection> = emptySet(),
    val effectiveZoneId: String? = null,
    val isMultiZoneDay: Boolean = false,
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
    private val placeFamiliarity: PlaceFamiliarityRepository? = null,
    placeResolutionCache: PlaceResolutionCache? = null,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val writer = HistoryRecordWriter(rawHistory, store, owner, device, config, userPlaces, placeResolutionCache, json)

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
            val contextStart = first.atStartOfDayIn(zone) - 24.hours
            val contextEnd = last.plus(DatePeriod(days = 1)).atStartOfDayIn(zone) + 24.hours
            var rawRev = 0L
            var recordRev = 0L
            val boundaryCache = HistoryBoundaryCache(rawHistory, store, activityHistory, writer::decode, ownerId, origin, localDevice)
            val memoryCache = HistoryLinkedMemoryCache(notes)
            emitAll(
                combine(
                    rawHistory
                        .observeLocationHistoryBetween(contextStart, contextEnd)
                        .map { it to ++rawRev },
                    store
                        .observeRange(ownerId, origin, contextStart.toEpochMilliseconds(), contextEnd.toEpochMilliseconds())
                        .map { it to ++recordRev },
                    optionalHistorySection(HistoryFailedSection.NOTES) { notes.observeNotesInRange(contextStart, contextEnd) },
                    optionalHistorySection(HistoryFailedSection.PLACES) { userPlaces.observeAllPlaces() },
                    optionalHistorySection(HistoryFailedSection.ACTIVITY) {
                        activityHistory?.observeActivityHistoryBetween(contextStart, contextEnd) ?: flowOf(emptyList())
                    },
                ) { raw, rec, mem, pl, act ->
                    buildRangeSnapshot(
                        raw,
                        rec,
                        mem,
                        pl,
                        act,
                        ownerId,
                        localDevice,
                        first,
                        last,
                        zone,
                        sourceDeviceId,
                        contextStart,
                        contextEnd,
                        boundaryCache,
                        memoryCache,
                    )
                },
            )
        }.flowOn(reconstructionDispatcher)

    private suspend fun buildRangeSnapshot(
        localSource: Pair<List<LocationHistoryItem>, Long>,
        recordSource: Pair<List<HistoryRecord>, Long>,
        memorySource: OptionalHistorySection<JournalNote>,
        placeSource: OptionalHistorySection<Place>,
        activitySource: OptionalHistorySection<ActivityHistoryItem>,
        ownerId: String,
        localDevice: String,
        first: LocalDate,
        last: LocalDate,
        zone: TimeZone,
        sourceDeviceId: String?,
        contextStart: Instant,
        contextEnd: Instant,
        boundaryCache: HistoryBoundaryCache,
        memoryCache: HistoryLinkedMemoryCache,
    ): LocationHistorySnapshot {
        val evidence =
            extractRangeEvidence(
                recordSource.first,
                placeSource.items,
                localSource.first,
                activitySource.items,
                ownerId,
                localDevice,
                contextStart,
                contextEnd,
                writer::decode,
            )
        val carried = boundaryCache.read(Triple(localSource.second, recordSource.second, activitySource.revision), evidence)
        val windowStart = first.atStartOfDayIn(zone)
        val windowEnd = last.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
        val reconstructed = reconstructRangeByDevice(carried, evidence.payloads, windowStart, windowEnd, zone)
        val memories =
            memoryCache.resolve(
                reconstructed.inferredByDevice,
                evidence.payloads,
                memorySource.items,
                memorySource.revision,
                contextStart,
                contextEnd,
            )
        val selection =
            selectDaySources(
                reconstructed.inferredByDevice,
                evidence.payloads,
                first,
                last,
                reconstructed.effectiveZone,
                sourceDeviceId,
                localDevice,
            )
        return assembleHistorySnapshot(
            inferred = selection.items,
            payloads = evidence.payloads,
            notes = memories,
            devices = selection.devices,
            selectedDevice = selection.preferred,
            start = first.atStartOfDayIn(reconstructed.effectiveZone),
            end = last.plus(DatePeriod(days = 1)).atStartOfDayIn(reconstructed.effectiveZone),
            familiarityCounts = loadFamiliarityCounts(evidence.payloads),
        ).copy(
            effectiveZoneId = reconstructed.effectiveZone.id,
            isMultiZoneDay = reconstructed.isMultiZoneDay,
            failedSections =
                listOfNotNull(
                    memorySource.failure,
                    placeSource.failure,
                    activitySource.failure,
                    HistoryFailedSection.ACTIVITY.takeIf { carried.activityFailed },
                    HistoryFailedSection.NOTES.takeIf { memoryCache.linkedNotesFailed },
                ).toSet(),
        )
    }

    private suspend fun loadFamiliarityCounts(payloads: List<HistoryPayload>): Map<String, Int> {
        val repo = placeFamiliarity ?: return emptyMap()
        val placeIds = payloads.filterIsInstance<HistoryPayload.Place>().map { it.value.id }.distinct()
        return placeIds.associateWith { id -> repo.get(id)?.visitCount ?: 0 }
    }

    suspend fun savePlace(place: SemanticPlace) = writer.savePlace(place)

    suspend fun mergeVisits(
        primaryAnchorId: String,
        secondaryAnchorId: String,
    ) {
        if (primaryAnchorId != secondaryAnchorId) writer.correct(primaryAnchorId, HistoryField.MERGE_WITH, secondaryAnchorId)
    }

    suspend fun splitVisit(
        anchorId: String,
        splitAt: Instant,
    ) = writer.correct(anchorId, HistoryField.SPLIT_AT, splitAt.toString())

    suspend fun updatePlaceRadius(
        placeId: String,
        radiusMeters: Double,
    ) = writer.correct("place:$placeId", HistoryField.RADIUS, radiusMeters.coerceIn(30.0, 500.0).toString())

    suspend fun updatePlaceCategory(
        placeId: String,
        category: String?,
    ) = writer.correct("place:$placeId", HistoryField.CATEGORY, category.orEmpty())

    fun observeRecordedDaySummaries(
        first: LocalDate,
        last: LocalDate,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Flow<Map<LocalDate, RecordedDaySummary>> =
        flow {
            val ownerId = owner.getCanonicalOwnerId()
            val localDevice = device.getDeviceId().value.toString()
            val origin = config.getCurrentBackendUrl().trimEnd('/')
            val start = first.atStartOfDayIn(zone)
            val end = last.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
            emitAll(
                combine(
                    rawHistory.observeLocationHistoryBetween(start, end),
                    store.observeRange(ownerId, origin, start.toEpochMilliseconds(), end.toEpochMilliseconds()),
                    notes.observeNotesInRange(start, end),
                ) { local, _, dayNotes ->
                    val synced =
                        store.recordedObservationTimestamps(
                            ownerId,
                            origin,
                            start.toEpochMilliseconds(),
                            end.toEpochMilliseconds(),
                        )
                    buildDaySummaryMap(first, last, zone, ownerId, localDevice, local, synced, dayNotes)
                },
            )
        }.flowOn(reconstructionDispatcher)

    suspend fun latestRecordedDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate? {
        val latestLocation = rawHistory.getLastLocation()?.timestamp
        val latestNote =
            notes
                .observeRecentNotes(1)
                .first()
                .firstOrNull()
                ?.creationTimestamp
        val latestInstant = listOfNotNull(latestLocation, latestNote).maxOrNull() ?: return null
        return latestInstant.toLocalDateTime(zone).date
    }

    suspend fun correct(
        evidenceId: String,
        field: HistoryField,
        value: String,
    ) = writer.correct(evidenceId, field, value)

    suspend fun linkMemory(
        evidenceId: String,
        noteId: Uuid,
    ) = writer.linkMemory(evidenceId, noteId)

    suspend fun addVisit(
        start: Instant,
        end: Instant,
        place: SemanticPlace,
    ) = writer.addVisit(start, end, place)

    suspend fun deleteVisit(item: LocationDayItem) = writer.deleteVisit(item)

    suspend fun mergePlaces(
        fromId: String,
        into: SemanticPlace,
    ) = writer.mergePlaces(fromId, into)
}

private fun buildDaySummaryMap(
    first: LocalDate,
    last: LocalDate,
    zone: TimeZone,
    ownerId: String,
    localDevice: String,
    local: List<LocationHistoryItem>,
    syncedTimestamps: List<Long>,
    dayNotes: List<JournalNote>,
): Map<LocalDate, RecordedDaySummary> {
    val visitDates =
        buildSet {
            local
                .filter { it.userId == ownerId || (it.userId == "default_user" && it.deviceId == localDevice) }
                .forEach { add(it.timestamp.toLocalDateTime(zone).date) }
            syncedTimestamps.forEach { millis -> add(Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date) }
        }
    val memoryDates = dayNotes.mapTo(mutableSetOf()) { it.creationTimestamp.toLocalDateTime(zone).date }
    return buildMap {
        var cursor = first
        while (cursor <= last) {
            put(cursor, RecordedDaySummary(cursor, hasVisits = cursor in visitDates, hasMemories = cursor in memoryDates))
            cursor = cursor.plus(DatePeriod(days = 1))
        }
    }
}
