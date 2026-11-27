package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.timeline.MomentAudioUiState
import app.logdate.util.toReadableDateShort
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_alternative_place_chip
import logdate.client.feature.location.timeline.generated.resources.history_approx_minutes
import logdate.client.feature.location.timeline.generated.resources.history_around_time
import logdate.client.feature.location.timeline.generated.resources.history_between_visits
import logdate.client.feature.location.timeline.generated.resources.history_confirm_place_chip
import logdate.client.feature.location.timeline.generated.resources.history_distance_km
import logdate.client.feature.location.timeline.generated.resources.history_distance_m
import logdate.client.feature.location.timeline.generated.resources.history_duration_minutes
import logdate.client.feature.location.timeline.generated.resources.history_gap_add_prompt
import logdate.client.feature.location.timeline.generated.resources.history_last_recorded_clue
import logdate.client.feature.location.timeline.generated.resources.history_last_visited_with_count
import logdate.client.feature.location.timeline.generated.resources.history_latest_memory_date
import logdate.client.feature.location.timeline.generated.resources.history_location_recorded
import logdate.client.feature.location.timeline.generated.resources.history_memory_audio_fallback
import logdate.client.feature.location.timeline.generated.resources.history_memory_handwriting
import logdate.client.feature.location.timeline.generated.resources.history_memory_photo_fallback
import logdate.client.feature.location.timeline.generated.resources.history_memory_video_fallback
import logdate.client.feature.location.timeline.generated.resources.history_mode_bus
import logdate.client.feature.location.timeline.generated.resources.history_mode_cycled
import logdate.client.feature.location.timeline.generated.resources.history_mode_drove
import logdate.client.feature.location.timeline.generated.resources.history_mode_ran
import logdate.client.feature.location.timeline.generated.resources.history_mode_train
import logdate.client.feature.location.timeline.generated.resources.history_mode_travelling
import logdate.client.feature.location.timeline.generated.resources.history_mode_walked
import logdate.client.feature.location.timeline.generated.resources.history_place_in_memories
import logdate.client.feature.location.timeline.generated.resources.history_recording_paused_title
import logdate.client.feature.location.timeline.generated.resources.history_signal_interrupted_title
import logdate.client.feature.location.timeline.generated.resources.history_time_range
import logdate.client.feature.location.timeline.generated.resources.history_unknown_place_visited
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

internal fun LocationDayItem.toHistoryUi(
    notes: List<JournalNote>,
    fallbackZone: TimeZone = TimeZone.currentSystemDefault(),
    allPlaces: List<SemanticPlace> = emptyList(),
    canMergePrevious: Boolean = false,
    canMergeNext: Boolean = false,
): HistoryItemUi =
    when (this) {
        is PlaceVisit -> toVisitUi(notes, fallbackZone, allPlaces, canMergePrevious, canMergeNext)
        is JourneyLeg -> toJourneyUi(fallbackZone)
        is HistoryGap -> toGapUi(fallbackZone)
    }

private fun PlaceVisit.toVisitUi(
    notes: List<JournalNote>,
    fallbackZone: TimeZone,
    allPlaces: List<SemanticPlace>,
    canMergePrevious: Boolean,
    canMergeNext: Boolean,
): HistoryItemUi {
    val zone = resolveZone(displayZoneId, fallbackZone)
    val placesById = allPlaces.associateBy { it.id }
    val candidates =
        buildList {
            val primaryPlace = place
            if (primaryPlace != null && !primaryPlace.userConfirmed) {
                add(
                    PlaceCandidateChipUi(
                        primaryPlace,
                        UiText.Resource(Res.string.history_confirm_place_chip, listOf(primaryPlace.name)),
                        isConfirmation = true,
                    ),
                )
            }
            candidatePlaceIds
                .filter { it != primaryPlace?.id }
                .mapNotNull(placesById::get)
                .take(2)
                .forEach { candidate ->
                    add(
                        PlaceCandidateChipUi(
                            candidate,
                            UiText.Resource(Res.string.history_alternative_place_chip, listOf(candidate.name)),
                            isConfirmation = false,
                        ),
                    )
                }
        }
    return HistoryItemUi(
        id = id,
        kind = HistoryItemKind.Visit,
        title =
            place?.name?.toUiText()
                ?: UiText.Resource(
                    if (confirmedStay) Res.string.history_unknown_place_visited else Res.string.history_location_recorded,
                ),
        timeLabel =
            if (confirmedStay) {
                historyTimeRange(zone)
            } else {
                UiText.Resource(Res.string.history_around_time, listOf(start.formatLocalTime(zone)))
            },
        supportingText = place?.locality?.takeIf { it.isNotBlank() }?.toUiText(),
        memories = notes.filter { it.uid in memoryIds }.map { it.toHistoryMemory() },
        isApproximate = !confirmedStay,
        placeId = place?.id,
        placeCategory = place?.category,
        placeConfirmed = place?.userConfirmed == true,
        disambiguationCandidates = candidates,
        canMergePrevious = canMergePrevious,
        canMergeNext = canMergeNext,
        canSplit = confirmedStay && (end - start).inWholeMinutes >= 10,
        anchorEvidenceId = evidenceIds.firstOrNull(),
    )
}

