package app.logdate.client.domain.location.history

import app.logdate.client.device.crypto.activityHistoryRecordId
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationObservation
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class BoundaryEvidence(
    val observations: List<LocationObservation>,
    val activities: List<ActivityObservation>,
    val activityFailed: Boolean = false,
)

/**
 * Pages only the uninterrupted component preceding a window, never assuming continuity across missing evidence.
 * A pathological uninterrupted component can still be large; truncating it would discard correction anchors.
 *
 * Paging stops where [ReconstructLocationDay] could not join older samples to the window: at a
 * recording gap, or once precise samples show the person was somewhere else for longer than drift.
 */
internal class HistoryBoundaryReader(
    private val raw: LocationHistoryRepository,
    private val store: HistoryRecordStore,
    private val activity: ActivityHistoryRepository?,
    private val decode: (HistoryRecord) -> HistoryPayload?,
    private val parameters: HistoryReconstructionParameters = HistoryReconstructionParameters(),
) {
    suspend fun read(
        owner: String,
        origin: String,
        localDevice: String,
        observations: List<LocationObservation>,
        activities: List<ActivityObservation>,
        deletedSamples: Set<String>,
        deletedActivities: Set<String>,
    ): BoundaryEvidence {
        val prefix = mutableListOf<LocationObservation>()
        val allActivities = activities.toMutableList()
        var activityFailed = false
        for ((device, points) in observations.groupBy { it.deviceId }) {
            val validPoints = points.filter { it.validCoordinates() }
            if (validPoints.isEmpty()) continue
            val earliest = validPoints.minBy { it.timestamp }
            if (!tryAppendActivities(allActivities) {
                    readPrecedingActivityCarryIn(owner, origin, localDevice, device, earliest.timestamp + 1.milliseconds, deletedActivities)
                }
            ) {
                activityFailed = true
            }
            val start = applyActivityEvidence(validPoints, allActivities).minWith(compareBy({ it.timestamp }, { it.id }))
            if (readDevicePrefix(owner, origin, localDevice, device, start, deletedSamples, deletedActivities, prefix, allActivities)) {
                activityFailed = true
            }
        }
        return BoundaryEvidence(observations + prefix, allActivities.distinctBy { Triple(it.ownerId, it.deviceId, it.id) }, activityFailed)
    }

    private suspend fun readDevicePrefix(
        owner: String,
        origin: String,
        localDevice: String,
        device: String,
        initialNext: LocationObservation,
        deletedSamples: Set<String>,
        deletedActivities: Set<String>,
        prefix: MutableList<LocationObservation>,
        allActivities: MutableList<ActivityObservation>,
    ): Boolean {
        var activityFailed = false
        var next = initialNext
        val continuity = ContinuityTracker(parameters, next)
        var cursorTime = next.timestamp
        var cursorId = next.id
        while (true) {
            val page = observationPage(owner, origin, localDevice, device, cursorTime, cursorId)
            if (page.isEmpty()) break
            val valid = page.filter { it.id !in deletedSamples && it.validCoordinates() }
            if (valid.isNotEmpty()) {
                val from = valid.last().timestamp - 10.minutes
                val until = next.timestamp + 1.milliseconds
                if (!tryAppendActivities(allActivities) { readActivities(owner, origin, device, from, until, deletedActivities) }) {
                    activityFailed = true
                }
                val enriched = applyActivityEvidence(valid + next, allActivities)
                next = enriched.last()
                val stopped =
                    enriched.dropLast(1).any { previous ->
                        if (continuity.separates(previous, next)) return@any true
                        prefix += previous
                        next = previous
                        false
                    }
                if (stopped) break
            }
            val oldest = page.last()
            cursorTime = oldest.timestamp
            cursorId = oldest.id
            if (page.size < PAGE_SIZE) break
        }
        return activityFailed
    }

    private suspend inline fun tryAppendActivities(
        target: MutableList<ActivityObservation>,
        load: () -> List<ActivityObservation>,
    ): Boolean =
        try {
            target += load()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Napier.w("Boundary activity evidence is unavailable", error)
            false
        }

    private suspend fun observationPage(
        owner: String,
        origin: String,
        localDevice: String,
        device: String,
        cursorTime: Instant,
        cursorId: String,
    ): List<LocationObservation> {
        val local =
            raw.getLocationHistoryBefore(owner, device, cursorTime, cursorId, PAGE_SIZE) +
                if (device ==
                    localDevice
                ) {
                    raw.getLocationHistoryBefore("default_user", device, cursorTime, cursorId, PAGE_SIZE)
                } else {
                    emptyList()
                }
        val remote =
            store
                .observationsBefore(owner, origin, device, cursorTime.toEpochMilliseconds(), cursorId, PAGE_SIZE)
                .mapNotNull { (decode(it) as? HistoryPayload.Observation)?.value }
        return (local.map { it.toObservation(owner) } + remote)
            .filter { it.ownerId == owner && it.deviceId == device }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<LocationObservation> { it.timestamp }.thenByDescending { it.id })
            .take(PAGE_SIZE)
    }

    private suspend fun readPrecedingActivityCarryIn(
        owner: String,
        origin: String,
        localDevice: String,
        device: String,
        before: Instant,
        deletedActivities: Set<String>,
    ): List<ActivityObservation> {
        val localLatest =
            listOfNotNull(
                activity?.latestBefore(owner, device, before),
                if (device == localDevice) activity?.latestBefore("default_user", device, before) else null,
            ).maxByOrNull { it.timestamp }?.let {
                ActivityObservation(
                    it.id,
                    owner,
                    it.deviceId,
                    it.timestamp,
                    it.activityType,
                    it.transitionType,
                    it.timeZoneId,
                )
            }
        val remoteLatest =
            store
                .latestActivityBefore(owner, origin, device, before.toEpochMilliseconds())
                ?.let { (decode(it) as? HistoryPayload.Activity)?.value }
        return listOfNotNull(localLatest, remoteLatest)
            .filter { it.ownerId == owner && it.deviceId == device && activityHistoryRecordId(it.id) !in deletedActivities }
            .distinctBy { Triple(it.ownerId, it.deviceId, it.id) }
            .sortedBy { it.timestamp }
            .takeLast(1)
    }

    private suspend fun readActivities(
        owner: String,
        origin: String,
        device: String,
        from: Instant,
        until: Instant,
        deletedActivities: Set<String>,
    ): List<ActivityObservation> {
        val localEvents =
            activity?.observeActivityHistoryBetween(from, until)?.first().orEmpty().map {
                ActivityObservation(
                    it.id,
                    it.userId,
                    it.deviceId,
                    it.timestamp,
                    it.activityType,
                    it.transitionType,
                    it.timeZoneId,
                )
            }
        val remoteEvents =
            store
                .activitiesBetween(owner, origin, device, from.toEpochMilliseconds(), until.toEpochMilliseconds())
                .mapNotNull { (decode(it) as? HistoryPayload.Activity)?.value }
        return (localEvents + remoteEvents).filter {
            it.ownerId == owner && it.deviceId == device && activityHistoryRecordId(it.id) !in deletedActivities
        }
    }

    private fun LocationObservation.validCoordinates() = hasValidCoordinates()

    private companion object {
        const val PAGE_SIZE = 256
    }
}

