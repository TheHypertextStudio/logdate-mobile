@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.main

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.domain.timeline.Timeline
import app.logdate.feature.core.streak.CampfireViewModel
import app.logdate.feature.core.sync.SyncAction
import app.logdate.feature.core.sync.SyncPresentationViewModel
import app.logdate.feature.journals.ui.JournalClickCallback
import app.logdate.feature.journals.ui.JournalsOverviewScreen
import app.logdate.feature.rewind.ui.RewindOverviewScreen
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.audio.TranscriptionProvider
import app.logdate.ui.common.applyScreenStyles
import app.logdate.ui.common.transitions.TransitionKeys
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.currentPlatform
import app.logdate.ui.streak.CampfireChip
import app.logdate.ui.timeline.TimelinePane
import app.logdate.ui.timeline.TimelineUiState
import app.logdate.ui.workspace.AdaptiveWorkspaceLayout
import app.logdate.ui.workspace.LocalWorkspaceDetail
import app.logdate.ui.workspace.LocalWorkspaceDismissDetail
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.create_new_entry
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

private val FabEditorBoundsTransform =
    BoundsTransform { _, _ ->
        tween(durationMillis = 350, easing = FastOutSlowInEasing)
    }

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
    onOpenMediaDetail: (Uuid) -> Unit = {},
    onOpenSyncSettings: () -> Unit = {},
    onOpenSyncIssues: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onOpenStreak: () -> Unit = {},
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
    val isLibraryEnabled by viewModel.isLibraryEnabled.collectAsStateWithLifecycle()
    val visibleDestinations = HomeRouteDestination.visibleEntries(isLibraryEnabled)
    var currentDestination: HomeRouteDestination by rememberSaveable {
        mutableStateOf(HomeRouteDestination.Timeline)
    }
    // Disabling Library while it's the open tab would otherwise leave the shell on a tab that's no
    // longer in the bar; fall back to Timeline whenever the current tab drops out of the list.
    LaunchedEffect(visibleDestinations) {
        if (currentDestination !in visibleDestinations) {
            currentDestination = HomeRouteDestination.Timeline
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val onSyncAction: (SyncAction) -> Unit = { action ->
        when (action) {
            SyncAction.UseMobileData -> syncPresentationViewModel.useMobileData()

            SyncAction.SignIn,
            SyncAction.ManageStorage,
            -> onOpenSettings()

            SyncAction.ReviewConflicts, SyncAction.ReviewIssues -> onOpenSyncIssues()

            SyncAction.OpenStatus -> onOpenSyncSettings()

            SyncAction.EnterRecoveryPhrase -> onOpenSyncSettings()
        }
    }
    val timelineStatusActions: @Composable RowScope.() -> Unit = {
        campfire.value?.let { presentation ->
            CampfireChip(
                presentation = presentation,
                onClick = onOpenStreak,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
    val flags: FeatureFlagStore = koinInject()
    val mergeJournals: MergeJournalsUseCase = koinInject()
    val mergeIssues = remember(mergeJournals) { mergeJournals.observeIssues() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val workspaceEnabled by remember(flags) { flags.observe(FeatureFlag.HOME_WORKSPACE_V2) }
        .collectAsStateWithLifecycle(initialValue = FeatureFlag.HOME_WORKSPACE_V2.defaultEnabled)
    if (workspaceEnabled) {
        val detail = LocalWorkspaceDetail.current
        val dismissDetail = LocalWorkspaceDismissDetail.current
        val sourceState = rememberSaveableStateHolder()
        WorkspaceScaffold(
            destinations = visibleDestinations.map { WorkspaceDestination(it.name, it.label, it.selectedIcon) },
            selectedKey = currentDestination.name,
            onSelect = {
                if (detail != null) dismissDetail()
                currentDestination = HomeRouteDestination.valueOf(it)
            },
            onCreate =
                if (currentDestination == HomeRouteDestination.LocationHistory) {
                    null
                } else {
                    { if (currentDestination == HomeRouteDestination.Journals) onCreateJournal() else onNewEntry() }
                },
            createLabel = if (currentDestination == HomeRouteDestination.Journals) "Create journal" else "Add a memory",
            createEntryTransition = currentDestination != HomeRouteDestination.Journals,
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
                )
            },
            modifier = modifier,
        ) {
            val destinationContent: @Composable () -> Unit = {
                sourceState.SaveableStateProvider(currentDestination.name) {
                    when (currentDestination) {
                        HomeRouteDestination.Timeline -> {
                            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                            val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
                            TranscriptionProvider(transcriptionState) {
                                TimelinePane(
                                    uiState =
                                        TimelineUiState(
                                            uiState.items,
                                            uiState.loadingState,
                                            uiState.isLoadingMore,
                                            uiState.hasMoreOlderContent,
                                            uiState.appendError,
                                        ),
                                    onNewEntry = onNewEntry,
                                    onOpenDay = onOpenDay,
                                    onVisibleAudioNoteIdsChanged = viewModel::updateVisibleAudioNoteIds,
                                    onLoadMoreOlder = viewModel::loadMoreOlder,
                                    onProfileClick = onOpenSettings,
                                    onSearchClick = onOpenSearch,
                                    onOpenDraft = onOpenDraft,
                                    onImportBackup = onImportBackup,
                                    timelineSuggestion = uiState.timelineSuggestion,
                                )
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
                            RewindOverviewScreen(onOpenRewind, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (detail == null && currentDestination == HomeRouteDestination.LocationHistory) {
                destinationContent()
            } else {
                AdaptiveWorkspaceLayout(
                    modifier = Modifier.fillMaxSize(),
                    browseOnStart = true,
                    focusConstraints =
                        if (detail == null && currentDestination == HomeRouteDestination.Timeline) {
                            app.logdate.ui.workspace.PanelConstraints.ReadingCollection
                        } else if (currentDestination ==
                            HomeRouteDestination.Library
                        ) {
                            app.logdate.ui.workspace.PanelConstraints.Visual
                        } else {
                            app.logdate.ui.workspace.PanelConstraints.Reading
                        },
                    browse = destinationContent.takeIf { detail != null },
                    focus = detail ?: destinationContent,
                )
            }
        }
        return
    }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val navLayoutType =
        when {
            // Foldable laid flat (tabletop): keep navigation in the bottom/flat half rather
            // than spanning a rail across the hinge.
            adaptiveInfo.windowPosture.isTabletop -> {
                NavigationSuiteType.NavigationBar
            }

            // Wide enough (>= 600dp), including landscape phones: always use the side rail,
            // never a bottom bar. Material's default would drop to a bottom bar here because
            // the window height is compact.
            adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND) -> {
                NavigationSuiteType.NavigationRail
            }

            else -> {
                NavigationSuiteType.NavigationBar
            }
        }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            NavigationSuiteScaffold(
                layoutType = navLayoutType,
                containerColor = Color.Transparent,
                navigationSuiteColors =
                    NavigationSuiteDefaults.colors(
                        navigationRailContainerColor = Color.Transparent,
                        navigationBarContainerColor = Color.Transparent,
                    ),
                navigationSuiteItems = {
                    visibleDestinations.forEach { destination ->
                        item(
                            selected = destination == currentDestination,
                            onClick = {
                                currentDestination = destination
                            },
                            icon = {
                                if (currentPlatform.isApple) {
                                    Icon(
                                        painter = destination.iosTabBarIcon(),
                                        contentDescription = destination.label,
                                    )
                                } else {
                                    Icon(
                                        imageVector =
                                            if (destination == currentDestination) {
                                                destination.selectedIcon
                                            } else {
                                                destination.unselectedIcon
                                            },
                                        contentDescription = destination.label,
                                    )
                                }
                            },
                            label = { Text(destination.label) },
                        )
                    }
                },
            ) {
                // Keep the FAB inside NavigationSuiteScaffold's content slot. The adaptive
                // navigation surface then measures and reserves its own bottom-bar or rail
                // space, so the create action remains responsive without a hardcoded offset.
                val sharedTransitionScope = LocalSharedTransitionScope.current
                val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    floatingActionButton = {
                        if (currentDestination != HomeRouteDestination.LocationHistory &&
                            !(currentPlatform.isApple && currentDestination == HomeRouteDestination.Timeline)
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    when (currentDestination) {
                                        HomeRouteDestination.Journals -> onCreateJournal()
                                        else -> onNewEntry()
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier =
                                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                rememberSharedContentState(TransitionKeys.FAB_TO_EDITOR_TRANSITION),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                boundsTransform = FabEditorBoundsTransform,
                                                clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.large),
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                            ) {
                                Icon(
                                    painter = PlatformIcons.newEntry(),
                                    contentDescription = stringResource(Res.string.create_new_entry),
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentDestination) {
                            HomeRouteDestination.Timeline -> {
                                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                                Column(
                                    modifier =
                                        Modifier
                                            .applyScreenStyles()
                                            .safeDrawingPadding(),
                                ) {
                                    val transcriptionState by viewModel.transcriptionState.collectAsStateWithLifecycle()
                                    TranscriptionProvider(transcriptionState) {
                                        TimelinePane(
                                            uiState =
                                                TimelineUiState(
                                                    items = uiState.items,
                                                    loadingState = uiState.loadingState,
                                                    isLoadingMore = uiState.isLoadingMore,
                                                    hasMoreOlderContent = uiState.hasMoreOlderContent,
                                                    appendError = uiState.appendError,
                                                ),
                                            onNewEntry = onNewEntry,
                                            onOpenDay = onOpenDay,
                                            onVisibleAudioNoteIdsChanged = viewModel::updateVisibleAudioNoteIds,
                                            onLoadMoreOlder = viewModel::loadMoreOlder,
                                            onProfileClick = onOpenSettings,
                                            onSearchClick = onOpenSearch,
                                            onOpenDraft = onOpenDraft,
                                            onImportBackup = onImportBackup,
                                            timelineSuggestion = uiState.timelineSuggestion,
                                            statusActions = timelineStatusActions,
                                        )
                                    }
                                }
                            }

                            HomeRouteDestination.LocationHistory -> {
                                locationContent(
                                    Modifier
                                        .applyScreenStyles()
                                        .safeDrawingPadding(),
                                )
                            }

                            HomeRouteDestination.Journals -> {
                                JournalsOverviewScreen(
                                    onOpenJournal = onOpenJournal,
                                    onBrowseJournals = onBrowseJournals,
                                    onCreateJournal = onCreateJournal,
                                    modifier = Modifier.applyScreenStyles(),
                                )
                            }

                            HomeRouteDestination.Library -> {
                                libraryContent(Modifier.applyScreenStyles())
                            }

                            HomeRouteDestination.Rewind -> {
                                RewindOverviewScreen(
                                    onOpenRewind = onOpenRewind,
                                    modifier = Modifier.applyScreenStyles(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolves the SF Symbol painter shown for a home tab on Apple platforms. iOS tab bars
 * conventionally use a single glyph per tab with the system tint indicating selection, so
 * we don't switch between filled/outlined variants here — the tint color handles it.
 */
@Composable
private fun HomeRouteDestination.iosTabBarIcon(): Painter =
    when (this) {
        HomeRouteDestination.Timeline -> PlatformIcons.timeline()
        HomeRouteDestination.LocationHistory -> PlatformIcons.location()
        HomeRouteDestination.Journals -> PlatformIcons.journal()
        HomeRouteDestination.Library -> PlatformIcons.library()
        HomeRouteDestination.Rewind -> PlatformIcons.rewind()
    }
