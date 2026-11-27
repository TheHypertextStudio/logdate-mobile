package app.logdate.client.domain.places

import app.logdate.client.location.places.ExternalPlacesProvider
import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.location.places.PlaceSuggestion
import app.logdate.client.location.places.ReverseGeocodingProvider
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.model.Location
import app.logdate.shared.model.Place
import io.github.aakira.napier.Napier
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Domain use case that resolves a raw location into a semantic place.
 *
 * Prioritizes user-defined places over external place suggestions and applies dwell-time and
 * overnight residential guards before falling back to structured reverse geocoding.
 */
class ResolveLocationToPlaceUseCase(
    private val userPlacesRepository: UserPlacesRepository,
    private val externalPlacesProvider: ExternalPlacesProvider,
    private val reverseGeocodingProvider: ReverseGeocodingProvider,
    // Matching radius in meters
    private val placeMatchingRadius: Double = 100.0,
) {
    suspend operator fun invoke(location: Location): PlaceResolutionResult =
        invoke(location, searchRadiusMeters = placeMatchingRadius, dwellDuration = null, isOvernightResidential = false)

    suspend operator fun invoke(
        location: Location,
        searchRadiusMeters: Double,
        dwellDuration: Duration? = null,
        isOvernightResidential: Boolean = false,
    ): PlaceResolutionResult {
        val lookupRadius = maxOf(placeMatchingRadius, searchRadiusMeters)
        // First, check user-defined places honoring each place's custom radius.
        val userPlace =
            runCatching {
                findMatchingUserPlace(location, lookupRadius)
            }.onFailure { error ->
                Napier.w("Failed to resolve nearby user place", error)
            }.getOrNull()
        if (userPlace != null) {
            return PlaceResolutionResult.UserDefinedPlace(userPlace)
        }

        // Fallback to external places with uncertainty-scaled radius.
        val externalSuggestions =
            try {
                externalPlacesProvider.searchNearbyPlaces(location, searchRadiusMeters)
            } catch (e: Exception) {
                Napier.w("Failed to resolve nearby external place", e)
                emptyList()
            }

        val bestSuggestion = selectBestExternalSuggestion(externalSuggestions, location, dwellDuration, isOvernightResidential)
        if (bestSuggestion != null) {
            return PlaceResolutionResult.ExternalSuggestion(bestSuggestion)
        }

        // Fallback to reverse geocoding for coarse location.
        val geocoded =
            runCatching { reverseGeocodingProvider.reverseGeocode(location) }
                .onFailure { error ->
                    Napier.w("Failed to reverse geocode location", error)
                }.getOrNull()
        return if (geocoded != null) {
            PlaceResolutionResult.CoarseLocation(location, geocoded)
        } else {
            PlaceResolutionResult.UnknownLocation(location)
        }
    }

    private fun selectBestExternalSuggestion(
        suggestions: List<PlaceSuggestion>,
        origin: Location,
        dwellDuration: Duration?,
        isOvernightResidential: Boolean,
    ): PlaceSuggestion? {
        if (suggestions.isEmpty()) return null
        val isExtendedStay = dwellDuration != null && dwellDuration > UTILITY_POI_MAX_DWELL
        val suppressCommercialOvernight = isOvernightResidential && dwellDuration != null && dwellDuration >= OVERNIGHT_MIN_DWELL

        val nonUtility =
            if (isExtendedStay) {
                suggestions.filterNot { it.isTransientUtilityPoi() }
            } else {
                suggestions
            }
        val pool =
            if (nonUtility.isNotEmpty()) {
                nonUtility
            } else if (isExtendedStay) {
                emptyList()
            } else {
                suggestions
            }
        if (suppressCommercialOvernight) {
            return pool.firstOrNull { suggestion ->
                calculateDistance(origin.latitude, origin.longitude, suggestion.latitude, suggestion.longitude) <=
                    OVERNIGHT_EXACT_MATCH_METERS
            }
        }
        return pool.firstOrNull()
    }

    private fun PlaceSuggestion.isTransientUtilityPoi(): Boolean {
        val typeSet = (types + listOfNotNull(category)).map { it.lowercase() }
        return typeSet.any { token -> TRANSIENT_UTILITY_TYPES.any { utility -> token.contains(utility) } }
    }

    private suspend fun findMatchingUserPlace(
        location: Location,
        searchRadiusMeters: Double,
    ): Place? {
        val queryRadius = maxOf(searchRadiusMeters, MAX_USER_PLACE_QUERY_METERS)
        val nearbyUserPlaces =
            userPlacesRepository.getPlacesNear(
                latitude = location.latitude,
                longitude = location.longitude,
                radiusMeters = queryRadius,
            )

        return nearbyUserPlaces
            .filter { place ->
                val allowedRadius = maxOf(placeMatchingRadius, (place as? Place.UserDefined)?.radiusMeters ?: placeMatchingRadius)
                calculateDistance(location.latitude, location.longitude, place.latitude, place.longitude) <= allowedRadius
            }.minByOrNull { userPlace ->
                calculateDistance(
                    location.latitude,
                    location.longitude,
                    userPlace.latitude,
                    userPlace.longitude,
                )
            }
    }

    private companion object {
        val UTILITY_POI_MAX_DWELL = 45.minutes
        val OVERNIGHT_MIN_DWELL = 5.hours
        const val OVERNIGHT_EXACT_MATCH_METERS = 25.0
        const val MAX_USER_PLACE_QUERY_METERS = 500.0
        val TRANSIENT_UTILITY_TYPES =
            setOf(
                "atm",
                "parking",
                "mailbox",
                "bus_stop",
                "electric_vehicle_charging_station",
            )
    }

    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val earthRadius = 6371000.0 // Earth radius in meters
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180) * cos(lat2 * PI / 180) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}

/**
 * Result of place resolution with different types based on data source.
 */
sealed class PlaceResolutionResult {
    data class UserDefinedPlace(
        val place: Place,
    ) : PlaceResolutionResult()

    data class ExternalSuggestion(
        val suggestion: app.logdate.client.location.places.PlaceSuggestion,
    ) : PlaceResolutionResult()

    data class CoarseLocation(
        val location: Location,
        val address: GeocodedAddress,
    ) : PlaceResolutionResult()

    data class UnknownLocation(
        val location: Location,
    ) : PlaceResolutionResult()
}
