@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.merge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.feature.journals.ui.JournalCover
import app.logdate.shared.model.Journal
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing

@Composable
internal fun JournalMergeBooks(
    preview: JournalMergePreview,
    merging: Boolean,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit = {},
) {
    val source = preview.source
    val destination = preview.destination
    val reduced by rememberSystemReduceMotion()
    val progress = remember(source.id, destination.id) { Animatable(0f) }
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(merging, reduced) {
        val target = if (merging) 1f else 0f
        if (reduced) progress.snapTo(target) else progress.animateTo(target, tween(680, easing = FastOutSlowInEasing))
        if (merging) settled()
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val density = LocalDensity.current
    BoxWithConstraints(modifier.testTag("journal_merge_books"), contentAlignment = Alignment.Center) {
        if (density.fontScale >= 1.5f || maxWidth / density.fontScale < 280.dp) {
            var sourceHeight by remember { mutableIntStateOf(0) }
            val gap = with(density) { Spacing.lg.toPx() }
            val coverWidth = maxWidth.coerceAtMost(256.dp)
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                MergeBook(
                    source,
                    "journal_merge_source_cover",
                    Modifier.width(coverWidth).onSizeChanged { sourceHeight = it.height }.graphicsLayer {
                        alpha = 1f - progress.value
                        scaleX = 1f - progress.value * .12f
                        scaleY = scaleX
                    },
                )
                MergeBook(
                    destination,
                    "journal_merge_destination_cover",
                    Modifier.width(coverWidth).graphicsLayer {
                        translationY = -(sourceHeight + gap) * progress.value
                        scaleX = 1f + progress.value * .04f
                        scaleY = scaleX
                    },
                )
            }
        } else {
            val heightLimit = if (constraints.hasBoundedHeight) ((maxHeight - 24.dp) * .5625f).coerceAtLeast(80.dp) else 256.dp
            val sourceWidth = (maxWidth * .48f).coerceAtMost(256.dp).coerceAtMost(heightLimit * .94f)
            val destinationWidth = (maxWidth * .56f).coerceAtMost(256.dp).coerceAtMost(heightLimit)
            val spread = with(density) { (maxWidth * .20f).coerceAtMost(150.dp).toPx() }
            val shift = (1f - progress.value) * spread * direction
            MergeBook(
                source,
                "journal_merge_source_cover",
                Modifier.align(Alignment.Center).width(sourceWidth).graphicsLayer {
                    translationX = -shift
                    alpha = 1f - progress.value
                    scaleX = 1f - progress.value * .12f
                    scaleY = scaleX
                },
                rotation = 6f * direction * (1f - progress.value),
            )
            MergeBook(
                destination,
                "journal_merge_destination_cover",
                Modifier.align(Alignment.Center).width(destinationWidth).graphicsLayer {
                    translationX = shift
                    scaleX = 1f + progress.value * .04f
                    scaleY = scaleX
                },
                rotation = -6f * direction * (1f - progress.value),
            )
        }
    }
}

@Composable
private fun MergeBook(
    journal: Journal,
    coverTag: String,
    modifier: Modifier,
    rotation: Float = 0f,
) {
    JournalCover(
        journal,
        modifier.testTag(coverTag).graphicsLayer { rotationZ = rotation },
        elevation = 4.dp,
        showLastUpdated = false,
    )
}
