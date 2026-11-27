package app.logdate.client.location.places

import app.logdate.shared.model.Location

/**
 * Converts geographic coordinates into human-readable addresses.
 */
interface ReverseGeocodingProvider {
    /**
     * Returns a [GeocodedAddress] for the given [location], or `null` if geocoding is
     * unavailable or the lookup fails.
     */
    suspend fun reverseGeocode(location: Location): GeocodedAddress?
}

/**
 * A human-readable address resolved from geographic coordinates.
 *
 * @property thoroughfare Street name (e.g. "Main St").
 * @property subLocality Neighborhood or district.
 * @property locality City or town.
 * @property adminArea State or province.
 */
data class GeocodedAddress(
    val thoroughfare: String?,
    val subLocality: String?,
    val locality: String?,
    val adminArea: String?,
    val subThoroughfare: String? = null,
    val countryCode: String? = null,
) {
    fun formatFallbackLabel(): String? {
        val number = subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }
        val street = thoroughfare?.trim()?.takeIf { it.isNotEmpty() }
        val neighborhood = subLocality?.trim()?.takeIf { it.isNotEmpty() }
        val city = locality?.trim()?.takeIf { it.isNotEmpty() }
        val state = adminArea?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            number != null && street != null -> "$number $street"
            street != null -> street
            neighborhood != null && city != null -> "$neighborhood, $city"
            neighborhood != null -> neighborhood
            city != null -> city
            state != null -> state
            else -> null
        }
    }
}

/**
 * No-op [ReverseGeocodingProvider] for platforms that do not support geocoding.
 *
 * @see AndroidReverseGeocodingProvider
 */
class UnavailableReverseGeocodingProvider : ReverseGeocodingProvider {
    override suspend fun reverseGeocode(location: Location): GeocodedAddress? = null
}
