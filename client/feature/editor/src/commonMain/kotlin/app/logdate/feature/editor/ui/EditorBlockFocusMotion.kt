@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.logdate.feature.editor.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.unit.Dp
import app.logdate.ui.creation.ExpressiveMotion
import kotlin.uuid.Uuid

internal val BlockFocusProgressKey = SemanticsPropertyKey<Float>("BlockFocusProgress")
internal val LocalBlockFocusId = compositionLocalOf<Uuid?> { null }

internal val LocalBlockFocusProgress = compositionLocalOf<Float?> { null }

/** Focus progress drives corners, alpha and chrome, so it never overshoots. */
internal fun <T> blockFocusSpring() = spring<T>(ExpressiveMotion.EFFECTS_DAMPING, ExpressiveMotion.SPATIAL_STIFFNESS)

/** The memory's surface moving between its place in the entry and focus settles like a container. */
internal fun <T> blockFocusBoundsSpring() = spring<T>(ExpressiveMotion.SPATIAL_DAMPING, ExpressiveMotion.SPATIAL_STIFFNESS)

@Composable
internal fun Modifier.editorBlockFocusElement(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    blockId: Uuid,
    cornerRadius: Dp,
    focusProgress: Float,
): Modifier =
    with(sharedScope) {
        sharedElement(
            rememberSharedContentState("block_surface_$blockId"),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = { _, _ -> blockFocusBoundsSpring() },
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(cornerRadius * (1f - focusProgress))),
        )
    }
