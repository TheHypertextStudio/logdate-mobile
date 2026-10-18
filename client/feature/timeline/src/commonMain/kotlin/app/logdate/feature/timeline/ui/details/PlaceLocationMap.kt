@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.timeline.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A map of one place, marked at its coordinates. Uses the native map where it is available and the
 * drawn preview where it is not.
 */
@Composable
fun PlaceLocationMap(
    name: String,
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
) {
    TimelineLocationsMap(
        locations = listOf(DayLocation(locationId = "place", name = name, latitude = latitude, longitude = longitude)),
        modifier = modifier,
    )
}
