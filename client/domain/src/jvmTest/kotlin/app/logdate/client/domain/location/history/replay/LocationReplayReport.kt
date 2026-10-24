package app.logdate.client.domain.location.history.replay

import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A named way of turning samples into a day, compared side by side in the replay. */
internal data class ReplayAlgorithm(
    val name: String,
    val reconstruct: (List<LocationObservation>) -> List<LocationDayItem>,
)

internal data class ReplayDay(
    val date: LocalDate,
    val samples: List<LocationObservation>,
    val itemsByAlgorithm: Map<String, List<LocationDayItem>>,
)

/**
 * Rebuilds every recorded day the way the history screen does (24 hours of context on each side)
 * and keeps only the items the day would show.
 */
internal fun replayDays(
    samples: List<LocationObservation>,
    zone: TimeZone,
    algorithms: List<ReplayAlgorithm>,
): List<ReplayDay> {
    val sorted = samples.sortedBy { it.timestamp }
    return sorted
        .map { it.timestamp.toLocalDateTime(zone).date }
        .distinct()
        .map { date ->
            val start = date.atStartOfDayIn(zone)
            val end = date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
            val context = sorted.filter { it.timestamp >= start - 24.hours && it.timestamp < end + 24.hours }
            ReplayDay(
                date = date,
                samples = sorted.filter { it.timestamp >= start && it.timestamp < end },
                itemsByAlgorithm =
                    algorithms.associate { algorithm ->
                        algorithm.name to algorithm.reconstruct(context).filter { it.overlaps(start, end) }
                    },
            )
        }
}

internal fun formatReplay(days: List<ReplayDay>): String =
    buildString {
        days.forEach { day ->
            appendLine("${day.date}  ${sampleSummary(day.samples)}")
            day.itemsByAlgorithm.forEach { (name, items) -> appendLine("  ${name.padEnd(18)} ${itemSummary(items)}") }
        }
        appendLine()
        appendLine("All days")
        appendLine("  ${sampleSummary(days.flatMap { it.samples })}")
        days
            .first()
            .itemsByAlgorithm.keys
            .forEach { name ->
                appendLine("  ${name.padEnd(18)} ${itemSummary(days.flatMap { it.itemsByAlgorithm.getValue(name) })}")
            }
    }

private fun sampleSummary(samples: List<LocationObservation>): String {
    val sources =
        samples
            .groupingBy { it.source }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ${it.value}" }
    val accuracy =
        samples
            .groupingBy { accuracyBand(it.accuracyMeters) }
            .eachCount()
            .toSortedMap(compareBy { ACCURACY_BANDS.indexOf(it) })
            .entries
            .joinToString(" ") { "${it.key}:${it.value}" }
    val fast = samples.count { (it.speedMetersPerSecond ?: 0f) > 1f }
    val intervals = samples.zipWithNext { a, b -> b.timestamp - a.timestamp }
    val longGaps = intervals.count { it > 10.minutes }
    return "samples ${samples.size} [$sources] accuracy[$accuracy] speed>1m/s ${percent(fast, samples.size)} " +
        "median interval ${intervals.median()?.let(::formatDuration) ?: "-"} gaps>10m $longGaps"
}

private fun itemSummary(items: List<LocationDayItem>): String {
    val visits = items.filterIsInstance<PlaceVisit>()
    val confirmed = visits.filter { it.confirmedStay }
    val journeys = items.filterIsInstance<JourneyLeg>()
    val connectors = journeys.count { it.route.isEmpty() }
    val gaps = items.count { it is HistoryGap }
    val stayLengths = confirmed.map { it.end - it.start }
    return "visits ${visits.size} (confirmed ${confirmed.size}, approximate ${visits.size - confirmed.size}) " +
        "journeys ${journeys.size} (connectors $connectors) gaps $gaps " +
        "median stay ${stayLengths.median()?.let(::formatDuration) ?: "-"}"
}

private val ACCURACY_BANDS = listOf("none", "<=20m", "<=50m", "<=100m", "<=200m", ">200m")

private fun accuracyBand(accuracy: Float?): String =
    when {
        accuracy == null -> "none"
        accuracy <= 20f -> "<=20m"
        accuracy <= 50f -> "<=50m"
        accuracy <= 100f -> "<=100m"
        accuracy <= 200f -> "<=200m"
        else -> ">200m"
    }

private fun percent(
    part: Int,
    whole: Int,
): String = if (whole == 0) "-" else "${part * 100 / whole}%"

private fun List<Duration>.median(): Duration? = sorted().getOrNull(size / 2)

private fun formatDuration(duration: Duration): String =
    duration.toComponents { hours, minutes, seconds, _ ->
        when {
            hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

private fun LocationDayItem.overlaps(
    start: Instant,
    end: Instant,
): Boolean = this.start < end && (this.end > start || (this.start == this.end && this.start >= start))
