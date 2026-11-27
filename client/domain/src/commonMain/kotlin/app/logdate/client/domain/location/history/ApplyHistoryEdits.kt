package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlin.time.Instant

class ApplyHistoryEdits {
    operator fun invoke(
        items: List<LocationDayItem>,
        edits: List<HistoryCorrection>,
        places: List<SemanticPlace>,
    ): List<LocationDayItem> {
        val superseded = edits.flatMap { it.supersedes }.toSet()
        val activeEdits = edits.filterNot { it.id in superseded }
        val deletedConnectors = deletedConnectorEnds(activeEdits)
        val afterBase =
            items.mapIndexedNotNull { index, item ->
                if (item.isDeletedConnector(
                        items.getOrNull(index - 1),
                        items.getOrNull(index + 1),
                        deletedConnectors,
                    )
                ) {
                    return@mapIndexedNotNull null
                }
                applyItemEdits(item, activeEdits, places)
            }
        val afterSplits =
            applySplitEdits(afterBase, activeEdits)
                .mapNotNull { item ->
                    if (item.evidenceIds.any { it.startsWith(SPLIT_PREFIX) }) {
                        applyItemEdits(item, activeEdits, places)
                    } else {
                        item
                    }
                }
        return applyMergeEdits(afterSplits, activeEdits)
    }

    private fun applyItemEdits(
        item: LocationDayItem,
        activeEdits: List<HistoryCorrection>,
        places: List<SemanticPlace>,
    ): LocationDayItem? {
        val matching =
            activeEdits.filter {
                // Arrival and departure are edited through an item's first sample. Once later
                // recordings join that item to others, an edit aimed at an inner sample described a
                // smaller visit and would cut the joined one short.
                it.targetEvidenceId in item.evidenceIds &&
                    (it.field !in TIME_FIELDS || it.targetEvidenceId == item.evidenceIds.first())
            }
        if (matching.any { it.field == HistoryField.DELETE }) return null
        val fields = matching.groupBy { it.field }
        val unique = fields.mapValues { (_, values) -> values.distinctBy { it.value }.singleOrNull()?.value }
        val start = unique[HistoryField.START]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: item.start
        val end = unique[HistoryField.END]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: item.end
        val validStart = if (start <= end) start else item.start
        val validEnd = if (start <= end) end else item.end
        val explicitPlace = unique[HistoryField.PLACE]?.let { id -> places.firstOrNull { it.id == id } }
        val suggestedPlace = unique[HistoryField.SUGGESTED_PLACE]?.let { id -> places.firstOrNull { it.id == id } }
        return when (item) {
            is PlaceVisit ->
                item.copy(
                    start = validStart,
                    end = validEnd,
                    place = explicitPlace ?: item.place ?: suggestedPlace,
                )
            is JourneyLeg ->
                item.copy(
                    start = validStart,
                    end = validEnd,
                    mode =
                        unique[HistoryField.ACTIVITY]?.let { name -> TravelMode.entries.firstOrNull { it.name == name } } ?: item.mode,
                    userConfirmed = unique[HistoryField.ACTIVITY] != null,
                )
            else -> item
        }
    }

    private fun applySplitEdits(
        items: List<LocationDayItem>,
        activeEdits: List<HistoryCorrection>,
    ): List<LocationDayItem> {
        val splitEdits = activeEdits.filter { it.field == HistoryField.SPLIT_AT }
        if (splitEdits.isEmpty()) return items
        return items.flatMap { item ->
            if (item !is PlaceVisit) return@flatMap listOf(item)
            val splitAt =
                splitEdits
                    .filter { it.targetEvidenceId == item.evidenceIds.firstOrNull() || it.targetEvidenceId in item.evidenceIds }
                    .mapNotNull { runCatching { Instant.parse(it.value) }.getOrNull() }
                    .distinct()
                    .singleOrNull()
            if (splitAt == null || splitAt <= item.start || splitAt >= item.end) {
                listOf(item)
            } else {
                val primaryAnchor = item.evidenceIds.firstOrNull() ?: item.id
                listOf(
                    item.copy(
                        id = "${item.id}:part1",
                        end = splitAt,
                    ),
                    item.copy(
                        id = "${item.id}:part2",
                        start = splitAt,
                        evidenceIds = listOf("$SPLIT_PREFIX$primaryAnchor:$splitAt"),
                        memoryIds = emptyList(),
                    ),
                )
            }
        }
    }

