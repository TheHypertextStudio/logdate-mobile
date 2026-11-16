package app.logdate.client.domain.location

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.NoteType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ObserveLocationMemoryPlacesUseCase(
    private val notesRepository: JournalNotesRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    operator fun invoke(filter: LocationMemoryTimeFilter): Flow<List<LocationMemoryPlace>> =
        notesRepository.allNotesObserved.map { notes ->
            aggregatePlaces(
                notes = notes,
                filter = filter,
                now = clock.now(),
            )
        }

    internal fun aggregatePlaces(
        notes: List<JournalNote>,
        filter: LocationMemoryTimeFilter,
        now: Instant,
    ): List<LocationMemoryPlace> {
        val filteredNotes =
            notes
                .filter { note -> note.location?.hasLocation == true }
                .filter { note -> note.isWithin(filter, now) }

        return filteredNotes
            .groupBy { note -> note.locationGroupKey() }
            .map { (_, groupedNotes) -> groupedNotes.toLocationMemoryPlace() }
            .sortedByDescending(LocationMemoryPlace::lastVisitedAt)
    }

    private fun JournalNote.isWithin(
        filter: LocationMemoryTimeFilter,
        now: Instant,
    ): Boolean = filter.contains(creationTimestamp, now, timeZone)

    private fun JournalNote.locationGroupKey(): String {
        val location = requireNotNull(location)
        val place = location.place
        if (place != null) {
            return "place:${place.id}"
        }

        val latitude = requireNotNull(location.effectiveLatitude)
        val longitude = requireNotNull(location.effectiveLongitude)
        return "coordinates:${roundCoordinate(latitude)},${roundCoordinate(longitude)}"
    }

    private fun List<JournalNote>.toLocationMemoryPlace(): LocationMemoryPlace {
        val firstLocation = first().location
        val semanticPlace = firstLocation?.place
        val latitude = mapNotNull { note -> note.location?.effectiveLatitude }.average()
        val longitude = mapNotNull { note -> note.location?.effectiveLongitude }.average()
        val memories = sortedByDescending(JournalNote::creationTimestamp).map { note -> note.toLocationMemory() }

        return LocationMemoryPlace(
            id = first().locationGroupKey(),
            semanticName = semanticPlace?.name,
            latitude = semanticPlace?.latitude ?: latitude,
            longitude = semanticPlace?.longitude ?: longitude,
            lastVisitedAt = memories.maxOf(LocationMemory::timestamp),
            memories = memories,
        )
    }

    private fun JournalNote.toLocationMemory(): LocationMemory {
        val location = requireNotNull(location)
        return LocationMemory(
            noteId = uid,
            timestamp = creationTimestamp,
            type = type,
            preview = previewText(),
            latitude = requireNotNull(location.effectiveLatitude),
            longitude = requireNotNull(location.effectiveLongitude),
        )
    }

    private fun JournalNote.previewText(): String =
        when (this) {
            is JournalNote.Handwriting -> "Handwritten memory"
            is JournalNote.Text -> content
            is JournalNote.Image -> "Photo memory"
            is JournalNote.Audio -> "Audio memory"
            is JournalNote.Video -> "Video memory"
        }

    private fun roundCoordinate(value: Double): Double = round(value * 10_000) / 10_000
}

sealed class LocationMemoryTimeFilter {
    data object Last30Days : LocationMemoryTimeFilter()

    data object Last90Days : LocationMemoryTimeFilter()

    data object YearToDate : LocationMemoryTimeFilter()

    data object AllTime : LocationMemoryTimeFilter()

    data class Custom(
        val startInclusive: LocalDate? = null,
        val endInclusive: LocalDate? = null,
    ) : LocationMemoryTimeFilter() {
        init {
            require(startInclusive == null || endInclusive == null || startInclusive <= endInclusive) {
                "Custom location memory range start must be on or before end."
            }
        }
    }
}

internal fun LocationMemoryTimeFilter.contains(
    timestamp: Instant,
    now: Instant,
    timeZone: TimeZone,
): Boolean {
    val noteDate = timestamp.toLocalDateTime(timeZone).date
    val currentDate = now.toLocalDateTime(timeZone).date

    return if (this == LocationMemoryTimeFilter.Last30Days) {
        noteDate.isWithin(
            startInclusive = currentDate.minus(DatePeriod(days = 29)),
            endInclusive = currentDate,
        )
    } else if (this == LocationMemoryTimeFilter.Last90Days) {
        noteDate.isWithin(
            startInclusive = currentDate.minus(DatePeriod(days = 89)),
            endInclusive = currentDate,
        )
    } else if (this == LocationMemoryTimeFilter.YearToDate) {
        noteDate.isWithin(
            startInclusive = LocalDate(currentDate.year, 1, 1),
            endInclusive = currentDate,
        )
    } else if (this == LocationMemoryTimeFilter.AllTime) {
        true
    } else if (this is LocationMemoryTimeFilter.Custom) {
        noteDate.isWithin(
            startInclusive = startInclusive,
            endInclusive = endInclusive,
        )
    } else {
        Napier.w("Unexpected location memory filter encountered while matching notes: $this")
        true
    }
}

private fun LocalDate.isWithin(
    startInclusive: LocalDate?,
    endInclusive: LocalDate?,
): Boolean {
    val isAfterStart = startInclusive == null || this >= startInclusive
    val isBeforeEnd = endInclusive == null || this <= endInclusive
    return isAfterStart && isBeforeEnd
}

data class LocationMemoryPlace(
    val id: String,
    val semanticName: String?,
    val latitude: Double,
    val longitude: Double,
    val lastVisitedAt: Instant,
    val memories: List<LocationMemory>,
)

data class LocationMemory(
    val noteId: Uuid,
    val timestamp: Instant,
    val type: NoteType,
    val preview: String,
    val latitude: Double,
    val longitude: Double,
)
