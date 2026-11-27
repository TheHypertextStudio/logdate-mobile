package app.logdate.client.domain.location.history

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
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
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.SemanticPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val HISTORY_TEST_START = Instant.parse("2026-09-29T00:00:00Z")
internal val HISTORY_TEST_DATE = LocalDate.parse("2026-09-29")
internal const val HISTORY_TEST_PHONE = "00000000-0000-0000-0000-000000000001"
internal const val HISTORY_TEST_TABLET = "00000000-0000-0000-0000-000000000002"

internal fun historyTestObservation(
    id: String,
    time: Instant,
    deviceId: String = HISTORY_TEST_PHONE,
) = LocationObservation(id, "owner", deviceId, time, 36.0, -115.0, accuracyMeters = 8f)

internal fun historyTestActivity(
    time: Instant,
    type: String,
    transition: String,
) = ActivityObservation("activity", "owner", HISTORY_TEST_PHONE, time, type, transition)

internal fun historyTestPlace(id: String) = SemanticPlace(id, id, 36.0, -115.0, userConfirmed = true)

internal fun historyTestNote(time: Instant) = JournalNote.Text(creationTimestamp = time, lastUpdated = time, content = "Memory")

internal inline fun <reified T> historyProxy(crossinline value: () -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> value() } as T

