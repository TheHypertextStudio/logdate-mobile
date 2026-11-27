package app.logdate.client.domain.location.history

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationDayItem
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Caches decoded boundary observations and activity transitions keyed by `(rawRevision, storeRevision,
 * activityRevision)` so range emits reuse prior boundary expansion when only unrelated stores change.
 */
internal class HistoryBoundaryCache(
    rawHistory: LocationHistoryRepository,
    store: HistoryRecordStore,
    activityHistory: ActivityHistoryRepository?,
    decode: (HistoryRecord) -> HistoryPayload?,
    private val ownerId: String,
    private val origin: String,
    private val localDevice: String,
) {
    private var boundaryKey: Triple<Long, Long, Long>? = null
    private var boundary: BoundaryEvidence? = null
    private val reader = HistoryBoundaryReader(rawHistory, store, activityHistory, decode)

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

/**
 * Fetches and caches explicitly linked [JournalNote] records that fall outside the requested day window.
 *
 * Cross-day links occur when a note recorded after midnight is manually attached to an earlier visit; keeping
 * them cached avoids repeating individual repository lookups while notes and link targets remain unchanged.
 */
internal class HistoryLinkedMemoryCache(
    private val notes: JournalNotesRepository,
) {
    private val linkedNotes = mutableMapOf<Uuid, JournalNote>()
    private var previousLinkIds: Set<Uuid>? = null
    private var previousNotesRevision = -1L
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
        val contextEvidenceIds = collectContextEvidenceIds(inferredByDevice, payloads, contextStart, contextEnd)
        val boundedNoteIds = boundedNotes.mapTo(mutableSetOf()) { it.uid }
        val linkedIds =
            payloads
                .filterIsInstance<HistoryPayload.MemoryLink>()
                .filter { it.value.targetEvidenceId in contextEvidenceIds }
                .mapNotNullTo(mutableSetOf()) { Uuid.parseOrNull(it.value.noteId)?.takeIf { id -> id !in boundedNoteIds } }
        if (linkedIds != previousLinkIds || revision != previousNotesRevision) {
            refreshLinkedNotes(linkedIds, revision)
        }
        return boundedNotes + linkedNotes.values
    }

    private fun collectContextEvidenceIds(
        inferredByDevice: Map<String, List<LocationDayItem>>,
        payloads: List<HistoryPayload>,
        contextStart: Instant,
        contextEnd: Instant,
    ): Set<String> =
        inferredByDevice.values
            .flatten()
            .filter { it.overlaps(contextStart, contextEnd) }
            .flatMap { it.evidenceIds }
            .toSet() +
            payloads
                .filterIsInstance<HistoryPayload.Manual>()
                .filter { it.value.start < contextEnd && it.value.end >= contextStart }
                .map { it.value.id }

    private suspend fun refreshLinkedNotes(
        linkedIds: Set<Uuid>,
        revision: Long,
    ) {
        linkedNotes.keys.retainAll(linkedIds)
        linkedNotesFailed = false
        for (noteId in linkedIds) {
            try {
                val note = notes.getNoteById(noteId)
                if (note == null) linkedNotes.remove(noteId) else linkedNotes[noteId] = note
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                linkedNotesFailed = true
                Napier.w("Linked location memory unavailable", error)
            }
        }
        previousLinkIds = linkedIds
        previousNotesRevision = revision
    }
}

/**
 * Wraps an optional reactive location-history input so upstream failures preserve the last good list and
 * increment [revision] only on successful emissions.
 */
internal data class OptionalHistorySection<T>(
    val items: List<T>,
    val failure: HistoryFailedSection? = null,
    val revision: Long = 0,
)

/**
 * Observes [source] and degrades stream errors into partial-failure state instead of cancelling sibling flows
 * in `combine(...)`.
 */
internal fun <T> optionalHistorySection(
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
