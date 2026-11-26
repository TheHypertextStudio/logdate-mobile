package app.logdate.shared.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
sealed class Place(
    val uid: Uuid,
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    /**
     * User-defined place with custom name and location.
     */
    @OptIn(ExperimentalUuidApi::class)
    data class UserDefined(
        val id: Uuid,
        val displayName: String,
        val lat: Double,
        val lng: Double,
        val radiusMeters: Double = 100.0,
        val description: String? = null,
        val category: String? = null,
    ) : Place(id, displayName, lat, lng)
}