internal class HistoryServiceTestFixture(
    private val savedPlaces: List<Place> = emptyList(),
    private val notes: List<JournalNote> = emptyList(),
    private val notesFlow: Flow<List<JournalNote>>? = null,
    private val placesFlow: Flow<List<Place>>? = null,
    private val activityFlow: Flow<List<ActivityHistoryItem>>? = null,
    private val activities: List<ActivityHistoryItem> = emptyList(),
    private val raw: List<LocationHistoryItem> = emptyList(),
) {
    var boundaryReads = 0
    var batchWrites = 0
    var singleWrites = 0
    var allNotesReads = 0
    val requestedRanges = mutableListOf<Pair<Instant, Instant>>()
    val requestedNoteIds = mutableListOf<Uuid>()
    private val state = MutableStateFlow(emptyList<HistoryRecord>())
    private val store =
        object : HistoryRecordStore {
            override fun observe(
                ownerId: String,
                origin: String,
            ) = state

            override suspend fun records(
                ownerId: String,
                origin: String,
            ) = state.value

            override suspend fun put(
                ownerId: String,
                origin: String,
                record: HistoryRecord,
            ) {
                singleWrites++
                state.value = state.value.filterNot { it.id == record.id } + record
            }

            override suspend fun putBatch(
                ownerId: String,
                origin: String,
                records: List<HistoryRecord>,
            ) {
                batchWrites++
                val replaced = records.map { it.id }.toSet()
                state.value = state.value.filterNot { it.id in replaced } + records
            }

            override suspend fun observationsBefore(
                ownerId: String,
                origin: String,
                deviceId: String,
                beforeMillis: Long,
                beforeSampleId: String,
                limit: Int,
            ): List<HistoryRecord> {
                boundaryReads++
                return state.value
                    .mapNotNull { record ->
                        if (record.deleted) {
                            null
                        } else {
                            record.payload?.let { text ->
                                (Json.decodeFromString(HistoryPayload.serializer(), text) as? HistoryPayload.Observation)?.value?.let {
                                    record to it
                                }
                            }
                        }
                    }.filter { (_, point) ->
                        point.ownerId == ownerId &&
                            point.deviceId == deviceId &&
                            (
                                point.timestamp.toEpochMilliseconds() < beforeMillis ||
                                    (point.timestamp.toEpochMilliseconds() == beforeMillis && point.id < beforeSampleId)
                            )
                    }.sortedWith(
                        compareByDescending<Pair<HistoryRecord, LocationObservation>> {
                            it.second.timestamp
                        }.thenByDescending { it.second.id },
                    ).take(limit)
                    .map { it.first }
            }

            override suspend fun pending(
                ownerId: String,
                origin: String,
                limit: Int,
            ) = emptyList<HistoryRecord>()

            override suspend fun cursor(
                ownerId: String,
                origin: String,
            ) = 0L

            override suspend fun applyPage(
                ownerId: String,
                origin: String,
                records: List<HistoryRecord>,
                cursor: Long,
            ) = Unit

            override suspend fun acknowledge(
                ownerId: String,
                origin: String,
                id: String,
                deviceVersion: Long,
                serverVersion: Long,
            ) = Unit
        }
    val service = createService()

    fun createService() =
        LocationHistoryService(
            rawHistory =
                object : LocationHistoryRepository by historyProxy<LocationHistoryRepository>({ flowOf(raw) }) {
                    override suspend fun getLocationHistoryBefore(
                        userId: String,
                        deviceId: String,
                        before: Instant,
                        beforeSampleId: String,
                        limit: Int,
                    ): List<LocationHistoryItem> =
                        raw
                            .filter {
                                it.userId == userId &&
                                    it.deviceId == deviceId &&
                                    (it.timestamp < before || (it.timestamp == before && it.sampleId < beforeSampleId))
                            }.sortedWith(compareByDescending<LocationHistoryItem> { it.timestamp }.thenByDescending { it.sampleId })
                            .take(limit)

                    override suspend fun getLocationHistoryBetween(
                        startTime: Instant,
                        endTime: Instant,
                    ) = raw.filter { it.timestamp >= startTime && it.timestamp < endTime }

                    override suspend fun getLastLocation(): LocationHistoryItem? = raw.maxByOrNull { it.timestamp }
                },
            store = store,
            notes =
                object : JournalNotesRepository by historyProxy<JournalNotesRepository>({ flowOf(notes) }) {
                    override val allNotesObserved: Flow<List<JournalNote>>
                        get() {
                            allNotesReads++
                            return notesFlow ?: flowOf(notes)
                        }

                    override fun observeNotesInRange(
                        start: Instant,
                        end: Instant,
                    ): Flow<List<JournalNote>> {
                        requestedRanges += start to end
                        return notesFlow ?: flowOf(notes.filter { it.creationTimestamp >= start && it.creationTimestamp < end })
                    }

                    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> =
                        flowOf(notes.sortedByDescending { it.creationTimestamp }.take(limit))

                    override suspend fun getNoteById(noteId: Uuid): JournalNote? {
                        requestedNoteIds += noteId
                        return notes.firstOrNull { it.uid == noteId }
                    }
                },
            owner = historyProxy<CanonicalOwnerProvider> { "owner" },
            device = historyProxy<DeviceIdProvider> { MutableStateFlow(Uuid.parse(HISTORY_TEST_PHONE)) },
            config = historyProxy<LogDateConfigRepository> { "https://example.test" },
            userPlaces = historyProxy<UserPlacesRepository> { placesFlow ?: flowOf(savedPlaces) },
            activityHistory =
                object : ActivityHistoryRepository by historyProxy<ActivityHistoryRepository>({ activityFlow ?: flowOf(activities) }) {
                    override suspend fun latestBefore(
                        userId: String,
                        deviceId: String,
                        before: Instant,
                    ): ActivityHistoryItem? =
                        activities
                            .filter { it.userId == userId && it.deviceId == deviceId && it.timestamp < before }
                            .maxByOrNull { it.timestamp }

                    override suspend fun latestForDevice(
                        deviceId: String,
                        since: Instant,
                    ): ActivityHistoryItem? =
                        activities
                            .filter { it.deviceId == deviceId && it.timestamp >= since }
                            .maxByOrNull { it.timestamp }
                },
            reconstructionDispatcher = Dispatchers.Unconfined,
        )

    suspend fun save(
        id: String,
        payload: HistoryPayload,
    ) {
        store.put(
            "owner",
            "https://example.test",
            HistoryRecord(
                id,
                "test",
                Json.encodeToString(HistoryPayload.serializer(), payload),
                (payload as? HistoryPayload.Observation)?.value?.deviceId ?: HISTORY_TEST_PHONE,
            ),
        )
    }

    suspend fun records() = store.records("owner", "https://example.test")

    suspend fun snapshot() = service.observeDay(HISTORY_TEST_DATE, zone = TimeZone.UTC).first()
}
