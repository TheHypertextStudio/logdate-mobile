@file:Suppress("ktlint:standard:filename")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.runtime.Immutable
import app.logdate.client.text.UiText
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_places_count
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
internal data class MapCluster(
    val id: String,
    val coordinate: HistoryCoordinate,
    val placeCount: Int,
    val totalVisits: Int,
    val dominantCategory: String?,
    val memberIds: List<String>,
    val memberCoordinates: List<HistoryCoordinate>,
    val label: UiText,
)

internal fun clusterMapMarkers(
    markers: List<HistoryMapMarker>,
    zoom: Float,
): List<MapCluster> {
    if (markers.isEmpty()) return emptyList()
    val radiusMeters =
        when {
            zoom < 8f -> 80_000.0
            zoom < 11f -> 12_000.0
            zoom < 13.5f -> 1_800.0
            else -> return markers.map(::singleMarkerCluster)
        }
    val ordered = markers.sortedByDescending { it.visitCount }
    val assigned = BooleanArray(ordered.size)
    return ordered.indices.mapNotNull { i ->
        if (assigned[i]) return@mapNotNull null
        val seed = ordered[i]
        val group = mutableListOf(seed)
        assigned[i] = true
        for (j in i + 1 until ordered.size) {
            if (!assigned[j] && clusterDistanceMeters(seed.coordinate, ordered[j].coordinate) <= radiusMeters) {
                group += ordered[j]
                assigned[j] = true
            }
        }
        buildClusterFromGroup(seed, group)
    }
}

private fun singleMarkerCluster(marker: HistoryMapMarker): MapCluster =
    MapCluster(
        id = marker.id,
        coordinate = marker.coordinate,
        placeCount = 1,
        totalVisits = marker.visitCount.coerceAtLeast(1),
        dominantCategory = marker.category,
        memberIds = listOf(marker.id),
        memberCoordinates = listOf(marker.coordinate),
        label = marker.label,
    )

private fun buildClusterFromGroup(
    seed: HistoryMapMarker,
    group: List<HistoryMapMarker>,
): MapCluster {
    val totalVisits = group.sumOf { it.visitCount.coerceAtLeast(1) }
    val weightedLat = group.sumOf { it.coordinate.latitude * it.visitCount.coerceAtLeast(1) } / totalVisits
    val weightedLon = group.sumOf { it.coordinate.longitude * it.visitCount.coerceAtLeast(1) } / totalVisits
    val dominantCategory =
        group
            .mapNotNull { marker -> marker.category?.let { it to marker.visitCount.coerceAtLeast(1) } }
            .groupBy({ it.first }, { it.second })
            .maxByOrNull { (_, weights) -> weights.sum() }
            ?.key
    val memberIds = group.map { it.id }
    return MapCluster(
        id = if (group.size == 1) seed.id else "cluster:${memberIds.sorted().joinToString(",")}",
        coordinate = HistoryCoordinate(weightedLat, weightedLon),
        placeCount = group.size,
        totalVisits = totalVisits,
        dominantCategory = dominantCategory,
        memberIds = memberIds,
        memberCoordinates = group.map { it.coordinate },
        label =
            if (group.size == 1) {
                seed.label
            } else {
                UiText.Plural(Res.plurals.history_places_count, group.size, listOf(group.size))
            },
    )
}

private fun clusterDistanceMeters(
    a: HistoryCoordinate,
    b: HistoryCoordinate,
): Double {
    val dLat = (b.latitude - a.latitude) * PI / 180.0
    val dLon = (b.longitude - a.longitude) * PI / 180.0
    val lat1 = a.latitude * PI / 180.0
    val lat2 = b.latitude * PI / 180.0
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 6_371_000.0 * 2.0 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1.0 - h).coerceIn(0.0, 1.0)))
}
