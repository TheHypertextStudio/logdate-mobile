package app.logdate.client.location.tracking

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Defines GPS accuracy and update frequency parameters for a specific movement state.
 *
 * Each profile is tuned for a different activity type (still, walking, cycling, driving, low-power)
 * to balance location accuracy against battery consumption while allowing indoor Wi-Fi continuity
 * fixes through `setWaitForAccurateLocation(false)`.
 */
data class LocationProfile(
    val priority: Int,
    val intervalMillis: Long,
    val minIntervalMillis: Long,
    val displacementMeters: Float,
    val maxDelayMillis: Long,
) {
    fun toLocationRequest(): LocationRequest =
        LocationRequest
            .Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(minIntervalMillis)
            .setMinUpdateDistanceMeters(displacementMeters)
            .setMaxUpdateDelayMillis(maxDelayMillis)
            .setWaitForAccurateLocation(false)
            .build()

    companion object {
        /** Low-power heartbeat while the user is stationary. */
        val STILL =
            LocationProfile(
                priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                intervalMillis = 180_000L,
                minIntervalMillis = 180_000L,
                displacementMeters = 0f,
                maxDelayMillis = 180_000L,
            )

        /** High accuracy for walking or running — captures each city block. */
        val ON_FOOT =
            LocationProfile(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMillis = 20_000L,
                minIntervalMillis = 10_000L,
                displacementMeters = 15f,
                maxDelayMillis = 45_000L,
            )

        /** High accuracy tailored for bicycle speeds and corner fidelity. */
        val CYCLING =
            LocationProfile(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMillis = 15_000L,
                minIntervalMillis = 8_000L,
                displacementMeters = 25f,
                maxDelayMillis = 30_000L,
            )

        /** High accuracy for vehicle travel — rapid interval with larger displacement filter. */
        val IN_VEHICLE =
            LocationProfile(
                priority = Priority.PRIORITY_HIGH_ACCURACY,
                intervalMillis = 8_000L,
                minIntervalMillis = 4_000L,
                displacementMeters = 40f,
                maxDelayMillis = 20_000L,
            )
    }
}
