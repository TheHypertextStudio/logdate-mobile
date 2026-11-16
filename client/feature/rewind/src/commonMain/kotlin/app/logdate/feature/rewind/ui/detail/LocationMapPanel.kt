@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.feature.rewind.ui.MapPanelPoint
import app.logdate.ui.workspace.LocalWorkspacePlaybackInsets
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.rewind_map_loading
import logdate.client.feature.rewind.generated.resources.rewind_map_main_area
import logdate.client.feature.rewind.generated.resources.rewind_map_no_locations
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max

/** A non-interactive geographic backdrop. Story navigation keeps ownership of gestures. */
@Composable
fun LocationMapPanel(
    points: List<MapPanelPoint>,
    title: String,
    subtitle: String,
    accentSeed: Int,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(points) { rewindMapGeometry(points) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(panelAccentBackground(accentSeed)).testTag("rewind-map-surface")) {
        var headingHeight by remember(maxWidth, maxHeight, density.fontScale) { mutableStateOf(0.dp) }
        var noticeHeight by remember(maxWidth, maxHeight, density.fontScale) { mutableStateOf(0.dp) }
        val safeInsets = LocalWorkspacePlaybackInsets.current
        val safePadding = safeInsets.asPaddingValues()
        val compact = maxHeight < 480.dp
        val chromeClearance = if (compact) 80.dp else 96.dp
        val headingMaxHeight =
            maxOf(maxHeight * 0.45f, safePadding.calculateTopPadding() + chromeClearance + 80.dp).coerceAtMost(
                maxHeight * 0.75f,
            )
        val direction = LocalLayoutDirection.current
        val contentInsets =
            PaddingValues(
                start = safePadding.calculateStartPadding(direction) + 24.dp,
                end = safePadding.calculateEndPadding(direction) + 24.dp,
                top = (headingHeight + 24.dp).coerceAtMost(maxHeight * 0.6f),
                bottom = if (geometry.hasLocationsOutsideFrame) noticeHeight + 16.dp else safePadding.calculateBottomPadding() + 24.dp,
            )
        MapBackdrop(
            geometry,
            contentInsets,
            headingHeight > 0.dp && (!geometry.hasLocationsOutsideFrame || noticeHeight > 0.dp),
            Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { headingHeight = with(density) { it.height.toDp() } }
                .fillMaxWidth()
                .heightIn(max = headingMaxHeight)
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 24.dp, end = 24.dp, top = chromeClearance, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapHeading(title, subtitle, compact)
        }
        if (geometry.hasLocationsOutsideFrame) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { noticeHeight = with(density) { it.height.toDp() } }
                    .testTag("rewind-map-notice")
                    .heightIn(max = maxHeight * 0.3f)
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            ) { MainAreaNotice(geometry, Modifier.verticalScroll(rememberScrollState())) }
        }
    }
}

@Composable
private fun MapHeading(
    title: String,
    subtitle: String,
    compact: Boolean,
) {
    Text(
        title,
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Start,
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.9f),
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun MainAreaNotice(
    geometry: RewindMapGeometry,
    modifier: Modifier = Modifier,
) {
    if (geometry.hasLocationsOutsideFrame) {
        Text(
            stringResource(Res.string.rewind_map_main_area),
            modifier = modifier,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MapBackdrop(
    geometry: RewindMapGeometry,
    contentInsets: PaddingValues,
    layoutReady: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        when {
            geometry.points.isEmpty() -> {
                RewindMapMessage(
                    stringResource(Res.string.rewind_map_no_locations),
                    Modifier.align(Alignment.Center),
                )
            }

            !layoutReady -> {
                RewindMapMessage(stringResource(Res.string.rewind_map_loading), Modifier.align(Alignment.Center))
            }

            else -> {
                RewindGeographicMap(geometry, contentInsets, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal expect fun RewindGeographicMap(
    geometry: RewindMapGeometry,
    contentInsets: PaddingValues,
    modifier: Modifier,
)

@Composable
internal fun RewindMapMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier.padding(12.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(12.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

private data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

private fun List<MapPanelPoint>.boundingBox(): BoundingBox {
    var minLat = first().latitude
    var maxLat = first().latitude
    var minLon = first().longitude
    var maxLon = first().longitude
    forEach { point ->
        if (point.latitude < minLat) minLat = point.latitude
        if (point.latitude > maxLat) maxLat = point.latitude
        if (point.longitude < minLon) minLon = point.longitude
        if (point.longitude > maxLon) maxLon = point.longitude
    }
    return BoundingBox(minLat = minLat, maxLat = maxLat, minLon = minLon, maxLon = maxLon)
}

/**
 * Roughly converts a lat/lon bounding-box span into kilometers using the back-of-the-
 * envelope "1 degree of latitude is ~111 km, longitude varies but the average for
 * mid-latitudes is close enough" rule. The eligibility check inside [qualifiesForMapPanel]
 * uses this to decide whether the rewind's path is geographically meaningful or just
 * GPS jitter inside one apartment.
 */
internal fun List<MapPanelPoint>.approximateBoundingKilometers(): Double {
    if (size < 2) return 0.0
    val bounds = boundingBox()
    val latKm = abs(bounds.maxLat - bounds.minLat) * KM_PER_DEGREE_LAT
    val lonKm = abs(bounds.maxLon - bounds.minLon) * KM_PER_DEGREE_LAT
    return max(latKm, lonKm)
}

private const val KM_PER_DEGREE_LAT = 111.0

/**
 * Decides whether the rewind has a path interesting enough to render the map panel.
 *
 * The bar is conservative on purpose: a week spent in one apartment shouldn't get a
 * map panel that says "you barely moved", and a week with two GPS samples on the same
 * couch shouldn't get one either. The user has to actually have moved.
 */
internal fun List<MapPanelPoint>.qualifiesForMapPanel(): Boolean {
    if (size < MIN_POINTS_FOR_MAP_PANEL) return false
    return approximateBoundingKilometers() >= MIN_BOUNDING_KM
}

private const val MIN_POINTS_FOR_MAP_PANEL = 3
private const val MIN_BOUNDING_KM = 1.0
