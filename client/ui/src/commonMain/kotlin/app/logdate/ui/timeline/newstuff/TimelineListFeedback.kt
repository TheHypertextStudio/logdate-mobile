@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.timeline.newstuff

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.restore.LocalAcknowledgeCloudRestore
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.TimelineDayCardLayout
import kotlinx.datetime.LocalDate
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.common_retry
import logdate.client.ui.generated.resources.post_restore_import_action
import logdate.client.ui.generated.resources.post_restore_import_message
import logdate.client.ui.generated.resources.post_restore_start_fresh
import logdate.client.ui.generated.resources.post_restore_welcome_back
import logdate.client.ui.generated.resources.timeline_empty_action
import logdate.client.ui.generated.resources.timeline_empty_message
import logdate.client.ui.generated.resources.timeline_empty_title
import logdate.client.ui.generated.resources.timeline_loading_older_days
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TimelineAppendLoadingItem(modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .padding(start = Spacing.lg),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(Res.string.timeline_loading_older_days),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TimelineAppendErrorItem(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .padding(start = Spacing.lg),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.padding(end = Spacing.xs),
            )
            Text(stringResource(Res.string.common_retry))
        }
    }
}

@Composable
internal fun TimelineDaySkeleton(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .padding(bottom = Spacing.xl),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.width(96.dp).height(14.dp),
        ) {}
        TimelineMemoryTextBlock(text = null, emphasized = true)
        TimelineMemoryTextBlock(text = null)
    }
}

@Composable
internal fun TimelineDayCardLayout.style(): TimelineDayStyle =
    when (this) {
        TimelineDayCardLayout.MEDIA_LED -> {
            TimelineDayStyle(
                accentColor = MaterialTheme.colorScheme.primary,
                softAccentColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                chipColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
                textHighlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
            )
        }

        TimelineDayCardLayout.VOICE_LED -> {
            TimelineDayStyle(
                accentColor = MaterialTheme.colorScheme.secondary,
                softAccentColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f),
                chipColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f),
                textHighlightColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
            )
        }

        TimelineDayCardLayout.PLACE_LED -> {
            TimelineDayStyle(
                accentColor = MaterialTheme.colorScheme.tertiary,
                softAccentColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f),
                chipColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f),
                textHighlightColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.22f),
            )
        }

        TimelineDayCardLayout.STORY_LED -> {
            TimelineDayStyle(
                accentColor = MaterialTheme.colorScheme.primary,
                softAccentColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                chipColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                textHighlightColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }

internal fun Dp.toTimelineLayoutMode(): TimelineDayLayoutMode =
    when {
        this >= 920.dp -> TimelineDayLayoutMode.EXPANDED
        this >= 620.dp -> TimelineDayLayoutMode.MEDIUM
        else -> TimelineDayLayoutMode.COMPACT
    }

internal fun TimelineDayLayoutMode.contentType(): TimelineListContentType =
    when (this) {
        TimelineDayLayoutMode.COMPACT -> TimelineListContentType.DAY_COMPACT
        TimelineDayLayoutMode.MEDIUM -> TimelineListContentType.DAY_MEDIUM
        TimelineDayLayoutMode.EXPANDED -> TimelineListContentType.DAY_EXPANDED
    }

internal fun LocalDate.shortMonthLabel(): String =
    when (month) {
        kotlinx.datetime.Month.JANUARY -> "JAN"
        kotlinx.datetime.Month.FEBRUARY -> "FEB"
        kotlinx.datetime.Month.MARCH -> "MAR"
        kotlinx.datetime.Month.APRIL -> "APR"
        kotlinx.datetime.Month.MAY -> "MAY"
        kotlinx.datetime.Month.JUNE -> "JUN"
        kotlinx.datetime.Month.JULY -> "JUL"
        kotlinx.datetime.Month.AUGUST -> "AUG"
        kotlinx.datetime.Month.SEPTEMBER -> "SEP"
        kotlinx.datetime.Month.OCTOBER -> "OCT"
        kotlinx.datetime.Month.NOVEMBER -> "NOV"
        else -> "DEC"
    }

@Composable
internal fun TimelineEmptyState(
    onStartWriting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
    ) {
        Text(
            text = stringResource(Res.string.timeline_empty_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(Res.string.timeline_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onStartWriting) {
            Text(stringResource(Res.string.timeline_empty_action))
        }
    }
}

@Composable
internal fun PostRestoreEmptyState(
    onImportBackup: () -> Unit,
    onStartWriting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val acknowledgeRestore = LocalAcknowledgeCloudRestore.current

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
    ) {
        Text(
            text = stringResource(Res.string.post_restore_welcome_back),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(Res.string.post_restore_import_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            FilledTonalButton(onClick = {
                acknowledgeRestore()
                onImportBackup()
            }) {
                Text(stringResource(Res.string.post_restore_import_action))
            }
            FilledTonalButton(onClick = {
                acknowledgeRestore()
                onStartWriting()
            }) {
                Text(stringResource(Res.string.post_restore_start_fresh))
            }
        }
    }
}
