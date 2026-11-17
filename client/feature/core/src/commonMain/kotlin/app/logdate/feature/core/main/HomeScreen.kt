@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.main

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.domain.timeline.Timeline
import app.logdate.feature.core.settings.ui.watch.v2.WatchReadyOnPhoneCard
import app.logdate.feature.core.settings.updates.AppUpdateCheckTrigger
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.core.settings.updates.AppUpdateStatus
import app.logdate.feature.core.streak.CampfireViewModel
import app.logdate.feature.core.sync.SyncAction
import app.logdate.feature.core.sync.SyncPresentationViewModel
import app.logdate.feature.journals.ui.JournalClickCallback
import app.logdate.feature.journals.ui.JournalListItemUiState
import app.logdate.feature.journals.ui.JournalsOverviewScreen
import app.logdate.feature.journals.ui.JournalsOverviewViewModel
import app.logdate.feature.rewind.ui.RewindOverviewScreen
import app.logdate.ui.audio.TranscriptionProvider
import app.logdate.ui.keyboard.AppKeyboardShortcut
import app.logdate.ui.keyboard.AppShortcutScope
import app.logdate.ui.timeline.TimelinePane
import app.logdate.ui.timeline.TimelineUiState
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceRailJournal
import app.logdate.ui.workspace.WorkspaceRailJournals
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.action_create_journal
import logdate.client.feature.core.generated.resources.workspace_add_a_memory
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun HomeScreen(
    onNewEntry: () -> Unit,
    onOpenJournal: JournalClickCallback,
    onCreateJournal: () -> Unit,
    onBrowseJournals: () -> Unit,
    onOpenRewind: (Uuid) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenDraft: (draftId: String) -> Unit = {},
    onImportBackup: () -> Unit = {},
    onSignInToRestoreAccount: () -> Unit = {},
    onOpenSyncSettings: () -> Unit = {},
    /** Where the storage-full banner's action leads. Settings, unless somewhere more specific is given. */
    onManageStorage: (() -> Unit)? = null,
    onEnterRecoveryPhrase: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
    onOpenSyncIssues: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onOpenTextNote: (Uuid) -> Unit = {},
    onOpenStreak: () -> Unit = {},
    hasRetainedJournalRecovery: Boolean = false,
    onOpenRecoveryOptions: () -> Unit = {},
    onWriteMark: (Uuid) -> Unit = {},
    onRecordVoice: () -> Unit = onNewEntry,
    locationContent: @Composable (Modifier) -> Unit = {},
    libraryContent: @Composable (Modifier) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    syncPresentationViewModel: SyncPresentationViewModel = koinViewModel(),
    campfireViewModel: CampfireViewModel = koinViewModel(),
) {
    // Held as State and read only inside the slots below, so a sync or streak update recomposes
    // the status button and banner rather than the whole home shell.
    val syncPresentation = syncPresentationViewModel.presentation.collectAsStateWithLifecycle()
    val accountSyncStatus = syncPresentationViewModel.accountStatus.collectAsStateWithLifecycle()
    val campfire = campfireViewModel.presentation.collectAsStateWithLifecycle()
    val flags: FeatureFlagStore = koinInject()
    val isLibraryEnabled by viewModel.isLibraryEnabled.collectAsStateWithLifecycle()
    val navigation = rememberHomeNavigation(isLibraryEnabled, flags)
    val visibleDestinations = navigation.destinations
    val currentDestination = navigation.selected
    // Ctrl+1 to Ctrl+5 open the Home tabs in the order the navigation shows them.
    val homeKeys =
        modifier.onPreviewKeyEvent { event ->
            val index = AppKeyboardShortcut.match(event, AppShortcutScope.Home)?.homeTabIndex
            val destination = index?.let(visibleDestinations::getOrNull) ?: return@onPreviewKeyEvent false
            navigation.select(destination)
            true
        }
    val snackbarHostState = remember { SnackbarHostState() }
    QuietSaveReturnEffect(snackbarHostState, onOpenDraft)
    val appUpdates: AppUpdateController = koinInject()
    val appUpdateState by appUpdates.uiState.collectAsStateWithLifecycle()
    val updateScope = rememberCoroutineScope()
    val onSyncAction: (SyncAction) -> Unit = { action ->
        when (action) {
            SyncAction.UseMobileData -> syncPresentationViewModel.useMobileData()
            SyncAction.SignIn -> onOpenSettings()
            SyncAction.ManageStorage -> (onManageStorage ?: onOpenSettings)()
            SyncAction.ReviewConflicts, SyncAction.ReviewIssues -> onOpenSyncIssues()
            SyncAction.OpenStatus -> onOpenSyncSettings()
            SyncAction.EnterRecoveryPhrase -> onEnterRecoveryPhrase()
            SyncAction.UpdateApp -> updateScope.launch { appUpdates.checkForUpdates(AppUpdateCheckTrigger.Manual) }
        }
    }
    val mergeJournals: MergeJournalsUseCase = koinInject()
    val mergeIssues = remember(mergeJournals) { mergeJournals.observeIssues() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sourceState = rememberSaveableStateHolder()
    val journalsViewModel: JournalsOverviewViewModel = koinViewModel()
    val journalsState by journalsViewModel.uiState.collectAsStateWithLifecycle()
    val railJournals =
        remember(journalsState.journals, onOpenJournal) {
            WorkspaceRailJournals(
                items =
                    journalsState.journals.filterIsInstance<JournalListItemUiState.ExistingJournal>().map {
                        WorkspaceRailJournal(it.data.id.toString(), it.data.title)
                    },
                onOpen = { onOpenJournal(Uuid.parse(it)) },
            )
        }
    HomeWorkspaceComposition(
        destinations =
            visibleDestinations.map {
                WorkspaceDestination(it.name, stringResource(it.label), it.selectedIcon, it.unselectedIcon)
            },
        selectedKey = currentDestination.name,
        onSelect = {
            navigation.select(HomeRouteDestination.valueOf(it))
        },
        onCreate =
            if (currentDestination == HomeRouteDestination.LocationHistory) {
                null
            } else {
                { if (currentDestination == HomeRouteDestination.Journals) onCreateJournal() else onNewEntry() }
            },
        createLabel =
            stringResource(
                if (currentDestination == HomeRouteDestination.Journals) {
                    Res.string.action_create_journal
                } else {
                    Res.string.workspace_add_a_memory
                },
            ),
        createEntryTransition = currentDestination != HomeRouteDestination.Journals,
        createOnPull = currentDestination == HomeRouteDestination.Timeline,
        onSearch = onOpenSearch,
        actions = {
            HomeWorkspaceAccountAction(
                syncPresentation.value,
                campfire.value,
                onOpenSettings,
                onOpenStreak,
                onSyncAction,
                accountSyncStatus.value,
                hasMergeRecovery = mergeIssues.value.isNotEmpty(),
                canUpdateApp = appUpdateState.status != AppUpdateStatus.Unsupported,
            )
        },
        status = {
            Column(Modifier.padding(horizontal = app.logdate.ui.theme.Spacing.lg)) {
                if (hasRetainedJournalRecovery) {
                    RetainedJournalRecoveryNotice(onOpenRecoveryOptions)
                }
                WatchRecordingStatus()
                WatchReadyOnPhoneCard(onClick = onOpenWatch)
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        journals = railJournals,
        modifier = homeKeys,
    ) {
        val destinationContent: @Composable () -> Unit = {
            sourceState.SaveableStateProvider(currentDestination.name) {
                when (currentDestination) {
                    HomeRouteDestination.Timeline -> {
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
                        val birthday by viewModel.birthday.collectAsStateWithLifecycle()
                        TranscriptionProvider(transcriptionState) {
                            Column {
                                TimelinePane(
                                    uiState =
                                        TimelineUiState(
                                            uiState.items,
                                            uiState.loadingState,
                                            uiState.isLoadingMore,
                                            uiState.hasMoreOlderContent,
                                            uiState.appendError,
                                        ),
                                    pendingMomentContent = { mark ->
                                        app.logdate.feature.timeline.ui.marks
                                            .MarkedMomentEntry(mark, onWriteMark)
                                    },
                                    onNewEntry = onNewEntry,
                                    onOpenDay = onOpenDay,
                                    onOpenTextNote = onOpenTextNote,
                                    onVisibleAudioNoteIdsChanged = viewModel::updateVisibleAudioNoteIds,
                                    onLoadMoreOlder = viewModel::loadMoreOlder,
                                    onOpenDraft = onOpenDraft,
                                    onDismissSuggestion = viewModel::dismissActivitySuggestion,
                                    onUseSuggestion = viewModel::useActivitySuggestion,
                                    onRecordVoice = onRecordVoice,
                                    onImportBackup = onImportBackup,
                                    onSignInToRestoreAccount = onSignInToRestoreAccount,
                                    timelineSuggestion = uiState.timelineSuggestion,
                                    birthday = birthday,
                                )
                            }
                        }
                    }

                    HomeRouteDestination.LocationHistory -> {
                        locationContent(Modifier.fillMaxSize())
                    }

                    HomeRouteDestination.Journals -> {
                        JournalsOverviewScreen(
                            onOpenJournal,
                            onBrowseJournals,
                            onCreateJournal,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    HomeRouteDestination.Library -> {
                        libraryContent(Modifier.fillMaxSize())
                    }

                    HomeRouteDestination.Rewind -> {
                        RewindOverviewScreen(
                            onOpenRewind,
                            modifier = Modifier.fillMaxSize(),
                            onCreateMemory = onNewEntry,
                        )
                    }
                }
            }
        }
        destinationContent()
    }
}
