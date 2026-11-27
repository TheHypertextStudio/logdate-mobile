package app.logdate.client.location.places

import app.logdate.shared.model.Location

/**
 * Provider for external place data sources (e.g., Google Places API).
 *
 * This is a fallback data source for place suggestions when user-defined
 * places don't match the current location.
 */
interface ExternalPlacesProvider {
    /**
     * Searches for places near the given coordinates using external APIs.
     *
     * @return List of place suggestions with confidence scores (0-100)
     */
    suspend fun searchNearbyPlaces(location: Location): List<PlaceSuggestion>

    /**
     * Searches for places near [location] bounded by [radiusMeters].
     */
    suspend fun searchNearbyPlaces(
        location: Location,
        radiusMeters: Double,
    ): List<PlaceSuggestion> = searchNearbyPlaces(location)
}

/**
 * A place suggestion from an external provider with metadata.
 */
data class PlaceSuggestion(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val confidence: Int,
    val category: String? = null,
    val externalId: String? = null,
    val types: List<String> = emptyList(),
)