private fun JourneyLeg.toJourneyUi(fallbackZone: TimeZone): HistoryItemUi {
    val zone = resolveZone(displayZoneId, fallbackZone)
    val isConnector = route.isEmpty() && smoothedRoute.isEmpty()
    val durationMinutes = (end - start).inWholeMinutes.toInt()
    val distanceLabel = formatRouteDistanceText(distanceMeters)
    val supporting =
        when {
            isConnector && durationMinutes > 0 -> {
                UiText.Resource(Res.string.history_approx_minutes, listOf(durationMinutes))
            }

            isConnector -> {
                UiText.Resource(Res.string.history_around_time, listOf(start.formatLocalTime(zone)))
            }

            durationMinutes > 0 -> {
                val durText = UiText.Resource(Res.string.history_duration_minutes, listOf(durationMinutes))
                distanceLabel?.let { UiText.Joined(listOf(it, durText), " · ") } ?: durText
            }

            else -> {
                distanceLabel ?: UiText.Resource(Res.string.history_around_time, listOf(start.formatLocalTime(zone)))
            }
        }
    return HistoryItemUi(
        id = id,
        kind = HistoryItemKind.Journey,
        title = if (isConnector) UiText.Resource(Res.string.history_between_visits) else mode.labelText(),
        timeLabel = historyTimeRange(zone),
        supportingText = supporting,
        travelMode = mode,
        isConnector = isConnector,
        anchorEvidenceId = evidenceIds.firstOrNull(),
    )
}

private fun HistoryGap.toGapUi(fallbackZone: TimeZone): HistoryItemUi {
    val paused = evidenceIds.any { it.contains("pause", ignoreCase = true) }
    return HistoryItemUi(
        id = id,
        kind = HistoryItemKind.Gap,
        title =
            UiText.Resource(
                if (paused) Res.string.history_recording_paused_title else Res.string.history_signal_interrupted_title,
            ),
        timeLabel = historyTimeRange(fallbackZone),
        supportingText = UiText.Resource(Res.string.history_gap_add_prompt),
    )
}

internal fun formatRouteDistanceText(meters: Double): UiText? {
    if (!meters.isFinite() || meters < 50.0) return null
    return if (meters >= 1000.0) {
        val tenths = ((meters / 1000.0) * 10.0).roundToInt()
        UiText.Resource(Res.string.history_distance_km, listOf("${tenths / 10}.${tenths % 10}"))
    } else {
        UiText.Resource(Res.string.history_distance_m, listOf(meters.roundToInt()))
    }
}

private fun resolveZone(
    zoneId: String?,
    fallback: TimeZone,
): TimeZone = zoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: fallback

internal fun Instant.formatLocalTimeLabel(zone: TimeZone): String = formatLocalTime(zone)

private fun Instant.formatLocalTime(zone: TimeZone): String {
    val dt: LocalDateTime = toLocalDateTime(zone)
    val hour12 =
        when (val h = dt.hour % 12) {
            0 -> 12
            else -> h
        }
    val minute = dt.minute.toString().padStart(2, '0')
    val suffix = if (dt.hour < 12) "AM" else "PM"
    return "$hour12:$minute $suffix"
}

