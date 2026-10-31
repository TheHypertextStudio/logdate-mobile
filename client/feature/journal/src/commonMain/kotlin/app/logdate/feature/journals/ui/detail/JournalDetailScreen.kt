@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class)

package app.logdate.feature.journals.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.feature.journals.ui.merge.journalMergeAvailability
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.common.LocalHomeScreenPins
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.WorkspacePanel
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.add_to_journal
import logdate.client.feature.journal.generated.resources.cd_journal_options
import logdate.client.feature.journal.generated.resources.journal_add_to_home_screen
import logdate.client.feature.journal.generated.resources.journal_change_order
import logdate.client.feature.journal.generated.resources.journal_delete_label
import logdate.client.feature.journal.generated.resources.journal_detail_back_to_journals
import logdate.client.feature.journal.generated.resources.journal_detail_load_error
import logdate.client.feature.journal.generated.resources.journal_header_title
import logdate.client.feature.journal.generated.resources.journal_merge_action
import logdate.client.feature.journal.generated.resources.journal_merge_success
import logdate.client.feature.journal.generated.resources.journal_settings_label
import logdate.client.feature.journal.generated.resources.journal_share_label
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun JournalDetailScreen(
    journalId: Uuid,
    onGoBack: () -> Unit,
    onJournalDeleted: () -> Unit,
    onNavigateToNoteDetail: (noteId: Uuid) -> Unit = { _ -> },
    onOpenEditor: (Uuid) -> Unit = {},
    onOpenContentPicker: (Uuid) -> Unit = {},
    onNavigateToSettings: (journalId: Uuid) -> Unit = {},
    onNavigateToShare: (journalId: Uuid) -> Unit = {},
    onNavigateToMerge: (Uuid) -> Unit = {},
    mergedSourceTitle: String? = null,
    modifier: Modifier = Modifier,
    flags: FeatureFlagStore = koinInject(),
    config: LogDateConfigRepository = koinInject(),
    viewModel: JournalDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(journalId) {
        viewModel.setSelectedJournalId(journalId)
    }

    val state by viewModel.uiState.collectAsState()
    val mergeEnabled by remember(flags, config) { journalMergeAvailability(flags, config) }.collectAsState(false)
    val snackbarHostState = remember { SnackbarHostState() }
    val destinationTitle = (state as? JournalDetailUiState.Success)?.title
    val mergedMessage = destinationTitle?.let { stringResource(Res.string.journal_merge_success, mergedSourceTitle.orEmpty(), it) }
    var mergeNoticeShown by rememberSaveable(mergedSourceTitle) { mutableStateOf(false) }
    LaunchedEffect(mergedSourceTitle, mergedMessage) {
        if (!mergeNoticeShown && mergedSourceTitle != null && mergedMessage != null) {
            mergeNoticeShown = true
            snackbarHostState.showSnackbar(mergedMessage)
        }
    }
    var openDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var noteToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    JournalDetailScreenContent(
        uiState = state,
        mergeEnabled = mergeEnabled,
        onNavigateToMerge = onNavigateToMerge,
        snackbarHostState = snackbarHostState,
        onGoBack = onGoBack,
        onNavigateToNoteDetail = onNavigateToNoteDetail,
        onOpenEditor = { onOpenEditor(journalId) },
        onOpenContentPicker = { onOpenContentPicker(journalId) },
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToShare = onNavigateToShare,
        onToggleSortOrder = viewModel::toggleSortOrder,
        onRequestDelete = { openDeleteConfirmation = true },
        showDeleteConfirmation = openDeleteConfirmation,
        onDismissDeleteConfirmation = { openDeleteConfirmation = false },
        onConfirmDelete = {
            viewModel.deleteJournal(onJournalDeleted)
            openDeleteConfirmation = false
        },
        onRemoveNoteFromJournal = { noteId -> noteToRemove = noteId.toString() },
        showRemoveNoteConfirmation = noteToRemove != null,
        onDismissRemoveNoteConfirmation = { noteToRemove = null },
        onConfirmRemoveNote = {
            noteToRemove?.let { viewModel.removeNoteFromJournal(Uuid.parse(it)) }
            noteToRemove = null
        },
        modifier = modifier,
    )
}

