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
        val deletedConnectors = deletedConnectorEnds(edits)
        return items.mapIndexedNotNull { index, item ->
            if (item.isDeletedConnector(
                    items.getOrNull(index - 1),
                    items.getOrNull(index + 1),
                    deletedConnectors,
                )
            ) {
                return@mapIndexedNotNull null
            }
            val matching =
                edits.filter {
                    // Arrival and departure are edited through an item's first sample. Once later
                    // recordings join that item to others, an edit aimed at an inner sample described a
                    // smaller visit and would cut the joined one short.
                    it.targetEvidenceId in item.evidenceIds &&
                        (it.field !in TIME_FIELDS || it.targetEvidenceId == item.evidenceIds.first())
                }
            if (matching.any { it.field == HistoryField.DELETE }) return@mapIndexedNotNull null
            val superseded = matching.flatMap { it.supersedes }.toSet()
            val fields = matching.filterNot { it.id in superseded }.groupBy { it.field }
            val unique = fields.mapValues { (_, values) -> values.distinctBy { it.value }.singleOrNull()?.value }
            val start = unique[HistoryField.START]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: item.start
            val end = unique[HistoryField.END]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: item.end
            val validStart = if (start <= end) start else item.start
            val validEnd = if (start <= end) end else item.end
            when (item) {
                is PlaceVisit -> {
                    item.copy(
                        start = validStart,
                        end = validEnd,
                        place = places.firstOrNull { it.id == unique[HistoryField.PLACE] } ?: item.place,
                    )
                }

                is JourneyLeg -> {
                    item.copy(
                        start = validStart,
                        end = validEnd,
                        mode =
                            unique[HistoryField.ACTIVITY]?.let { name -> TravelMode.entries.firstOrNull { it.name == name } } ?: item.mode,
                        userConfirmed = unique[HistoryField.ACTIVITY] != null,
                    )
                }

                else -> {
                    item
                }
            }
        }
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
    }
}
