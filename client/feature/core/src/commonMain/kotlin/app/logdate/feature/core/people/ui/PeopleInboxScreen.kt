@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.people_inbox_empty_state
import logdate.client.feature.core.generated.resources.people_review_add_action
import logdate.client.feature.core.generated.resources.people_review_dismiss_action
import logdate.client.feature.core.generated.resources.people_review_label
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun PeopleInboxScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PeopleInboxViewModel = koinViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    PeopleInboxContent(
        suggestions = suggestions,
        notice = notice,
        onBack = onBack,
        onConfirm = viewModel::confirm,
        onDismissSuggestion = viewModel::dismiss,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier,
    )
}

/**
 * Stateless list of the names waiting for review. Each name can be added to the list of people or
 * rejected so it is not suggested again.
 *
 * @param suggestions the names waiting for review, or `null` while they are still being read, when
 *   neither suggestions nor the empty message are shown
 */
@Composable
fun PeopleInboxContent(
    suggestions: List<PeopleInboxViewModel.SuggestionUiState>?,
    notice: PeopleNotice?,
    onBack: () -> Unit,
    onConfirm: (Uuid) -> Unit,
    onDismissSuggestion: (Uuid) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(Res.string.people_review_label),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (notice != null) {
            item {
                PeopleNoticeCard(
                    notice = notice,
                    onDismiss = onDismissNotice,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        if (suggestions == null) return@SettingsScaffold

        if (suggestions.isEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.people_inbox_empty_state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
            return@SettingsScaffold
        }

        items(count = suggestions.size, key = { index -> suggestions[index].id.toString() }) { index ->
            SuggestionCard(
                suggestion = suggestions[index],
                onConfirm = onConfirm,
                onDismissSuggestion = onDismissSuggestion,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
    }
}

/**
 * One suggested name with up to two lines from the entries or transcripts it was found in.
 */
@Composable
private fun SuggestionCard(
    suggestion: PeopleInboxViewModel.SuggestionUiState,
    onConfirm: (Uuid) -> Unit,
    onDismissSuggestion: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialContainer(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = suggestion.nameHint,
                style = MaterialTheme.typography.titleMedium,
            )
            suggestion.evidencePreview.forEach { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { onConfirm(suggestion.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.people_review_add_action))
            }
            TextButton(
                onClick = { onDismissSuggestion(suggestion.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.people_review_dismiss_action))
            }
        }
    }
}
