package app.logdate.client.domain.location

import app.logdate.client.domain.location.history.ReconstructLocationDay
import app.logdate.client.domain.location.history.toObservation
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.location.PlaceVisit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Turns location history into the places someone stopped at, newest first.
 *
 * Stops come from the same reconstruction as the location history screen, so a drive or a noisy
 * fix never shows up as a stop here while the history shows a journey.
 */
class ObserveLocationStopsUseCase(
    private val observeLocationHistoryUseCase: ObserveLocationHistoryUseCase,
    private val reconstruct: ReconstructLocationDay = ReconstructLocationDay(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun currentDevice(deviceId: String): Flow<LocationStopSnapshot> =
        observeLocationHistoryUseCase()
            .conflate()
            .map { history ->
                val local =
                    history.filter {
                        deviceId.isNotBlank() &&
                            it.deviceId == deviceId &&
                            it.countsTowardActivityStops() &&
                            it.captureSource != LocationCaptureSource.MANUAL
                    }
                LocationStopSnapshot(aggregateStops(local), local.maxByOrNull { it.timestamp })
            }.flowOn(dispatcher)

    /**
     * Reconstruction is heavy over long history, so it never runs on the collector's (often main)
     * thread, and fixes that arrive while it runs are folded into one more pass.
     */
    operator fun invoke(): Flow<List<LocationStop>> = observeLocationHistoryUseCase().conflate().map(::aggregateStops).flowOn(dispatcher)

    /**
     * Stops that end at or after [since], reconstructed from only the recent history. Samples from
     * a day before [since] are read too, so a stay that began earlier keeps its real start time
     * instead of appearing to begin at the cutoff.
     */
    fun since(since: Instant): Flow<List<LocationStop>> =
        observeLocationHistoryUseCase
            .between(since - RECONSTRUCTION_LEAD_IN, Instant.DISTANT_FUTURE)
            .conflate()
            .map { history -> aggregateStops(history).filter { it.endTime >= since } }
            .flowOn(dispatcher)

    internal fun aggregateStops(history: List<LocationHistoryItem>): List<LocationStop> {
        val samples = history.filter { item -> item.countsTowardActivityStops() }
        val samplesById = samples.associateBy { it.sampleId }
        val owners = ownersByDevice(samples)
        return reconstruct(samples.map { it.toObservation(if (it.userId == LEGACY_OWNER) owners[it.deviceId] ?: it.userId else it.userId) })
            .filterIsInstance<PlaceVisit>()
            .map { visit -> toStop(visit, visit.evidenceIds.mapNotNull(samplesById::get).sortedBy { it.timestamp }) }
            .sortedByDescending { it.endTime }
    }

    private fun toStop(
        visit: PlaceVisit,
        group: List<LocationHistoryItem>,
    ): LocationStop {
        val first = group.first()
        // Deleting a stop removes its time range, so the range covers every sample behind it.
        val startTime = minOf(visit.start, first.timestamp)
        val endTime = maxOf(visit.end, group.last().timestamp)
        val maxInternalGap =
            group
                .zipWithNext { previous, current -> current.timestamp - previous.timestamp }
                .maxOrNull() ?: Duration.ZERO
        return LocationStop(
            id = "${first.userId}:${first.deviceId}:${startTime.toEpochMilliseconds()}:${endTime.toEpochMilliseconds()}",
            location =
                Location(
                    latitude = visit.latitude,
                    longitude = visit.longitude,
                    altitude = LocationAltitude(group.map { it.location.altitude.value }.average(), AltitudeUnit.METERS),
                ),
            startTime = startTime,
            endTime = endTime,
            sampleCount = group.size,
            maxInternalGap = maxInternalGap,
            hasReliableDuration = visit.confirmedStay,
            evidenceKind = if (visit.confirmedStay) LocationStopEvidenceKind.STAY else LocationStopEvidenceKind.OBSERVATION,
            primaryPipeline = first.capturePipeline,
        )
    }

    /**
     * Samples recorded before the account owner was known carry a placeholder owner. They belong to
     * the same person as the device's other samples, so they are reconstructed together.
     */
    private fun ownersByDevice(samples: List<LocationHistoryItem>): Map<String, String> =
        samples
            .filter { it.userId != LEGACY_OWNER }
            .groupBy { it.deviceId }
            .mapValues { (_, items) -> items.maxBy { it.timestamp }.userId }

    private fun LocationHistoryItem.countsTowardActivityStops(): Boolean =
        captureSource != LocationCaptureSource.TIMELINE_REVIEW &&
            captureSource != LocationCaptureSource.JOURNAL_ENTRY

    private companion object {
        const val LEGACY_OWNER = "default_user"
        val RECONSTRUCTION_LEAD_IN = 1.days
    }
}

/** Stops and latest fix read from the same device-scoped history snapshot. */
data class LocationStopSnapshot(
    val stops: List<LocationStop>,
    val latest: LocationHistoryItem?,
)