@Composable
fun JournalDetailScreenContent(
    uiState: JournalDetailUiState,
    onGoBack: () -> Unit,
    onNavigateToNoteDetail: (noteId: Uuid) -> Unit = { _ -> },
    onOpenEditor: () -> Unit = {},
    onOpenContentPicker: () -> Unit = {},
    onNavigateToSettings: (journalId: Uuid) -> Unit = {},
    onNavigateToShare: (journalId: Uuid) -> Unit = {},
    onToggleSortOrder: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    showDeleteConfirmation: Boolean = false,
    onDismissDeleteConfirmation: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onRemoveNoteFromJournal: (noteId: Uuid) -> Unit = {},
    showRemoveNoteConfirmation: Boolean = false,
    onDismissRemoveNoteConfirmation: () -> Unit = {},
    onConfirmRemoveNote: () -> Unit = {},
    mergeEnabled: Boolean = false,
    onNavigateToMerge: (Uuid) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        is JournalDetailUiState.Loading -> {
            JournalDetailPlaceholder()
            return
        }

        is JournalDetailUiState.Error -> {
            WorkspacePanel(modifier) {
                Column(Modifier.padding(Spacing.lg)) {
                    Text(stringResource(Res.string.journal_detail_load_error))
                    TextButton(onClick = onGoBack) { Text(stringResource(Res.string.journal_detail_back_to_journals)) }
                }
            }
            return
        }

        is JournalDetailUiState.Success -> {
            var showOverflowMenu by remember { mutableStateOf(false) }
            var showAddMenu by remember { mutableStateOf(false) }
            var selectedTab by rememberSaveable { mutableStateOf(0) }
            val mediaEntries =
                remember(uiState.entries) {
                    uiState.entries.filter { it is EntryDisplayData.ImageEntry || it is EntryDisplayData.VideoEntry }
                }
            val hasMedia = mediaEntries.isNotEmpty()
            val journalHeaderTitle = stringResource(Res.string.journal_header_title)

            JournalReadingFrame(modifier) { contextVisible ->
                WorkspacePanel(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        JournalReadingHeader(if (contextVisible) journalHeaderTitle else uiState.title, onGoBack, actions = {
                            Box {
                                IconButton(onClick = { showAddMenu = true }) {
                                    Icon(Icons.Rounded.Add, stringResource(Res.string.add_to_journal))
                                }
                                JournalAddMenu(
                                    expanded = showAddMenu,
                                    onDismiss = { showAddMenu = false },
                                    onCreateEntry = onOpenEditor,
                                    onAddExistingContent = onOpenContentPicker,
                                )
                            }
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Rounded.MoreVert, stringResource(Res.string.cd_journal_options))
                                }
                                DropdownMenu(showOverflowMenu, { showOverflowMenu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.journal_change_order)) }, onClick = {
                                        showOverflowMenu = false
                                        onToggleSortOrder()
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.journal_share_label)) }, onClick = {
                                        showOverflowMenu = false
                                        onNavigateToShare(uiState.journalId)
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.journal_settings_label)) }, onClick = {
                                        showOverflowMenu = false
                                        onNavigateToSettings(uiState.journalId)
                                    })
                                    AddToHomeScreenItem(uiState) { showOverflowMenu = false }
                                    if (mergeEnabled) {
                                        DropdownMenuItem(text = { Text(stringResource(Res.string.journal_merge_action)) }, onClick = {
                                            showOverflowMenu = false
                                            onNavigateToMerge(uiState.journalId)
                                        })
                                    }
                                    DropdownMenuItem(text = { Text(stringResource(Res.string.journal_delete_label)) }, onClick = {
                                        showOverflowMenu = false
                                        onRequestDelete()
                                    })
                                }
                            }
                        })
                        JournalReadingContent(
                            uiState = uiState,
                            mediaEntries = mediaEntries,
                            hasMedia = hasMedia,
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                            onNavigateToNoteDetail = onNavigateToNoteDetail,
                            onRemoveNoteFromJournal = onRemoveNoteFromJournal,
                            onToggleSortOrder = onToggleSortOrder,
                            onNavigateToShare = onNavigateToShare,
                            onNavigateToSettings = onNavigateToSettings,
                            onRequestDelete = onRequestDelete,
                            mergeEnabled = mergeEnabled,
                            onNavigateToMerge = onNavigateToMerge,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contextVisible = contextVisible,
                        )
                        SnackbarHost(snackbarHostState)
                    }
                }
            }

            if (showDeleteConfirmation) {
                DeleteConfirmationDialog(
                    onDismissRequest = onDismissDeleteConfirmation,
                    onConfirmation = onConfirmDelete,
                )
            }

            if (showRemoveNoteConfirmation) {
                RemoveNoteFromJournalDialog(
                    onDismissRequest = onDismissRemoveNoteConfirmation,
                    onConfirmation = onConfirmRemoveNote,
                )
            }
        }
    }
}

/**
 * "Add to home screen" for a journal, shown only where the launcher accepts pinned shortcuts. The
 * launcher then asks the person to confirm and place the shortcut.
 */
@Composable
private fun AddToHomeScreenItem(
    uiState: JournalDetailUiState.Success,
    onDone: () -> Unit,
) {
    val pins = LocalHomeScreenPins.current ?: return
    if (!pins.canPin) return
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.journal_add_to_home_screen)) },
        onClick = {
            onDone()
            pins.pinJournal(uiState.journalId, uiState.title)
        },
    )
}
