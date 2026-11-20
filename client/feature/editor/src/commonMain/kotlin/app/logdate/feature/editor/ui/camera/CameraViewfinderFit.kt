package app.logdate.feature.editor.ui.camera

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/** Gap between the viewfinder card and the controls around it, and its side margins. */
internal val ViewfinderCardGap = 8.dp

/**
 * Where the live image goes in the space the camera panel measures.
 */
internal sealed interface CameraViewfinderFit {
    /** The image fits between the header and the shutter, so it sits in a card with nothing on it. */
    data class Card(
        val top: Dp,
        val width: Dp,
        val height: Dp,
    ) : CameraViewfinderFit

    /** The image is taller than the gap, so it fills the panel and the controls float over it. */
    data object Overlay : CameraViewfinderFit
}

/**
 * Picks [CameraViewfinderFit.Card] when the image fits between [topReserve] and [bottomReserve]
 * at full width, or at a narrower width that still keeps most of the panel. Otherwise the image
 * would shrink to a stamp, so it goes edge to edge instead.
 *
 * @param ratio width over height of the image.
 */
internal fun cameraViewfinderFit(
    panelWidth: Dp,
    panelHeight: Dp,
    topReserve: Dp,
    bottomReserve: Dp,
    ratio: Float,
): CameraViewfinderFit {
    val top = topReserve + ViewfinderCardGap
    val available = panelHeight - top - ViewfinderCardGap - bottomReserve
    val fullWidth = panelWidth - ViewfinderCardGap * 2
    if (available <= 0.dp || fullWidth <= 0.dp) return CameraViewfinderFit.Overlay
    val height = min(fullWidth / ratio, available)
    val width = height * ratio
    return if (width >= fullWidth * MIN_CARD_WIDTH_FRACTION) {
        CameraViewfinderFit.Card(top = top, width = width, height = height)
    } else {
        CameraViewfinderFit.Overlay
    }
}

private const val MIN_CARD_WIDTH_FRACTION = 0.8f