internal fun LocationDayItem.historyTimeRange(zone: TimeZone): UiText =
    if (start.toLocalDateTime(zone).date == end.toLocalDateTime(zone).date) {
        UiText.Resource(Res.string.history_time_range, listOf(start.formatLocalTime(zone), end.formatLocalTime(zone)))
    } else {
        UiText.Resource(
            Res.string.history_time_range,
            listOf(
                "${start.toReadableDateShort()}, ${start.formatLocalTime(zone)}",
                "${end.toReadableDateShort()}, ${end.formatLocalTime(zone)}",
            ),
        )
    }

internal fun TravelMode.labelText(): UiText =
    UiText.Resource(
        when (this) {
            TravelMode.WALKING -> Res.string.history_mode_walked
            TravelMode.RUNNING -> Res.string.history_mode_ran
            TravelMode.CYCLING -> Res.string.history_mode_cycled
            TravelMode.DRIVING -> Res.string.history_mode_drove
            TravelMode.BUS -> Res.string.history_mode_bus
            TravelMode.TRAIN -> Res.string.history_mode_train
            else -> Res.string.history_mode_travelling
        },
    )

internal fun JournalNote.toHistoryMemory(): HistoryMemoryUi =
    when (this) {
        is JournalNote.Handwriting -> {
            HistoryMemoryUi(
                uid,
                UiText.Resource(Res.string.history_memory_handwriting),
                HistoryMemoryKind.Text,
                creationTimestamp.toReadableDateShort().toUiText(),
                handwriting = document,
                handwritingUnreadable = unreadableContent != null,
            )
        }

        is JournalNote.Text -> {
            HistoryMemoryUi(
                uid,
                content.toUiText(),
                HistoryMemoryKind.Text,
                creationTimestamp.toReadableDateShort().toUiText(),
            )
        }

        is JournalNote.Image -> {
            HistoryMemoryUi(
                uid,
                caption.takeIf { it.isNotBlank() }?.toUiText() ?: UiText.Resource(Res.string.history_memory_photo_fallback),
                HistoryMemoryKind.Image,
                creationTimestamp.toReadableDateShort().toUiText(),
                mediaRef,
            )
        }

        is JournalNote.Video -> {
            HistoryMemoryUi(
                uid,
                caption.takeIf { it.isNotBlank() }?.toUiText() ?: UiText.Resource(Res.string.history_memory_video_fallback),
                HistoryMemoryKind.Video,
                creationTimestamp.toReadableDateShort().toUiText(),
                mediaRef,
            )
        }

        is JournalNote.Audio -> {
            HistoryMemoryUi(
                uid,
                UiText.Resource(Res.string.history_memory_audio_fallback),
                HistoryMemoryKind.Audio,
                creationTimestamp.toReadableDateShort().toUiText(),
                audio = MomentAudioUiState(mediaRef, durationMs, noteId = uid, recordedAt = creationTimestamp),
            )
        }
    }

internal fun LocationHistorySnapshot.visitPlaces(): List<SemanticPlace> =
    items.filterIsInstance<PlaceVisit>().mapNotNull { it.place }.distinctBy { it.id }

/** A place can have memories without evidence of a physical visit. */
internal fun LocationHistorySnapshot.collectionPlaces(): List<SemanticPlace> {
    val visited = visitPlaces()
    val linked = items.filterIsInstance<PlaceVisit>().flatMap { it.memoryIds }.toSet()
    val savedPlacesById = places.associateBy { it.id }
    val memoryPlaces =
        notes.filterNot { it.uid in linked }.mapNotNull { note ->
            val location = note.location ?: return@mapNotNull null
            val latitude = location.effectiveLatitude ?: return@mapNotNull null
            val longitude = location.effectiveLongitude ?: return@mapNotNull null
            val oldId = location.place?.id?.toString() ?: "memory:${note.uid}"
            val id = placeAliases[oldId] ?: oldId
            savedPlacesById[id] ?: SemanticPlace(id, location.place?.name.orEmpty(), latitude, longitude)
        }
    return (visited + memoryPlaces).distinctBy { it.id }
}

