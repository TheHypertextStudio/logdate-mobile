package app.logdate.client.location.places

import android.content.Context
import android.content.pm.PackageManager
import app.logdate.shared.model.Location
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import io.github.aakira.napier.Napier
import kotlinx.coroutines.tasks.await

/**
 * Searches for nearby places (restaurants, parks, etc.) using the Google Places SDK.
 *
 * Implements [ExternalPlacesProvider]. The API key is resolved at init from the
 * `com.google.android.geo.API_KEY` manifest metadata. Logs an error and disables lookups if the
 * dedicated Maps/Places key is absent.
 */
class GooglePlacesExternalPlacesProvider(
    private val context: Context,
) : ExternalPlacesProvider {
    companion object {
        /** Default radius of the circular search area around the given location, in meters. */
        private const val DEFAULT_SEARCH_RADIUS_METERS = 100.0
        private const val MIN_SEARCH_RADIUS_METERS = 60.0
        private const val MAX_SEARCH_RADIUS_METERS = 120.0

        /** Maximum number of place results to return per search. */
        private const val MAX_RESULTS = 5

        /** Confidence score assigned to the closest result. */
        private const val BASE_CONFIDENCE = 95

        /** Confidence decrease per position in the results list. */
        private const val CONFIDENCE_STEP = 10

        /** Lowest confidence score any result can receive. */
        private const val MIN_CONFIDENCE = 55
    }

    private val resolvedApiKey: String? = resolveApiKey()
    private val placesClient: PlacesClient? by lazy(::createPlacesClient)

    override suspend fun searchNearbyPlaces(location: Location): List<PlaceSuggestion> =
        searchNearbyPlaces(location, DEFAULT_SEARCH_RADIUS_METERS)

    override suspend fun searchNearbyPlaces(
        location: Location,
        radiusMeters: Double,
    ): List<PlaceSuggestion> {
        val client = placesClient ?: return emptyList()
        val boundedRadius = radiusMeters.coerceIn(MIN_SEARCH_RADIUS_METERS, MAX_SEARCH_RADIUS_METERS)

        return runCatching {
            val request =
                SearchNearbyRequest
                    .builder(
                        CircularBounds.newInstance(
                            LatLng(location.latitude, location.longitude),
                            boundedRadius,
                        ),
                        listOf(
                            Place.Field.DISPLAY_NAME,
                            Place.Field.FORMATTED_ADDRESS,
                            Place.Field.LOCATION,
                            Place.Field.ID,
                            Place.Field.PRIMARY_TYPE,
                            Place.Field.PRIMARY_TYPE_DISPLAY_NAME,
                            Place.Field.TYPES,
                        ),
                    ).setMaxResultCount(MAX_RESULTS)
                    .setRankPreference(SearchNearbyRequest.RankPreference.DISTANCE)
                    .build()

            client
                .searchNearby(request)
                .await()
                .places
                .mapIndexedNotNull { index, place ->
                    val latLng = place.location ?: return@mapIndexedNotNull null
                    PlaceSuggestion(
                        name = place.displayName ?: place.primaryTypeDisplayName ?: place.formattedAddress ?: "Nearby place",
                        address = place.formattedAddress ?: "${latLng.latitude}, ${latLng.longitude}",
                        latitude = latLng.latitude,
                        longitude = latLng.longitude,
                        confidence = (BASE_CONFIDENCE - (index * CONFIDENCE_STEP)).coerceAtLeast(MIN_CONFIDENCE),
                        category = place.primaryTypeDisplayName ?: place.primaryType,
                        externalId = place.id,
                        types = place.placeTypes.orEmpty(),
                    )
                }
        }.onFailure { error ->
            Napier.w("Google Places nearby search failed", error)
        }.getOrDefault(emptyList())
    }

    private fun createPlacesClient(): PlacesClient? {
        val apiKey = resolvedApiKey ?: return null
        return runCatching {
            if (!Places.isInitialized()) {
                Places.initializeWithNewPlacesApiEnabled(context.applicationContext, apiKey)
            }
            Places.createClient(context.applicationContext)
        }.onFailure { error ->
            Napier.e("Failed to initialize Google Places client", error)
        }.getOrNull()
    }

    private fun resolveApiKey(): String? {
        val key = context.readGoogleMapsManifestApiKey()
        if (key == null) {
            Napier.e(
                "No dedicated Google Maps API key found in com.google.android.geo.API_KEY manifest metadata.",
            )
        }
        return key
    }
}

private fun Context.readGoogleMapsManifestApiKey(): String? =
    runCatching {
        val metaData =
            packageManager
                .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData
                ?: return@runCatching null

        // The manifest meta-data can be either a literal string ("AIza…") or a resource reference
        // ("@string/google_maps_key"). Try the string form first because Bundle.getInt blows up
        // with ClassCastException — not 0 — when the value is actually a string, and that exception
        // would be silently swallowed by the surrounding runCatching, defeating any fallback path.
        metaData.getString(GOOGLE_MAPS_MANIFEST_KEY)?.ifBlank { null }?.let { return@runCatching it }

        val resourceId = runCatching { metaData.getInt(GOOGLE_MAPS_MANIFEST_KEY, 0) }.getOrDefault(0)
        if (resourceId != 0) readStringResource(resourceId).ifBlank { null } else null
    }.getOrNull()

private const val GOOGLE_MAPS_MANIFEST_KEY = "com.google.android.geo.API_KEY"

private fun Context.readStringResource(name: String): String {
    val resourceId = resources.getIdentifier(name, "string", packageName)
    return readStringResource(resourceId)
}

private fun Context.readStringResource(resourceId: Int): String =
    if (resourceId == 0) {
        ""
    } else {
        runCatching {
            getString(resourceId)
        }.getOrDefault("")
    }
