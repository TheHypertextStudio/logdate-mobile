package app.logdate.client.location.places

import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.logdate.shared.model.Location
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Converts latitude/longitude coordinates into street addresses using the Android [Geocoder].
 *
 * Implements [ReverseGeocodingProvider]. On API 33+ (Android 13) the non-blocking callback API
 * is used; on older versions the call runs on [ioDispatcher] because the legacy API blocks.
 */
class AndroidReverseGeocodingProvider(
    private val geocoder: Geocoder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReverseGeocodingProvider {
    companion object {
        /** Number of addresses to request from the geocoder (only the best match is needed). */
        private const val MAX_GEOCODER_RESULTS = 1
    }

    override suspend fun reverseGeocode(location: Location): GeocodedAddress? =
        try {
            val address =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: async API
                    suspendCancellableCoroutine<Address?> { continuation ->
                        geocoder.getFromLocation(
                            location.latitude,
                            location.longitude,
                            MAX_GEOCODER_RESULTS,
                        ) { addresses ->
                            continuation.resume(addresses.firstOrNull())
                        }
                    }
                } else {
                    // API < 33: blocking API
                    @Suppress("DEPRECATION")
                    withContext(ioDispatcher) {
                        geocoder
                            .getFromLocation(
                                location.latitude,
                                location.longitude,
                                MAX_GEOCODER_RESULTS,
                            )?.firstOrNull()
                    }
                }

            address?.let {
                val streetNumber =
                    it.subThoroughfare?.trim()?.takeIf { value -> value.any(Char::isDigit) }
                        ?: it.featureName?.trim()?.takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
                GeocodedAddress(
                    thoroughfare = it.thoroughfare,
                    subLocality = it.subLocality,
                    locality = it.locality,
                    adminArea = it.adminArea,
                    subThoroughfare = streetNumber,
                    countryCode = it.countryCode,
                )
            }
        } catch (e: Exception) {
            Napier.w(
                "Reverse geocoding failed for ${location.latitude}, ${location.longitude}",
                e,
            )
            null
        }
}
