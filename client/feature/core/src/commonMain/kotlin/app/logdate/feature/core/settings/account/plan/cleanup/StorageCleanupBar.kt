@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.formatByteSize
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.delay
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.storage_cleanup_clear_selection
import logdate.client.feature.core.generated.resources.storage_cleanup_delete
import logdate.client.feature.core.generated.resources.storage_cleanup_deleted
import logdate.client.feature.core.generated.resources.storage_cleanup_failed
import logdate.client.feature.core.generated.resources.storage_cleanup_freed
import logdate.client.feature.core.generated.resources.storage_cleanup_selected
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one tinted surface on the page. While things are chosen it says how many and how much room
 * they take, and deletes them; right after a deletion it says what was freed, then leaves.
 */
@Composable
internal fun CleanupBar(
    state: StorageCleanupUiState.Ready,
    onDelete: () -> Unit,
    actions: StorageCleanupActions,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalLogDateHaptics.current
    val outcome = state.outcome
    val content: BarContent? =
        when {
            state.selected.isNotEmpty() -> BarContent.Choosing(state.selected.size, state.selectedBytes, outcome?.failedCount ?: 0)
            outcome != null && outcome.deletedCount > 0 -> BarContent.Freed(outcome.freedBytes, outcome.deletedCount)
            else -> null
        }
    // The bar keeps its last words while it slides away.
    var last by remember { mutableStateOf(content) }
    if (content != null) last = content
    val shown = last

    if (content is BarContent.Freed) {
        LaunchedEffect(content) {
            haptics.saveSucceeded()
            delay(RESULT_SHOWN_MILLIS)
            actions.onResultShown()
        }
    }

    AnimatedVisibility(
        visible = content != null,
        enter =
            slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit =
            slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Surface(
                shape = CircleShape,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                shadowElevation = BAR_ELEVATION,
                modifier = Modifier.widthIn(max = BAR_MAX_WIDTH).fillMaxWidth().testTag(STORAGE_CLEANUP_BAR_TAG),
            ) {
                val appear = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                val vanish = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                AnimatedContent(
                    targetState = shown is BarContent.Freed,
                    transitionSpec = { fadeIn(appear) togetherWith fadeOut(vanish) },
                ) { freed ->
                    when {
                        freed && shown is BarContent.Freed -> FreedLine(shown)
                        shown is BarContent.Choosing -> ChoosingLine(shown, state.deleting, onDelete, actions.onClearSelection)
                        else -> Box(Modifier.heightIn(min = BAR_MIN_HEIGHT))
                    }
                }
            }
        }
    }
}

/** What the bar says. */
private sealed interface BarContent {
    data class Choosing(
        val count: Int,
        val bytes: Long,
        val failed: Int,
    ) : BarContent

    data class Freed(
        val bytes: Long,
        val count: Int,
    ) : BarContent
}

@Composable
private fun ChoosingLine(
    content: BarContent.Choosing,
    deleting: Boolean,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        // The vertical padding lets the bar grow around larger text.
        modifier =
            Modifier
                .heightIn(
                    min = BAR_MIN_HEIGHT,
                ).padding(start = Spacing.xs, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear, enabled = !deleting, modifier = Modifier.size(TOUCH_TARGET).testTag(STORAGE_CLEANUP_CLEAR_TAG)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.storage_cleanup_clear_selection))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs)) {
            Text(
                text = pluralStringResource(Res.plurals.storage_cleanup_selected, content.count, content.count),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            // Something that would not delete says so here, where the size was.
            val detail =
                when {
                    content.failed > 0 -> pluralStringResource(Res.plurals.storage_cleanup_failed, content.failed, content.failed)
                    content.bytes > 0 -> formatByteSize(content.bytes)
                    else -> null
                }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (content.failed > 0) colors.error else LocalContentColor.current.copy(alpha = CLEANUP_QUIET_INK),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (content.failed > 0) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
            }
        }
        Button(
            onClick = onDelete,
            enabled = !deleting,
            modifier = Modifier.heightIn(min = TOUCH_TARGET).testTag(STORAGE_CLEANUP_DELETE_TAG),
        ) {
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PROGRESS_SIZE),
                    strokeWidth = PROGRESS_STROKE,
                    color = LocalContentColor.current,
                )
            } else {
                Text(stringResource(Res.string.storage_cleanup_delete))
            }
        }
    }
}

@Composable
private fun FreedLine(content: BarContent.Freed) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = BAR_MIN_HEIGHT)
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(STORAGE_CLEANUP_RESULT_TAG),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(RESULT_ICON))
        Text(
            text =
                if (content.bytes > 0) {
                    stringResource(Res.string.storage_cleanup_freed, formatByteSize(content.bytes))
                } else {
                    pluralStringResource(Res.plurals.storage_cleanup_deleted, content.count, content.count)
                },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

private val TOUCH_TARGET = 48.dp
private val BAR_MAX_WIDTH = 480.dp
private val BAR_MIN_HEIGHT = 64.dp
private val BAR_ELEVATION = 3.dp
private val PROGRESS_SIZE = 18.dp
private val PROGRESS_STROKE = 2.dp
private val RESULT_ICON = 24.dp
private const val RESULT_SHOWN_MILLIS = 4_000L