/**
 * Walks backwards from a window's first sample and reports where the reconstruction can no longer
 * join older samples to it: a recording gap, or a departure (precise fixes outside the
 * neighbourhood for longer than the excursion tolerance). Single stray fixes never end a stay.
 */
internal class ContinuityTracker(
    private val parameters: HistoryReconstructionParameters,
    first: LocationObservation,
) {
    private val center = CenterAccumulator()
    private var hasCenter = false
    private var awaySince: Instant? = null
    private var rated = 0
    private var unrated = 0

    init {
        count(first)
        include(first)
    }

    fun separates(
        previous: LocationObservation,
        next: LocationObservation,
    ): Boolean {
        if (parameters.isRecordingGap(previous, next)) return true
        count(previous)
        if (!previous.isPrecise()) return false
        if (hasCenter && center.value.distanceTo(previous) > parameters.neighborhoodMeters) {
            val since = awaySince ?: previous.timestamp.also { awaySince = it }
            return since - previous.timestamp > parameters.excursionTolerance
        }
        awaySince = null
        include(previous)
        return false
    }

    private fun count(sample: LocationObservation) {
        if (sample.accuracyMeters == null) unrated++ else rated++
    }

    private fun include(sample: LocationObservation) {
        if (!sample.isPrecise()) return
        center.add(sample)
        hasCenter = true
    }

    /** Unrated fixes count while they are most of what was recorded, as in [ReconstructLocationDay]. */
    private fun LocationObservation.isPrecise() = parameters.isPrecise(this, acceptUnrated = unrated >= rated)
}
