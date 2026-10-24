package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.timeline.MomentAudioUiState
import app.logdate.util.localTime
import app.logdate.util.toReadableDateShort
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_missing_visit_hint
import logdate.client.feature.location.timeline.generated.resources.history_around_time
import logdate.client.feature.location.timeline.generated.resources.history_journey_minutes
import logdate.client.feature.location.timeline.generated.resources.history_location_recorded
import logdate.client.feature.location.timeline.generated.resources.history_memory_handwriting
import logdate.client.feature.location.timeline.generated.resources.history_memory_photo
import logdate.client.feature.location.timeline.generated.resources.history_memory_recording
import logdate.client.feature.location.timeline.generated.resources.history_memory_video
import logdate.client.feature.location.timeline.generated.resources.history_not_recorded
import logdate.client.feature.location.timeline.generated.resources.history_place_last_visited
import logdate.client.feature.location.timeline.generated.resources.history_place_latest_memory
import logdate.client.feature.location.timeline.generated.resources.history_place_location_recorded
import logdate.client.feature.location.timeline.generated.resources.history_place_you_visited
import logdate.client.feature.location.timeline.generated.resources.history_travel_bus
import logdate.client.feature.location.timeline.generated.resources.history_travel_cycled
import logdate.client.feature.location.timeline.generated.resources.history_travel_drove
import logdate.client.feature.location.timeline.generated.resources.history_travel_ran
import logdate.client.feature.location.timeline.generated.resources.history_travel_train
import logdate.client.feature.location.timeline.generated.resources.history_travel_travelling
import logdate.client.feature.location.timeline.generated.resources.history_travel_walked
import org.jetbrains.compose.resources.StringResource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal fun LocationDayItem.toHistoryUi(notes: List<JournalNote>): HistoryItemUi =
    when (this) {
        is PlaceVisit -> {
            HistoryItemUi(
                id,
                HistoryItemKind.Visit,
                place?.name?.toUiText()
                    ?: (if (confirmedStay) Res.string.history_place_you_visited else Res.string.history_location_recorded).toUiText(),
                if (confirmedStay) historyTimeRange().toUiText() else Res.string.history_around_time.toUiText(start.localTime),
                place?.locality.orEmpty().toUiText(),
                notes.filter { it.uid.toString() in memoryIds }.map { it.toHistoryMemory() },
                isApproximate = !confirmedStay,
            )
        }

        is JourneyLeg -> {
            HistoryItemUi(
                id,
                HistoryItemKind.Journey,
                mode.humanName().toUiText(),
                historyTimeRange().toUiText(),
                if (end > start) {
                    Res.string.history_journey_minutes.toUiText((end - start).inWholeMinutes)
                } else {
                    Res.string.history_around_time.toUiText(start.localTime)
                },
            )
        }

        is HistoryGap -> {
            HistoryItemUi(
                id,
                HistoryItemKind.Gap,
                Res.string.history_not_recorded.toUiText(),
                historyTimeRange().toUiText(),
                Res.string.history_add_missing_visit_hint.toUiText(),
            )
        }
    }

private fun LocationDayItem.historyTimeRange(): String {
    val zone = TimeZone.currentSystemDefault()
    return if (start.toLocalDateTime(zone).date == end.toLocalDateTime(zone).date) {
        "${start.localTime}–${end.localTime}"
    } else {
        "${start.toReadableDateShort()}, ${start.localTime}–${end.toReadableDateShort()}, ${end.localTime}"
    }
}

internal fun TravelMode.humanName(): StringResource =
    when (this) {
        TravelMode.WALKING -> Res.string.history_travel_walked
        TravelMode.RUNNING -> Res.string.history_travel_ran
        TravelMode.CYCLING -> Res.string.history_travel_cycled
        TravelMode.DRIVING -> Res.string.history_travel_drove
        TravelMode.BUS -> Res.string.history_travel_bus
        TravelMode.TRAIN -> Res.string.history_travel_train
        else -> Res.string.history_travel_travelling
    }

internal fun JournalNote.toHistoryMemory(): HistoryMemoryUi =
    when (this) {
        is JournalNote.Handwriting -> {
            HistoryMemoryUi(
                uid.toString(),
                Res.string.history_memory_handwriting.toUiText(),
                HistoryMemoryKind.Text,
                creationTimestamp.toReadableDateShort(),
            )
        }

        is JournalNote.Text -> {
            HistoryMemoryUi(uid.toString(), content.toUiText(), HistoryMemoryKind.Text, creationTimestamp.toReadableDateShort())
        }

        is JournalNote.Image -> {
            HistoryMemoryUi(
                uid.toString(),
                if (caption.isBlank()) Res.string.history_memory_photo.toUiText() else caption.toUiText(),
                HistoryMemoryKind.Image,
                creationTimestamp.toReadableDateShort(),
                mediaRef,
            )
        }

        is JournalNote.Video -> {
            HistoryMemoryUi(
                uid.toString(),
                if (caption.isBlank()) Res.string.history_memory_video.toUiText() else caption.toUiText(),
                HistoryMemoryKind.Video,
                creationTimestamp.toReadableDateShort(),
                mediaRef,
            )
        }

        is JournalNote.Audio -> {
            HistoryMemoryUi(
                uid.toString(),
                Res.string.history_memory_recording.toUiText(),
                HistoryMemoryKind.Audio,
                creationTimestamp.toReadableDateShort(),
                audio = MomentAudioUiState(mediaRef, durationMs, noteId = uid, recordedAt = creationTimestamp),
            )
        }
    }

