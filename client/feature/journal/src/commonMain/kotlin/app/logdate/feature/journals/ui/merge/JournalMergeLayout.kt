@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.ui.theme.Spacing
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.journal_merge_review_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun JournalMergeReviewLayout(
    state: JournalMergeUiState,
    preview: JournalMergePreview,
    onChangeDestination: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onMergeAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeSource: () -> Unit = {},
) {
    val changed = state.error == JournalMergeError.Changed
    val stage: @Composable (Modifier) -> Unit = { stageModifier ->
        JournalMergeIdentityComparison(
            preview,
            state.stage is JournalMergeStage.Merging,
            stageModifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            onMergeAnimationFinished,
        )
    }
    val decision: @Composable (Modifier, Boolean) -> Unit = { decisionModifier, spacious ->
        Surface(
            decisionModifier.testTag("journal_merge_decision"),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                Modifier.padding(if (spacious) Spacing.xl else Spacing.lg),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    Text(
                        stringResource(Res.string.journal_merge_review_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    JournalMergeSelection(
                        preview,
                        onChangeDestination,
                        enabled = state.stage !is JournalMergeStage.Merging,
                        onChangeSource = onChangeSource,
                        sourceEditable = !state.recovery,
                        changed = changed,
                    )
                    JournalMergeConsequences(state.recovery)
                }
                JournalMergeActions(state, onConfirm, onCancel, Modifier.padding(top = Spacing.lg), spacious = spacious)
            }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier.fillMaxSize().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            (maxWidth >= 960.dp || (maxWidth >= 760.dp && maxWidth > maxHeight)) && fontScale < 1.5f -> {
                val topInset = if (maxHeight < 640.dp) 72.dp else Spacing.lg
                val visualHeight = (maxHeight - topInset).coerceIn(240.dp, 560.dp)
                val spacious = maxHeight >= 560.dp
                val decisionWidth = maxWidth * .46f
                val decisionHeight = maxHeight - topInset
                Row(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = topInset),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                    verticalAlignment = if (spacious) Alignment.CenterVertically else Alignment.Top,
                ) {
                    stage(Modifier.weight(1f).height(visualHeight))
                    decision(Modifier.width(decisionWidth).heightIn(min = decisionHeight), spacious)
                }
            }

            maxHeight >= 720.dp && maxWidth >= 312.dp * fontScale && fontScale < 1.5f -> {
                val availableHeight = maxHeight
                Layout(
                    content = {
                        stage(Modifier.fillMaxWidth())
                        decision(Modifier.fillMaxWidth(), false)
                    },
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 72.dp),
                ) { measurables, constraints ->
                    val decisionPlaceable = measurables[1].measure(constraints.copy(minHeight = 0))
                    val gap = Spacing.lg.roundToPx()
                    val visualHeight =
                        (availableHeight.roundToPx() - 72.dp.roundToPx() - decisionPlaceable.height - gap)
                            .coerceIn(240.dp.roundToPx(), 560.dp.roundToPx())
                    val books = measurables[0].measure(constraints.copy(minHeight = visualHeight, maxHeight = visualHeight))
                    layout(constraints.maxWidth, books.height + gap + decisionPlaceable.height) {
                        books.placeRelative(0, 0)
                        decisionPlaceable.placeRelative(0, books.height + gap)
                    }
                }
            }

            else -> {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 72.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    stage(Modifier.fillMaxWidth())
                    decision(Modifier.fillMaxWidth(), false)
                }
            }
        }
    }
}
