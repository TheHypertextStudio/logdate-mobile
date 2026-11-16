@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.people_title
import logdate.client.feature.core.generated.resources.person_remove_dialog_message
import logdate.client.feature.core.generated.resources.person_remove_dialog_title
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_remove
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * One person's page.
 *
 * @param onOpenEntry opens a linked entry. Without it the linked entries are listed but cannot be
 *   opened.
 */
@Composable
fun PersonDetailScreen(
    personId: Uuid,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEntry: ((Uuid) -> Unit)? = null,
    viewModel: PersonDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState().collectAsStateWithLifecycle()

    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    LaunchedEffect(uiState.isRemoved) {
        if (!uiState.isRemoved) return@LaunchedEffect
        onBack()
    }

    PersonDetailContent(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
        onRemove = viewModel::remove,
        onOpenEntry = onOpenEntry,
        onDismissNotice = viewModel::dismissNotice,
    )
}

/**
 * Stateless person page body.
 *
 * A phone, tablet or desktop window shows one list: who the person is, the entries linked to them,
 * and the action that removes them. A foldable held open in book posture puts the person and the
 * remove action on the start pane and the linked entries on the end pane. Removing asks for
 * confirmation first.
 */
@Composable
fun PersonDetailContent(
    uiState: PersonDetailViewModel.UiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit = {},
    onOpenEntry: ((Uuid) -> Unit)? = null,
    onDismissNotice: () -> Unit = {},
) {
    var confirmingRemoval by rememberSaveable { mutableStateOf(false) }
    val person = uiState.person?.takeUnless { uiState.isLoading }
    val sectionModifier = Modifier.padding(horizontal = Spacing.lg)

    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            PersonPane {
                if (person == null) {
                    PersonStatus(isLoading = uiState.isLoading, modifier = sectionModifier)
                    return@PersonPane
                }
                PersonIdentity(person = person, modifier = sectionModifier)
                uiState.notice?.let { notice ->
                    PeopleNoticeCard(notice = notice, onDismiss = onDismissNotice, modifier = sectionModifier)
                }
                RemovePersonButton(
                    enabled = !uiState.isRemoving,
                    onClick = { confirmingRemoval = true },
                    modifier = sectionModifier,
                )
            }
        },
        endPane = {
            PersonPane {
                if (person == null) return@PersonPane
                PersonEntries(entries = uiState.linkedEntries, onOpenEntry = onOpenEntry, modifier = sectionModifier)
                if (uiState.showEvents) {
                    PersonEvents(events = uiState.linkedEvents, modifier = sectionModifier)
                }
            }
        },
        standardContent = {
            SettingsScaffold(
                title = person?.name ?: stringResource(Res.string.people_title),
                onBack = onBack,
            ) {
                if (person == null) {
                    item { PersonStatus(isLoading = uiState.isLoading, modifier = sectionModifier) }
                    return@SettingsScaffold
                }
                item { PersonIdentity(person = person, modifier = sectionModifier) }
                uiState.notice?.let { notice ->
                    item {
                        PeopleNoticeCard(notice = notice, onDismiss = onDismissNotice, modifier = sectionModifier)
                    }
                }
                item {
                    PersonEntries(entries = uiState.linkedEntries, onOpenEntry = onOpenEntry, modifier = sectionModifier)
                }
                if (uiState.showEvents) {
                    item { PersonEvents(events = uiState.linkedEvents, modifier = sectionModifier) }
                }
                item {
                    RemovePersonButton(
                        enabled = !uiState.isRemoving,
                        onClick = { confirmingRemoval = true },
                        modifier = sectionModifier,
                    )
                }
            }
        },
    )

    if (confirmingRemoval && person != null) {
        RemovePersonDialog(
            name = person.name,
            onConfirm = {
                confirmingRemoval = false
                onRemove()
            },
            onDismiss = { confirmingRemoval = false },
        )
    }
}

/**
 * One scrolling pane of the book-posture layout.
 */
@Composable
private fun PersonPane(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        content = content,
    )
}

@Composable
private fun RemovePersonDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.person_remove_dialog_title, name)) },
        text = { Text(stringResource(Res.string.person_remove_dialog_message, name)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(UiRes.string.common_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiRes.string.common_cancel))
            }
        },
    )
}
