@file:Suppress("ktlint:standard:function-naming")

@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.logdate.feature.journals.ui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.shared.model.Journal
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.common.AspectRatios
import app.logdate.ui.common.ContextMenuArea
import app.logdate.ui.common.ContextMenuItem
import app.logdate.ui.common.focusRing
import app.logdate.ui.common.transitions.TransitionKeys
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.last_updated_prefix
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal data class JournalCoverTransition(
    val visible: Boolean,
    val durationMillis: Int,
)

internal val LocalJournalCoverTransition = compositionLocalOf<JournalCoverTransition?> { null }

/**
 * Shape for individual journal cover items (rounded right edges, flat left like a book spine).
 */
internal val JournalShape =
    RoundedCornerShape(
        topEnd = 16.dp,
        bottomEnd = 16.dp,
    )

/**
 * A journal cover that displays basic information about a journal.
 *
 * The cover color is derived deterministically from the journal's ID,
 * giving each journal a unique visual identity. Text color adapts
 * based on background luminance.
 */
@Composable
fun JournalCover(
    journal: Journal,
    modifier: Modifier = Modifier,
    onClick: JournalClickCallback? = null,
    contextMenuItems: List<ContextMenuItem> = emptyList(),
    enabled: Boolean = true,
    elevation: Dp = 0.dp,
    showLastUpdated: Boolean = true,
) {
    BoxWithConstraints(modifier) {
        JournalCoverFace(
            journal,
            Modifier.heightIn(min = maxWidth / AspectRatios.JOURNAL_COVER),
            onClick,
            contextMenuItems,
            enabled,
            elevation,
            showLastUpdated = showLastUpdated,
        )
    }
}

@Composable
private fun JournalCoverFace(
    journal: Journal,
    modifier: Modifier,
    onClick: JournalClickCallback?,
    contextMenuItems: List<ContextMenuItem>,
    enabled: Boolean,
    elevation: Dp,
    showLastUpdated: Boolean = true,
) {
    val callerTransition = LocalJournalCoverTransition.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedTransitionScope = LocalSharedTransitionScope.current

    val coverColor = remember(journal.id) { deriveCoverColor(journal.id) }

    val baseModifier =
        modifier
            .shadow(
                elevation = elevation,
                shape = JournalShape,
            ).background(
                color = coverColor,
                shape = JournalShape,
            ).let { mod ->
                if (onClick != null) {
                    mod.focusRing().clickable(enabled) { onClick(journal.id) }
                } else {
                    mod
                }
            }.widthIn(max = 256.dp)

    val hasCoverImage = journal.coverImageUri != null

    val textColor =
        remember(coverColor, hasCoverImage) {
            if (hasCoverImage) {
                // Text sits on the scrim, which is always dark
                Color.White.copy(alpha = 0.95f)
            } else if (coverColor.luminance() > 0.5f) {
                Color.Black.copy(alpha = 0.87f)
            } else {
                Color.White.copy(alpha = 0.95f)
            }
        }

    ContextMenuArea(items = contextMenuItems) {
        if (sharedTransitionScope != null && (animatedVisibilityScope != null || callerTransition != null)) {
            with(sharedTransitionScope) {
                Box(
                    modifier =
                        if (callerTransition != null) {
                            baseModifier.sharedElementWithCallerManagedVisibility(
                                rememberSharedContentState(TransitionKeys.journalContainerTransition(journal.id)),
                                visible = callerTransition.visible,
                                boundsTransform =
                                    BoundsTransform {
                                        _,
                                        _,
                                        ->
                                        tween(callerTransition.durationMillis, easing = FastOutSlowInEasing)
                                    },
                            )
                        } else {
                            baseModifier.sharedElement(
                                rememberSharedContentState(TransitionKeys.journalContainerTransition(journal.id)),
                                requireNotNull(animatedVisibilityScope),
                            )
                        },
                ) {
                    JournalCoverContent(journal, textColor, showLastUpdated)
                }
            }
        } else {
            Box(modifier = baseModifier) {
                JournalCoverContent(journal, textColor, showLastUpdated)
            }
        }
    }
}

@Composable
private fun BoxScope.JournalCoverContent(
    journal: Journal,
    textColor: Color,
    showLastUpdated: Boolean,
) {
    // Cover image with scrim when available
    journal.coverImageUri?.let { uri ->
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .matchParentSize()
                    .clip(JournalShape),
        )
        val scrimColor = MaterialTheme.colorScheme.scrim
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.0f to Color.Transparent,
                                    0.4f to Color.Transparent,
                                    1.0f to scrimColor.copy(alpha = 0.7f),
                                ),
                        ),
                        shape = JournalShape,
                    ),
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.Bottom),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = journal.title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
        )
        if (showLastUpdated) {
            Text(
                stringResource(Res.string.last_updated_prefix, journal.lastUpdated.toReadableDateShort()),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = textColor.alpha * 0.7f),
            )
        }
    }
}

@Preview
@Composable
fun JournalCoverPreview() {
    LogDateTheme {
        JournalCover(
            journal =
                Journal(
                    id = Uuid.random(),
                    title = "Diary",
                    description = "Description",
                    created = Clock.System.now(),
                    isFavorited = false,
                    lastUpdated = Clock.System.now(),
                ),
            modifier = Modifier.width(180.dp),
            elevation = 2.dp,
            onClick = {},
        )
    }
}