internal fun LocationHistorySnapshot.visitPlaces(): List<SemanticPlace> =
    items
        .filterIsInstance<PlaceVisit>()
        .map {
            it.place ?: SemanticPlace("evidence:${it.evidenceIds.first()}", "A place you visited", it.latitude, it.longitude)
        }.distinctBy { it.id }

/** A place can have memories without evidence of a physical visit. */
internal fun LocationHistorySnapshot.collectionPlaces(): List<SemanticPlace> {
    val visited = visitPlaces()
    val linked = items.filterIsInstance<PlaceVisit>().flatMap { it.memoryIds }.toSet()
    val savedPlacesById = places.associateBy { it.id }
    val memoryPlaces =
        notes.filterNot { it.uid.toString() in linked }.mapNotNull { note ->
            val location = note.location ?: return@mapNotNull null
            val latitude = location.effectiveLatitude ?: return@mapNotNull null
            val longitude = location.effectiveLongitude ?: return@mapNotNull null
            val oldId = location.place?.id?.toString() ?: "memory:${note.uid}"
            val id = placeAliases[oldId] ?: oldId
            savedPlacesById[id] ?: SemanticPlace(id, location.place?.name ?: "A place in your memories", latitude, longitude)
        }
    return (visited + memoryPlaces).distinctBy { it.id }
}

internal fun LocationHistorySnapshot.placeRows(): List<HistoryPlaceUi> {
    val visitsByPlace =
        items.filterIsInstance<PlaceVisit>().groupBy { it.place?.id ?: "evidence:${it.evidenceIds.first()}" }
    val notesById = notes.associateBy { it.uid.toString() }
    val linkedIds =
        visitsByPlace.values
            .flatten()
            .flatMap { it.memoryIds }
            .toSet()
    val memoriesByPlace = mutableMapOf<String, MutableList<JournalNote>>()
    visitsByPlace.forEach { (placeId, visits) ->
        visits.flatMap { it.memoryIds }.forEach { memoryId ->
            notesById[memoryId]?.let { memoriesByPlace.getOrPut(placeId) { mutableListOf() }.add(it) }
        }
    }
    notes.filterNot { it.uid.toString() in linkedIds }.forEach { note ->
        val oldId =
            note.location
                ?.place
                ?.id
                ?.toString() ?: "memory:${note.uid}"
        val placeId = placeAliases[oldId] ?: oldId
        memoriesByPlace.getOrPut(placeId) { mutableListOf() }.add(note)
    }
    return groupedCollectionPlaces()
        .map { group ->
            val place = group.first()
            val ids = group.mapTo(mutableSetOf()) { it.id }
            val visits = ids.flatMap { visitsByPlace[it].orEmpty() }
            val memories = ids.flatMap { memoriesByPlace[it].orEmpty() }.distinctBy { it.uid }.sortedByDescending { it.creationTimestamp }
            val lastVisit =
                visits.filter { it.confirmedStay }.maxByOrNull { it.start }?.let {
                    val count = visits.count { visit -> visit.confirmedStay }
                    Res.plurals.history_place_last_visited.toUiText(count, it.start.toReadableDateShort(), count)
                }
            val lastClue =
                if (lastVisit == null) {
                    visits.maxByOrNull { it.start }?.let {
                        Res.string.history_place_location_recorded.toUiText(it.start.toReadableDateShort())
                    }
                } else {
                    null
                }
            val latestMemory =
                memories.firstOrNull()?.let { Res.string.history_place_latest_memory.toUiText(it.creationTimestamp.toReadableDateShort()) }
            HistoryPlaceUi(
                place.id,
                place.name,
                listOfNotNull(place.locality?.toUiText(), lastVisit, lastClue, latestMemory),
                memories.map { it.toHistoryMemory() },
                ids,
            )
        }.sortedByDescending { row ->
            row.sourceIds.flatMap { visitsByPlace[it].orEmpty() }.maxOfOrNull { it.start }
                ?: row.sourceIds.flatMap { memoriesByPlace[it].orEmpty() }.maxOfOrNull { it.creationTimestamp }
        }
}

private fun LocationHistorySnapshot.groupedCollectionPlaces(): List<List<SemanticPlace>> {
    val groups = mutableListOf<MutableList<SemanticPlace>>()
    val groupsByBucket = mutableMapOf<String, MutableList<MutableList<SemanticPlace>>>()
    collectionPlaces().forEach { place ->
        val bucket =
            if (place.userConfirmed) {
                "confirmed:${place.id}"
            } else {
                "${place.name.trim().lowercase()}:${(place.latitude * 1000).roundToInt()}:${(place.longitude * 1000).roundToInt()}"
            }
        val candidates = groupsByBucket.getOrPut(bucket) { mutableListOf() }
        val group = candidates.firstOrNull { nearby(it.first(), place) }
        if (group == null) {
            mutableListOf(place).also {
                candidates.add(it)
                groups.add(it)
            }
        } else {
            group.add(place)
        }
    }
    return groups
}

private fun nearby(
    first: SemanticPlace,
    second: SemanticPlace,
): Boolean {
    if (first.id == second.id) return true
    if (first.userConfirmed || second.userConfirmed || first.name != second.name) return false
    val latitude = (second.latitude - first.latitude) * PI / 180
    val longitude = (second.longitude - first.longitude) * PI / 180
    val haversine =
        sin(latitude / 2) * sin(latitude / 2) +
            cos(first.latitude * PI / 180) * cos(second.latitude * PI / 180) * sin(longitude / 2) * sin(longitude / 2)
    return 6371000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0))) <= 75
}
