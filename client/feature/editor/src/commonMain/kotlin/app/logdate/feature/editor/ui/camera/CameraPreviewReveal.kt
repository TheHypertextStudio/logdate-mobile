package app.logdate.feature.editor.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import app.logdate.ui.platform.rememberSystemReduceMotion

internal const val CAMERA_PREVIEW_COVER_TAG = "camera_preview_cover"

/**
 * Holds the card dark until the first camera frame arrives, then lets the picture fade up, so the
 * preview never pops in. Only the first start fades: flipping cameras has its own cover.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun CameraPreviewRevealCover(
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var streamedOnce by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) { if (streaming) streamedOnce = true }
    val reduceMotion = rememberSystemReduceMotion().value
    val reveal by animateFloatAsState(
        if (streamedOnce) 1f else 0f,
        if (reduceMotion) snap() else MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "cameraPreviewReveal",
    )
    if (reveal < 1f) {
        Box(
            modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - reveal }
                .background(Color.Black)
                .testTag(CAMERA_PREVIEW_COVER_TAG),
        )
    }
}