    private fun applyMergeEdits(
        items: List<LocationDayItem>,
        activeEdits: List<HistoryCorrection>,
    ): List<LocationDayItem> {
        val mergeEdits = activeEdits.filter { it.field == HistoryField.MERGE_WITH }
        if (mergeEdits.isEmpty()) return items
        var current = items.toMutableList()
        for (merge in mergeEdits) {
            val firstIdx = current.indexOfFirst { it is PlaceVisit && merge.targetEvidenceId in it.evidenceIds }
            val secondIdx = current.indexOfFirst { it is PlaceVisit && merge.value in it.evidenceIds }
            if (firstIdx < 0 || secondIdx < 0 || firstIdx == secondIdx) continue
            val lowIdx = minOf(firstIdx, secondIdx)
            val highIdx = maxOf(firstIdx, secondIdx)
            val first = current[lowIdx] as PlaceVisit
            val second = current[highIdx] as PlaceVisit
            val preferredPlace =
                when {
                    first.place?.userConfirmed == true -> first.place
                    second.place?.userConfirmed == true -> second.place
                    else -> first.place ?: second.place
                }
            val merged =
                first.copy(
                    start = minOf(first.start, second.start),
                    end = maxOf(first.end, second.end),
                    evidenceIds = (first.evidenceIds + second.evidenceIds).distinct(),
                    confirmedStay = first.confirmedStay || second.confirmedStay,
                    place = preferredPlace,
                    memoryIds = (first.memoryIds + second.memoryIds).distinct(),
                    sampleCount = first.sampleCount + second.sampleCount,
                    uncertaintyRadiusMeters = minOf(first.uncertaintyRadiusMeters, second.uncertaintyRadiusMeters),
                    confidence = maxOf(first.confidence, second.confidence),
                )
            val next = mutableListOf<LocationDayItem>()
            current.forEachIndexed { index, item ->
                when {
                    index == lowIdx -> next += merged
                    index == highIdx -> Unit
                    index in (lowIdx + 1) until highIdx && item is JourneyLeg -> Unit
                    else -> next += item
                }
            }
            current = next
        }
        return current
    }

    /**
     * A deleted connector's id names the samples on either side of it. Reconstruction can pick
     * different edge samples later, so a new connector between visits holding those same samples
     * stays deleted.
     */
    private fun LocationDayItem.isDeletedConnector(
        previous: LocationDayItem?,
        next: LocationDayItem?,
        deleted: List<Pair<String, String>>,
    ): Boolean {
        if (this !is JourneyLeg || route.isNotEmpty() || evidenceIds.none { it.startsWith(CONNECTOR_PREFIX) }) return false
        if (previous == null || next == null) return false
        return deleted.any { (before, after) -> before in previous.evidenceIds && after in next.evidenceIds }
    }

    private fun deletedConnectorEnds(edits: List<HistoryCorrection>): List<Pair<String, String>> =
        edits
            .filter { it.field == HistoryField.DELETE && it.targetEvidenceId.startsWith(CONNECTOR_PREFIX) }
            .mapNotNull { edit ->
                val parts = edit.targetEvidenceId.split(':')
                if (parts.size < 2) null else parts[parts.size - 2] to parts.last()
            }

    private companion object {
        val TIME_FIELDS = setOf(HistoryField.START, HistoryField.END)
        const val CONNECTOR_PREFIX = "derived:journey:"
        const val SPLIT_PREFIX = "derived:split:"
    }
}
