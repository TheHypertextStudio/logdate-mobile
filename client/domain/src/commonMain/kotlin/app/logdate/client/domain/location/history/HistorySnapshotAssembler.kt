package app.logdate.client.domain.location.history

import app.logdate.client.device.crypto.activityHistoryRecordId
import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.Place
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Decoded raw and synced evidence collected for a single reconstructed window before day segmentation. */
internal data class RangeEvidence(
    val payloads: List<HistoryPayload>,
    val observations: List<LocationObservation>,
    val activities: List<ActivityObservation>,
    val deletedSamples: Set<String>,
    val deletedActivities: Set<String>,
)

/** Per-device reconstructed timelines together with the window's resolved display timezone metadata. */
internal data class ReconstructedRange(
    val effectiveZone: TimeZone,
    val isMultiZoneDay: Boolean,
    val inferredByDevice: Map<String, List<LocationDayItem>>,
)

/**
 * Decodes a [HistoryRecord] payload JSON string, returning `null` for tombstones or forward-incompatible
 * schemas rather than failing the whole timeline.
 */
internal fun decodeHistoryRecord(
    json: Json,
    record: HistoryRecord,
): HistoryPayload? = record.payload?.let { runCatching { json.decodeFromString(HistoryPayload.serializer(), it) }.getOrNull() }

/**
 * Combines local SQLite sensor streams, saved user places, and synced history payloads into deduplicated
 * evidence while honoring `observation:`, `activity`, and `place` tombstones.
 */
internal fun extractRangeEvidence(
    records: List<HistoryRecord>,
    savedPlaces: List<Place>,
    local: List<LocationHistoryItem>,
    localActivities: List<ActivityHistoryItem>,
    ownerId: String,
    localDevice: String,
    contextStart: Instant,
    contextEnd: Instant,
    decode: (HistoryRecord) -> HistoryPayload?,
): RangeEvidence {
    val deletedPlaces = records.filter { it.deleted && it.recordType == "place" }.mapTo(mutableSetOf()) { it.id }
    val payloads =
        records.filterNot { it.deleted }.mapNotNull(decode) +
            savedPlaces
                .filterNot { it.uid.toString() in deletedPlaces }
                .map { HistoryPayload.Place(it.toSemanticPlace()) }
    val deletedSamples =
        records
            .filter {
                it.deleted && it.recordType == "observation"
            }.mapTo(mutableSetOf()) { it.id.removePrefix("observation:") }
    val observations = filterObservations(local, payloads, ownerId, localDevice, deletedSamples, contextStart, contextEnd)
    val deletedActivities = records.filter { it.deleted && it.recordType == "activity" }.mapTo(mutableSetOf()) { it.id }
    val activities = mergeActivities(localActivities, payloads, deletedActivities)
    return RangeEvidence(payloads, observations, activities, deletedSamples, deletedActivities)
}

internal fun Place.toSemanticPlace(): SemanticPlace {
    val userPlace = this as? Place.UserDefined
    return SemanticPlace(
        id = uid.toString(),
        name = name,
        latitude = latitude,
        longitude = longitude,
        userConfirmed = true,
        radiusMeters = userPlace?.radiusMeters ?: 75.0,
        category = userPlace?.category,
    )
}

private fun filterObservations(
    local: List<LocationHistoryItem>,
    payloads: List<HistoryPayload>,
    ownerId: String,
    localDevice: String,
    deletedSamples: Set<String>,
    contextStart: Instant,
    contextEnd: Instant,
): List<LocationObservation> =
    (
        local
            .filter { it.userId == ownerId || (it.userId == "default_user" && it.deviceId == localDevice) }
            .map { it.toObservation(ownerId) } +
            payloads.filterIsInstance<HistoryPayload.Observation>().map { it.value }
    ).filter {
        it.ownerId == ownerId && it.id !in deletedSamples && it.timestamp >= contextStart && it.timestamp < contextEnd
    }

private fun mergeActivities(
    localActivities: List<ActivityHistoryItem>,
    payloads: List<HistoryPayload>,
    deletedActivities: Set<String>,
): List<ActivityObservation> =
    (
        localActivities.map {
            ActivityObservation(it.id, it.userId, it.deviceId, it.timestamp, it.activityType, it.transitionType, it.timeZoneId)
        } + payloads.filterIsInstance<HistoryPayload.Activity>().map { it.value }
    ).filterNot { activityHistoryRecordId(it.id) in deletedActivities }