internal fun LocationHistorySnapshot.placeRows(): List<HistoryPlaceUi> {
    val visitsByPlace = items.filterIsInstance<PlaceVisit>().groupBy { it.place?.id ?: "evidence:${it.evidenceIds.first()}" }
    val memoriesByPlace = collectMemoriesByPlace(visitsByPlace)
    return groupedCollectionPlaces()
        .map { group -> buildPlaceRow(group, visitsByPlace, memoriesByPlace) }
        .sortedByDescending { row ->
            row.sourceIds.flatMap { visitsByPlace[it].orEmpty() }.maxOfOrNull { it.start }
                ?: row.sourceIds.flatMap { memoriesByPlace[it].orEmpty() }.maxOfOrNull { it.creationTimestamp }
        }
}

private fun LocationHistorySnapshot.collectMemoriesByPlace(visitsByPlace: Map<String, List<PlaceVisit>>): Map<String, List<JournalNote>> {
    val notesById = notes.associateBy { it.uid }
    val linkedIds = visitsByPlace.values.flatten().flatMapTo(mutableSetOf()) { it.memoryIds }
    val memoriesByPlace = mutableMapOf<String, MutableList<JournalNote>>()
    visitsByPlace.forEach { (placeId, visits) ->
        visits.flatMap { it.memoryIds }.forEach { memoryId ->
            notesById[memoryId]?.let { memoriesByPlace.getOrPut(placeId) { mutableListOf() }.add(it) }
        }
    }
    notes.filterNot { it.uid in linkedIds }.forEach { note ->
        val oldId =
            note.location
                ?.place
                ?.id
                ?.toString() ?: "memory:${note.uid}"
        val placeId = placeAliases[oldId] ?: oldId
        memoriesByPlace.getOrPut(placeId) { mutableListOf() }.add(note)
    }
    return memoriesByPlace
}

private fun buildPlaceRow(
    group: List<SemanticPlace>,
    visitsByPlace: Map<String, List<PlaceVisit>>,
    memoriesByPlace: Map<String, List<JournalNote>>,
): HistoryPlaceUi {
    val place = group.first()
    val ids = group.mapTo(mutableSetOf()) { it.id }
    val visits = ids.flatMap { visitsByPlace[it].orEmpty() }
    val memories = ids.flatMap { memoriesByPlace[it].orEmpty() }.distinctBy { it.uid }.sortedByDescending { it.creationTimestamp }
    val count = visits.count { it.confirmedStay }
    val lastVisit =
        visits.filter { it.confirmedStay }.maxByOrNull { it.start }?.let {
            UiText.Plural(Res.plurals.history_last_visited_with_count, count, listOf(it.start.toReadableDateShort(), count))
        }
    val lastClue =
        if (lastVisit == null) {
            visits.maxByOrNull { it.start }?.let {
                UiText.Resource(Res.string.history_last_recorded_clue, listOf(it.start.toReadableDateShort()))
            }
        } else {
            null
        }
    val latestMemory =
        memories.firstOrNull()?.let {
            UiText.Resource(Res.string.history_latest_memory_date, listOf(it.creationTimestamp.toReadableDateShort()))
        }
    val latestTimeMs =
        maxOf(
            visits.maxOfOrNull { it.start.toEpochMilliseconds() } ?: 0L,
            memories.maxOfOrNull { it.creationTimestamp.toEpochMilliseconds() } ?: 0L,
        )
    val title = place.name.takeIf { it.isNotBlank() }?.toUiText() ?: UiText.Resource(Res.string.history_place_in_memories)
    val supportingParts = listOfNotNull(place.locality?.takeIf { it.isNotBlank() }?.toUiText(), lastVisit, lastClue, latestMemory)
    return HistoryPlaceUi(
        id = place.id,
        title = title,
        supportingText = UiText.Joined(supportingParts, "\n"),
        memories = memories.map { it.toHistoryMemory() },
        sourceIds = ids,
        category = place.category,
        visitCount = count,
        latestTimestampMs = latestTimeMs,
    )
}

private fun LocationHistorySnapshot.groupedCollectionPlaces(): List<List<SemanticPlace>> {
    val visitedWithUnnamed =
        collectionPlaces() +
            items
                .filterIsInstance<PlaceVisit>()
                .filter { it.place == null }
                .map { SemanticPlace("evidence:${it.evidenceIds.first()}", "", it.latitude, it.longitude) }
    val groups = mutableListOf<MutableList<SemanticPlace>>()
    val groupsByBucket = mutableMapOf<String, MutableList<MutableList<SemanticPlace>>>()
    visitedWithUnnamed.distinctBy { it.id }.forEach { place ->
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
