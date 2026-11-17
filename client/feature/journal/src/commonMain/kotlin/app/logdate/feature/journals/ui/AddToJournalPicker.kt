@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import app.logdate.shared.model.Journal
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.platform.PlatformSheet
import app.logdate.ui.theme.Spacing
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.add_to_journal
import logdate.client.feature.journal.generated.resources.no_other_journals
import logdate.client.ui.generated.resources.common_done
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Adaptive picker for adding a note to one or more journals.
 *
 * Renders as a bottom sheet on compact screens and a dialog on expanded screens.
 * The caller provides the note ID; the picker loads journals and handles the
 * content association via [JournalContentRepository].
 *
 * @param currentJournalId The journal the note is currently being viewed in (excluded from the list).
 * @param journals All available journals.
 * @param memberJournalIds IDs of journals this note already belongs to.
 * @param onToggleMembership Called when the user checks/unchecks a journal.
 * @param onDismiss Called when the picker is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToJournalPicker(
    currentJournalId: Uuid? = null,
    journals: List<Journal>,
    memberJournalIds: Set<Uuid>,
    onToggleMembership: (journalId: Uuid, add: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val isExpanded =
        currentWindowAdaptiveInfoV2()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)

    val filteredJournals =
        remember(journals, currentJournalId) {
            if (currentJournalId != null) {
                journals.filter { it.id != currentJournalId }
            } else {
                journals
            }
        }

    if (isExpanded) {
        AddToJournalDialog(
            journals = filteredJournals,
            memberJournalIds = memberJournalIds,
            onToggleMembership = onToggleMembership,
            onDismiss = onDismiss,
        )
    } else {
        AddToJournalSheet(
            journals = filteredJournals,
            memberJournalIds = memberJournalIds,
            onToggleMembership = onToggleMembership,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToJournalSheet(
    journals: List<Journal>,
    memberJournalIds: Set<Uuid>,
    onToggleMembership: (journalId: Uuid, add: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    PlatformSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        JournalPickerContent(
            journals = journals,
            memberJournalIds = memberJournalIds,
            onToggleMembership = onToggleMembership,
        )
    }
}

@Composable
private fun AddToJournalDialog(
    journals: List<Journal>,
    memberJournalIds: Set<Uuid>,
    onToggleMembership: (journalId: Uuid, add: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_to_journal)) },
        text = {
            JournalPickerContent(
                journals = journals,
                memberJournalIds = memberJournalIds,
                onToggleMembership = onToggleMembership,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiRes.string.common_done))
            }
        },
    )
}

@Composable
private fun JournalPickerContent(
    journals: List<Journal>,
    memberJournalIds: Set<Uuid>,
    onToggleMembership: (journalId: Uuid, add: Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = Spacing.lg)) {
        Text(
            text = stringResource(Res.string.add_to_journal),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )

        if (journals.isEmpty()) {
            Text(
                text = stringResource(Res.string.no_other_journals),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.lg),
            )
        } else {
            LazyColumn {
                items(journals, key = { it.id }) { journal ->
                    val isMember = journal.id in memberJournalIds

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Checkbox(
                            checked = isMember,
                            onCheckedChange = { checked ->
                                onToggleMembership(journal.id, checked)
                            },
                        )
                        JournalCover(
                            journal = journal,
                            modifier = Modifier.size(40.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = journal.title,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