/** Runs activity projection and v4 day reconstruction separately per recording device. */
internal fun reconstructRangeByDevice(
    carried: BoundaryEvidence,
    payloads: List<HistoryPayload>,
    windowStart: Instant,
    windowEnd: Instant,
    fallbackZone: TimeZone,
): ReconstructedRange {
    val (effectiveZone, isMultiZoneDay) = resolveCaptureZone(carried.observations, windowStart, windowEnd, fallbackZone)
    val enriched = applyActivityEvidence(carried.observations, carried.activities)
    val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
    val confirmedPlaces =
        applyPlaceLabels(
            payloads.filterIsInstance<HistoryPayload.Place>().map { it.value }.distinctBy { it.id },
            edits,
        ).filter { it.userConfirmed }
    val inferredByDevice =
        enriched
            .groupBy { it.deviceId }
            .mapValues { (_, samples) -> ReconstructLocationDay()(samples, savedPlaces = confirmedPlaces) }
    return ReconstructedRange(effectiveZone, isMultiZoneDay, inferredByDevice)
}

/**
 * Merges inferred timelines, manual visits, edits, semantic places, memories, and multi-device conflicts into
 * a single [LocationHistorySnapshot] clipped to `[start, end)`.
 */
internal fun assembleHistorySnapshot(
    inferred: List<LocationDayItem>,
    payloads: List<HistoryPayload>,
    notes: List<app.logdate.client.repository.journals.JournalNote>,
    devices: List<String>,
    selectedDevice: String,
    start: Instant,
    end: Instant,
    familiarityCounts: Map<String, Int> = emptyMap(),
): LocationHistorySnapshot {
    val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
    val places = applyPlaceLabels(payloads.filterIsInstance<HistoryPayload.Place>().map { it.value }.distinctBy { it.id }, edits)
    val aliases = resolvePlaceAliases(edits)
    val manual =
        payloads.filterIsInstance<HistoryPayload.Manual>().mapNotNull { payload ->
            val place = places.firstOrNull { it.id == payload.value.placeId } ?: return@mapNotNull null
            payload.value.let { PlaceVisit(it.id, it.start, it.end, listOf(it.id), place.latitude, place.longitude, true, place) }
        }
    val dated = ApplyHistoryEdits()(inferred + manual, edits, places).filter { it.overlaps(start, end) }.sortedBy { it.start }
    val visits = resolveVisitPlacesAndMemories(dated, places, aliases, edits, payloads, notes, familiarityCounts)
    val linkedNoteIds = visits.values.flatMapTo(mutableSetOf()) { it.memoryIds }
    val datedNotes = notes.filter { it.creationTimestamp in start..<end || it.uid in linkedNoteIds }
    val conflicts = detectDisplayedConflicts(dated, visits, datedNotes, edits, aliases)
    return LocationHistorySnapshot(
        items = dated.map { visits[it.id] ?: it },
        places = places,
        notes = datedNotes,
        recordingDevices = devices,
        selectedDeviceId = selectedDevice,
        conflicts = conflicts,
        placeAliases = aliases,
    )
}

private fun resolveVisitPlacesAndMemories(
    dated: List<LocationDayItem>,
    places: List<SemanticPlace>,
    aliases: Map<String, String>,
    edits: List<HistoryCorrection>,
    payloads: List<HistoryPayload>,
    notes: List<app.logdate.client.repository.journals.JournalNote>,
    familiarityCounts: Map<String, Int>,
): Map<String, PlaceVisit> {
    val placesById = places.associateBy { it.id }
    val matchCandidates = places.filter { candidate -> aliases[candidate.id] == null || aliases[candidate.id] == candidate.id }
    val explicitPlaceCorrections =
        edits.filter { it.field == HistoryField.PLACE && !it.targetEvidenceId.startsWith("place:") }.associate {
            it.targetEvidenceId to
                it.value
        }
    val matcher = ScoredPlaceMatcher()
    val links = payloads.filterIsInstance<HistoryPayload.MemoryLink>().map { it.value }
    val matchedVisits =
        dated.filterIsInstance<PlaceVisit>().map { visit ->
            if (visit.place != null) {
                visit.copy(needsDisambiguation = false)
            } else {
                val explicitId = visit.evidenceIds.firstNotNullOfOrNull { explicitPlaceCorrections[it] }
                val match = matcher.match(visit, matchCandidates, familiarityCounts, explicitId)
                val resolvedAssigned = match.assigned?.let { placesById[aliases[it.id] ?: it.id] ?: it }
                visit.copy(
                    place = resolvedAssigned,
                    candidatePlaceIds = match.candidatePlaceIds,
                    needsDisambiguation = match.needsDisambiguation,
                )
            }
        }
    return AssociateVisitMemories()(matchedVisits, notes, links).associateBy { it.id }
}

