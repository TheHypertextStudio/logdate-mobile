package app.logdate.feature.editor.ui.camera

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Gap between the viewfinder card and what's around it, and its side margins. */
internal val ViewfinderCardGap = 8.dp

/**
 * Where the live image goes in the space the camera panel measures.
 */
internal sealed interface CameraViewfinderFit {
    /** A portrait card fills everything between the header and the dock; the shutter sits on it. */
    data class Card(
        val top: Dp,
        val width: Dp,
        val height: Dp,
    ) : CameraViewfinderFit

    /** The panel is too short for a portrait card, so the image fills it and the controls float over it. */
    data object Overlay : CameraViewfinderFit
}

/**
 * Gives the image every bit of the panel between [topReserve] and [bottomReserve], so there's never
 * an empty band between the image and the controls. The preview crops to fill the card; a panel
 * that can't hold a portrait card goes edge to edge instead.
 */
internal fun cameraViewfinderFit(
    panelWidth: Dp,
    panelHeight: Dp,
    topReserve: Dp,
    bottomReserve: Dp,
): CameraViewfinderFit {
    val top = topReserve + ViewfinderCardGap
    val height = panelHeight - top - ViewfinderCardGap - bottomReserve
    val width = panelWidth - ViewfinderCardGap * 2
    if (height <= 0.dp || width <= 0.dp) return CameraViewfinderFit.Overlay
    return if (height >= width * MIN_CARD_TALLNESS) {
        CameraViewfinderFit.Card(top = top, width = width, height = height)
    } else {
        CameraViewfinderFit.Overlay
    }
}

/** Below 3:4 the card would crop too much of the 9:16 capture. */
private const val MIN_CARD_TALLNESS = 4f / 3f
