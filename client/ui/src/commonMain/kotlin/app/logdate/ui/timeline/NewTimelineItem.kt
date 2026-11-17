@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.ui.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.conditional
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.theme.Spacing
import app.logdate.util.asTime
import logdate.client.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A timeline item represents a "day", where a day represents a block of connected events
 * spanning a maximum of roughly 24 hours.
 */
@Composable
fun NewTimelineItem(
    title: String,
    metadata: @Composable () -> Unit,
    summaryView: @Composable () -> Unit,
    expandedView: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detailLevel: ItemDetailLevel = ItemDetailLevel.MAX,
    onOptionsClick: () -> Unit = {},
    showOptions: Boolean = true,
) {
    val titleStyle =
        when (detailLevel) {
            ItemDetailLevel.MAX -> {
                MaterialTheme.typography.titleLarge
            }

            ItemDetailLevel.MIN -> {
                MaterialTheme.typography.titleSmall
            }
        }
    Row(
        modifier
            .animateContentSize()
            .fillMaxWidth()
            .conditional(detailLevel != ItemDetailLevel.MIN) {
                height(56.dp)
                padding(start = Spacing.sm)
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        TimelineLine(
            modifier =
                Modifier
                    .padding(vertical = Spacing.md),
        )
        LazyColumn {
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(title, style = titleStyle)
                        if (detailLevel == ItemDetailLevel.MAX) {
                            metadata()
                        }
                    }
                    if (showOptions) {
                        IconButton(onClick = onOptionsClick) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.options))
                        }
                    }
                }
            }
            item {
                AnimatedContent(
                    targetState = detailLevel,
                    modifier =
                        Modifier
                            .animateContentSize()
                            .padding(end = Spacing.lg),
                    label = "Content View",
                ) { targetDetailLevel ->
                    if (targetDetailLevel == ItemDetailLevel.MAX) {
                        expandedView()
                    } else {
                        summaryView()
                    }
                }
            }
        }
    }
}

@Composable
fun TextTimelineDetailItem(
    text: String,
    timestamp: Instant,
    onClick: () -> Unit = {},
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusableWithRing()
                .clickable(onClick = onClick),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Text(
                text,
                modifier = Modifier.fillMaxWidth(),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        lineBreak = LineBreak.Paragraph,
                    ),
            )
        }
        Text(
            timestamp.asTime,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        )
    }
}

@Preview
@Composable
fun NewTimelineItemPreview_MaxDetails() {
    Column(Modifier.height(256.dp)) {
        NewTimelineItem(
            title = "March 28",
            metadata = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(stringResource(Res.string.text_10_places), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(Res.string.text_2_people), style = MaterialTheme.typography.labelSmall)
                }
            },
            summaryView = {
            },
            expandedView = {
            },
        )
    }
}

@Preview
@Composable
fun NewTimelineItemPreview_MinDetails() {
    Column(Modifier.height(256.dp)) {
        NewTimelineItem(
            title = "March 28",
            metadata = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(stringResource(Res.string.text_10_places), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(Res.string.text_2_people), style = MaterialTheme.typography.labelSmall)
                }
            },
            summaryView = {
                TextTimelineDetailItem(
                    text = "This is something cool.",
                    timestamp = Clock.System.now(),
                )
                TextTimelineDetailItem(
                    text = "This is something cool.",
                    timestamp = Clock.System.now(),
                )
                TextTimelineDetailItem(
                    text = "This is something cool.",
                    timestamp = Clock.System.now(),
                )
            },
            expandedView = {
                TextTimelineDetailItem(
                    text = "This is something cool.",
                    timestamp = Clock.System.now(),
                )
                TextTimelineDetailItem(
                    text = "This is something cool.",
                    timestamp = Clock.System.now(),
                )
                TextTimelineDetailItem(
                    text = "This is something cool.",
                    timestamp = Clock.System.now(),
                )
            },
            detailLevel = ItemDetailLevel.MIN,
        )
    }
}