private fun detectDisplayedConflicts(
    dated: List<LocationDayItem>,
    visits: Map<String, PlaceVisit>,
    datedNotes: List<app.logdate.client.repository.journals.JournalNote>,
    edits: List<HistoryCorrection>,
    aliases: Map<String, String>,
): List<HistoryCorrection> {
    val superseded = edits.flatMapTo(mutableSetOf()) { it.supersedes }
    val displayedPlaces =
        visits.values.mapNotNull { it.place?.id } +
            datedNotes.mapNotNull {
                it.location
                    ?.place
                    ?.id
                    ?.toString()
            }
    val displayedEvidence = dated.flatMapTo(mutableSetOf()) { it.evidenceIds } + displayedPlaces.map { "place:${aliases[it] ?: it}" }
    return edits
        .filter { it.id !in superseded && it.targetEvidenceId in displayedEvidence }
        .groupBy { it.targetEvidenceId to it.field }
        .values
        .filter { group -> group.map { it.value }.distinct().size > 1 }
        .flatten()
}

/**
 * Resolves place merge pointers (`place:<source> -> target`) into transitive final IDs.
 *
 * Cycles and conflicting multi-target merges are dropped (`null`) so neither corrupted edit syncs nor
 * unresolved conflicts crash place lookup.
 */
internal fun resolvePlaceAliases(edits: List<HistoryCorrection>): Map<String, String> {
    val superseded = edits.flatMap { it.supersedes }.toSet()
    val candidates =
        edits
            .filter { it.id !in superseded && it.field == HistoryField.PLACE && it.targetEvidenceId.startsWith("place:") }
            .groupBy { it.targetEvidenceId.removePrefix("place:") }
    val direct =
        candidates
            .mapNotNull { (source, values) ->
                val target = values.map { it.value }.distinct().singleOrNull()
                target?.let { source to it }
            }.toMap()
    return direct.keys
        .mapNotNull { source ->
            val visited = mutableSetOf<String>()
            var target = source
            while (target in direct && visited.add(target)) target = direct.getValue(target)
            if (target in visited || target in candidates) null else source to target
        }.toMap()
}

/** Applies unambiguous active `LABEL`, `RADIUS` (clamped to 30m..500m), and `CATEGORY` edits onto places. */
internal fun applyPlaceLabels(
    places: List<SemanticPlace>,
    edits: List<HistoryCorrection>,
): List<SemanticPlace> {
    val superseded = edits.flatMap { it.supersedes }.toSet()
    val byPlace = edits.filter { it.id !in superseded }.groupBy { it.targetEvidenceId }
    return places.map { place ->
        val placeEdits = byPlace["place:${place.id}"].orEmpty().groupBy { it.field }
        val label =
            placeEdits[HistoryField.LABEL]
                .orEmpty()
                .map { it.value }
                .distinct()
                .singleOrNull()
        val radius =
            placeEdits[HistoryField.RADIUS].orEmpty().map { it.value }.distinct().singleOrNull()?.toDoubleOrNull()?.coerceIn(
                30.0,
                500.0,
            )
        val rawCategory =
            placeEdits[HistoryField.CATEGORY]
                .orEmpty()
                .map { it.value }
                .distinct()
                .singleOrNull()
        val category = rawCategory?.takeIf { it.isNotBlank() }
        place.copy(
            name = label ?: place.name,
            userConfirmed = place.userConfirmed || label != null,
            radiusMeters = radius ?: place.radiusMeters,
            category = if (rawCategory != null) category else place.category,
        )
    }
}
