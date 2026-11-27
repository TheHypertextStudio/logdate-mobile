package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Lazily resolves unnamed [PlaceVisit] stays through [PlaceResolutionCache] and writes non-destructive
 * `SUGGESTED_PLACE` corrections so automated geocoding never overwrites user-confirmed place labels.
 */
internal class HistoryVisitPlaceEnricher(
    private val history: LocationHistoryService,
    private val placeResolution: PlaceResolutionCache,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val resolving = mutableSetOf<String>()
    private val resolvedExternalPlaces = mutableMapOf<String, String>()

    fun reset(clearResolving: Boolean = true) {
        job?.cancel()
        if (clearResolving) resolving.clear()
    }

    fun clearResolving() {
        resolving.clear()
    }

    fun enqueue(
        data: LocationHistorySnapshot,
        fallbackZone: TimeZone,
    ) {
        data.places.forEach { place -> place.externalId?.let { resolvedExternalPlaces.getOrPut(it) { place.id } } }
        val unresolved = data.items.filterIsInstance<PlaceVisit>().filter { it.place == null && resolving.add(it.id) }
        if (unresolved.isEmpty()) return
        job = scope.launch { enrichVisits(unresolved, data.places, fallbackZone) }
    }

    private suspend fun enrichVisits(
        unresolved: List<PlaceVisit>,
        places: List<SemanticPlace>,
        fallbackZone: TimeZone,
    ) {
        unresolved.forEach { visit ->
            try {
                val dwell = visit.end - visit.start
                val visitZone = visit.displayZoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: fallbackZone
                // Scale lookup radius by 1.5x uncertainty within 60m..120m so coarse cell/Wi-Fi visits still match nearby POIs.
                val result =
                    placeResolution.resolveWithContext(
                        location = Location(visit.latitude, visit.longitude, LocationAltitude(0.0, AltitudeUnit.METERS)),
                        searchRadiusMeters = (visit.uncertaintyRadiusMeters * 1.5).coerceIn(60.0, 120.0),
                        dwellDuration = dwell,
                        isOvernightResidential = dwell >= 5.hours && overlapsOvernightWindow(visit.start, visit.end, visitZone),
                    )
                if (result is PlaceResolutionResult.UserDefinedPlace && places.any { it.id == result.place.uid.toString() }) {
                    return@forEach
                }
                toResolvedSemanticPlace(result, visit, resolvedExternalPlaces)?.let { place ->
                    history.savePlace(place)
                    if (result !is PlaceResolutionResult.UserDefinedPlace && visit.evidenceIds.isNotEmpty()) {
                        history.correct(visit.evidenceIds.first(), HistoryField.SUGGESTED_PLACE, place.id)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Place name unavailable; keeping the visit", error)
            }
        }
    }
}

internal fun overlapsOvernightWindow(
    start: Instant,
    end: Instant,
    zone: TimeZone,
): Boolean {
    val localStart = start.toLocalDateTime(zone)
    val localEnd = end.toLocalDateTime(zone)
    return localStart.hour in 0..4 ||
        localEnd.hour in 0..4 ||
        (localStart.date != localEnd.date && localEnd.hour >= 0)
}

internal fun toResolvedSemanticPlace(
    result: PlaceResolutionResult,
    visit: PlaceVisit,
    resolvedExternalPlaces: MutableMap<String, String>,
): SemanticPlace? {
    val name =
        when (result) {
            is PlaceResolutionResult.UserDefinedPlace -> result.place.name
            is PlaceResolutionResult.ExternalSuggestion -> result.suggestion.name
            is PlaceResolutionResult.CoarseLocation -> result.address.formatFallbackLabel()
            else -> null
        } ?: return null
    return when (result) {
        is PlaceResolutionResult.UserDefinedPlace -> {
            SemanticPlace(
                result.place.uid.toString(),
                result.place.name,
                result.place.latitude,
                result.place.longitude,
                userConfirmed = true,
            )
        }

        is PlaceResolutionResult.ExternalSuggestion -> {
            SemanticPlace(
                result.suggestion.externalId?.let { resolvedExternalPlaces.getOrPut(it) { Uuid.random().toString() } }
                    ?: "evidence:${visit.evidenceIds.first()}",
                name,
                result.suggestion.latitude,
                result.suggestion.longitude,
                locality = result.suggestion.address,
                externalId = result.suggestion.externalId,
            )
        }

        else -> {
            SemanticPlace("evidence:${visit.evidenceIds.first()}", name, visit.latitude, visit.longitude)
        }
    }
}
