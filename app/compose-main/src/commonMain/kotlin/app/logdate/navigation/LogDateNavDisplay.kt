@file:OptIn(ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.ui.LockableContent
import app.logdate.client.ui.navigation.DeepLinkAction
import app.logdate.client.ui.navigation.DeepLinkBus
import app.logdate.client.ui.navigation.LocationTimelineRoute
import app.logdate.client.ui.navigation.SearchRoute
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.account.navigation.CloudAccountSetupRoute
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.main.HomeScreen
import app.logdate.feature.core.main.rememberWatchPlaybackHandoff
import app.logdate.feature.core.navigation.BaseRoute
import app.logdate.feature.core.profile.navigation.ProfileRoute
import app.logdate.feature.core.profile.navigation.profileEntry
import app.logdate.feature.core.requiresUnlock
import app.logdate.feature.core.settings.navigation.BirthdaySettingsRoute
import app.logdate.feature.core.settings.navigation.ExportSettingsRoute
import app.logdate.feature.core.settings.navigation.RecoveryPhraseEntrySettingsRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.core.settings.navigation.StreakSettingsRoute
import app.logdate.feature.core.settings.navigation.SyncSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchNotificationSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchTroubleshootingRoute
import app.logdate.feature.core.settings.navigation.navigateToSettingsCategory
import app.logdate.feature.core.settings.navigation.settingsEntries
import app.logdate.feature.core.settings.navigation.watchEntries
import app.logdate.feature.core.sync.navigation.SyncIssuesRoute
import app.logdate.feature.core.sync.navigation.syncIssuesEntry
import app.logdate.feature.core.watch.continuity.watchHandoffEntries
import app.logdate.feature.editor.navigation.EditorLaunchMode
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.editor.navigation.RecordingEditorDestination
import app.logdate.feature.editor.navigation.completeEditorVisit
import app.logdate.feature.editor.navigation.editorEntry
import app.logdate.feature.events.navigation.EventDetailRoute
import app.logdate.feature.events.navigation.eventDetailEntry
import app.logdate.feature.journals.navigation.JournalContentPickerRoute
import app.logdate.feature.journals.navigation.JournalCreationRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.journals.navigation.JournalSettingsRoute
import app.logdate.feature.journals.navigation.JournalsOverviewRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.feature.journals.navigation.ShareJournalRoute
import app.logdate.feature.journals.navigation.completeJournalMerge
import app.logdate.feature.journals.navigation.journalEntries
import app.logdate.feature.journals.navigation.redirectJournalDetail
import app.logdate.feature.journals.navigation.replaceNoteDetail
import app.logdate.feature.journals.navigation.selectJournal
import app.logdate.feature.journals.navigation.selectJournalNote
import app.logdate.feature.library.navigation.MediaDetailRoute
import app.logdate.feature.library.navigation.libraryEntries
import app.logdate.feature.library.ui.LibraryScreen
import app.logdate.feature.location.timeline.ui.LocationTimelineScreen
import app.logdate.feature.onboarding.navigation.FirstEntryImportOffer
import app.logdate.feature.onboarding.navigation.OnboardingComplete
import app.logdate.feature.onboarding.navigation.OnboardingStart
import app.logdate.feature.onboarding.navigation.onboardingEntries
import app.logdate.feature.postcards.navigation.PostcardEditorRoute
import app.logdate.feature.postcards.navigation.PostcardViewerRoute
import app.logdate.feature.postcards.navigation.postcardsEntries
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.feature.rewind.navigation.rewindDetailEntry
import app.logdate.feature.search.ui.SearchScreen
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.audio.AudioPlaybackProvider
import app.logdate.ui.keyboard.AppKeyboardShortcut
import app.logdate.ui.keyboard.AppShortcutScope
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.platform.DefaultLogDateHaptics
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.platform.LocalPlatformHaptics
import app.logdate.ui.platform.iosEdgeSwipeBack
import app.logdate.ui.platform.rememberPlatformHapticsController
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.provenance.LocalActivityContextVisible
import app.logdate.ui.provenance.LocalWatchProvenanceVisible
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import io.github.aakira.napier.Napier
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject

/**
 * Multiplatform Navigation 3 root. Feature entry helpers and inline [taggedEntry]
 * registrations declare presentation and related-content metadata for [RelatedContentSceneStrategy].
 * Every concrete NavKey must be registered in [appNavSavedStateConfiguration] for restoration.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun LogDateNavDisplay(
    appUiState: GlobalAppUiLoadedState,
    onShowUnlockPrompt: () -> Unit,
    pendingNavKey: NavKey? = null,
    onPendingNavKeyConsumed: () -> Unit = {},
    onCurrentNavKeyChanged: (NavKey?) -> Unit = {},
    onShareSearchResult: ((SearchResult) -> Unit)? = null,
    onCloudSignIn: () -> Unit = {},
    sceneStrategy: SceneStrategy<NavKey> = rememberRelatedContentSceneStrategy(),
    shortcutRequests: AppShortcutRequests? = null,
) {
    val workspaceFlags: FeatureFlagStore = koinInject()
    val workspaceEnabled by remember(workspaceFlags) { workspaceFlags.observe(FeatureFlag.HOME_WORKSPACE_V2) }
        .collectAsState(initial = FeatureFlag.HOME_WORKSPACE_V2.defaultEnabled)
    val watchExperienceEnabled by remember(workspaceFlags) { workspaceFlags.observe(FeatureFlag.WATCH_EXPERIENCE_V2) }
        .collectAsState(initial = FeatureFlag.WATCH_EXPERIENCE_V2.defaultEnabled)
    val activityAwarenessEnabled by remember(workspaceFlags) { workspaceFlags.observe(FeatureFlag.ACTIVITY_AWARENESS) }
        .collectAsState(initial = FeatureFlag.ACTIVITY_AWARENESS.defaultEnabled)
    val backStack = rememberNavBackStack(appNavSavedStateConfiguration, BaseRoute)
    val placesEnabled by rememberPlacesBetaNavigation(workspaceFlags, backStack, appUiState.isOnboarded, appUiState.requiresUnlock)
    var hasRequestedUnlock by remember { mutableStateOf(false) }

    LaunchedEffect(appUiState.isOnboarded, appUiState.requiresUnlock) {
        val action =
            resolveNavBootstrapAction(
                backStack = backStack,
                isOnboarded = appUiState.isOnboarded,
                requiresUnlock = appUiState.requiresUnlock,
            )
        if (action is NavBootstrapAction.ResetTo) {
            val top = backStack.lastOrNull()
            if (action.key == OnboardingStart && top != null && top != BaseRoute) {
                Napier.w("Not onboarded; replacing restored back stack (top=$top) with onboarding")
            }
            backStack.clear()
            backStack.add(action.key)
        }
        if (!appUiState.isOnboarded) return@LaunchedEffect
        if (appUiState.requiresUnlock) {
            if (!hasRequestedUnlock) {
                hasRequestedUnlock = true
                onShowUnlockPrompt()
            }
            return@LaunchedEffect
        }
        hasRequestedUnlock = false
    }

    LaunchedEffect(pendingNavKey, placesEnabled, appUiState.isOnboarded, appUiState.requiresUnlock) {
        val target = pendingNavKey?.let { placesNavigationTarget(it, placesEnabled) } ?: return@LaunchedEffect
        if (!canOpenPendingDestination(target, appUiState.isOnboarded, appUiState.requiresUnlock)) return@LaunchedEffect
        when {
            target is EntryEditorRoute && target.resumeRecording -> RecordingEditorDestination.open(backStack, target)
            target is BlueskySignInRoute -> applyPendingDestination(backStack, target)
            else -> addPlacesSafeTarget(backStack, target)
        }
        onPendingNavKeyConsumed()
    }

    LaunchedEffect(backStack.lastOrNull()) {
        onCurrentNavKeyChanged(backStack.lastOrNull())
    }

    LaunchedEffect(backStack, appUiState.isOnboarded, appUiState.requiresUnlock) {
        if (!appUiState.isOnboarded || appUiState.requiresUnlock) return@LaunchedEffect
        DeepLinkBus.actions.collect { action ->
            when (action) {
                is DeepLinkAction.OpenJournal -> backStack.add(JournalDetailsRoute(action.id))
                is DeepLinkAction.OpenNote -> backStack.add(NoteDetailRoute(action.id))
                is DeepLinkAction.OpenRewind -> backStack.add(RewindDetailRoute(action.id))
                DeepLinkAction.OpenLocationTimeline -> openPlacesDeepLink(workspaceFlags, backStack)
            }
        }
    }

    val currentAppUiState by rememberUpdatedState(appUiState)
    // Opens an app-wide shortcut's screen, from its keys or from a request such as a menu item.
    val openShortcut: (AppKeyboardShortcut) -> Boolean =
        remember(backStack) {
            { shortcut ->
                val target = shortcut.appRoute()
                if (target == null || currentAppUiState.requiresUnlock || !currentAppUiState.isOnboarded) {
                    false
                } else {
                    applyPendingDestination(backStack, target)
                    true
                }
            }
        }
    LaunchedEffect(shortcutRequests) {
        shortcutRequests?.requests?.collect { openShortcut(it) }
    }

    val haptics = rememberPlatformHapticsController()
    val reduceMotion = rememberSystemReduceMotion()
    val logDateHaptics = remember(haptics) { DefaultLogDateHaptics(haptics) { reduceMotion.value } }
    LogDateTheme {
        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this,
                LocalWorkspaceEnabled provides workspaceEnabled,
                LocalWatchProvenanceVisible provides watchExperienceEnabled,
                LocalActivityContextVisible provides activityAwarenessEnabled,
                app.logdate.ui.workspace.LocalWorkspaceSearchAction provides { backStack.add(SearchRoute()) },
                LocalPlatformHaptics provides haptics,
                LocalLogDateHaptics provides logDateHaptics,
            ) {
                LockableContent(
                    isLocked = appUiState.requiresUnlock,
                    authState = appUiState.authState,
                    displayName = appUiState.displayName,
                    onUsePasscode = onShowUnlockPrompt,
                ) {
                    AudioPlaybackProvider(handoff = rememberWatchPlaybackHandoff()) {
                        NavDisplay(
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            sceneStrategies = listOf(rememberSettingsActivitySceneStrategy(), sceneStrategy),
                            modifier =
                                Modifier
                                    .iosEdgeSwipeBack(
                                        enabled = backStack.size > 1,
                                        onBack = { backStack.removeLastOrNull() },
                                    ).onPreviewKeyEvent { event ->
                                        AppKeyboardShortcut.match(event, AppShortcutScope.App)?.let(openShortcut) ?: false
                                    },
                            entryDecorators =
                                listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberPerVisitViewModelsDecorator(),
                                    rememberNavAnimatedVisibilityScopeEntryDecorator(),
                                    rememberWorkspaceRouteDecorator(),
                                ),
                            entryProvider =
                                entryProvider {
                                    taggedEntry<BaseRoute> { /* loading placeholder */ }
                                    taggedEntry<HomeRoute>(metadata = ActivityPresentation.Home.asMetadata()) {
                                        HomeScreen(
                                            onNewEntry = { backStack.add(EntryEditorRoute()) },
                                            onWriteMark = { backStack.add(EntryEditorRoute(markId = it.toString())) },
                                            onRecordVoice = { backStack.add(EntryEditorRoute(launchMode = EditorLaunchMode.Voice)) },
                                            onOpenJournal = { backStack.add(JournalDetailsRoute(it)) },
                                            onCreateJournal = { backStack.add(JournalCreationRoute()) },
                                            onBrowseJournals = { backStack.add(JournalsOverviewRoute) },
                                            onOpenRewind = { backStack.add(RewindDetailRoute(it)) },
                                            onOpenSettings = { backStack.add(SettingsRoute()) },
                                            onOpenSearch = { backStack.add(SearchRoute()) },
                                            onOpenDraft = { draftId ->
                                                backStack.add(EntryEditorRoute(draftId = draftId))
                                            },
                                            onImportBackup = { backStack.add(ExportSettingsRoute) },
                                            onOpenMediaDetail = { backStack.add(MediaDetailRoute(it)) },
                                            onOpenSyncSettings = { backStack.add(SyncSettingsRoute) },
                                            onEnterRecoveryPhrase = { backStack.add(RecoveryPhraseEntrySettingsRoute) },
                                            onOpenWatch = { backStack.add(WatchSettingsRoute) },
                                            onOpenSyncIssues = { backStack.add(SyncIssuesRoute) },
                                            onOpenDay = { date -> backStack.add(TimelineDetailRoute(date.toString())) },
                                            onOpenTextNote = { backStack.add(NoteDetailRoute(it)) },
                                            onOpenStreak = { backStack.add(StreakSettingsRoute) },
                                            libraryContent = { modifier ->
                                                LibraryScreen(
                                                    onOpenMediaDetail = { backStack.add(MediaDetailRoute(it)) },
                                                    onOpenSearch = {
                                                        backStack.add(
                                                            SearchRoute(
                                                                typeFtsValues =
                                                                    listOf(
                                                                        SearchContentType.MEDIA_CAPTION.ftsValue,
                                                                        SearchContentType.TRANSCRIPTION.ftsValue,
                                                                    ),
                                                            ),
                                                        )
                                                    },
                                                    modifier = modifier,
                                                )
                                            },
                                            locationContent = { modifier ->
                                                LocationTimelineScreen(
                                                    onAddMemory = { backStack.add(EntryEditorRoute(visitContext = it)) },
                                                    onOpenNote = { backStack.add(NoteDetailRoute(it)) },
                                                    modifier = modifier,
                                                    embeddedInHome = true,
                                                )
                                            },
                                        )
                                    }
                                    syncIssuesEntry(onBack = { backStack.removeLastOrNull() })
                                    journalEntries(
                                        onOpenJournal = { backStack.selectJournal(it) },
                                        onCreateJournal = { backStack.add(JournalCreationRoute()) },
                                        onBack = { backStack.removeLastOrNull() },
                                        onJournalDeleted = {
                                            backStack.clear()
                                            backStack.add(JournalsOverviewRoute)
                                        },
                                        onOpenNote = { noteId, journalId -> backStack.selectJournalNote(noteId, journalId) },
                                        onNavigateToNote = { noteId, journalId -> backStack.replaceNoteDetail(noteId, journalId) },
                                        onEditNote = { backStack.add(EntryEditorRoute(entryId = it.toString())) },
                                        onOpenEditorForJournal = { journalId ->
                                            backStack.add(EntryEditorRoute(journalIds = listOf(journalId.toString())))
                                        },
                                        onOpenContentPickerForJournal = { journalId ->
                                            backStack.add(JournalContentPickerRoute(journalId))
                                        },
                                        onOpenJournalSettings = { backStack.add(JournalSettingsRoute(it)) },
                                        onShareJournal = { backStack.add(ShareJournalRoute(it)) },
                                        onMergeJournal = { backStack.add(JournalMergeRoute(it.toString())) },
                                        onJournalMerged = { backStack.completeJournalMerge(it) },
                                        onJournalRedirected = { sourceId, destinationId ->
                                            backStack.redirectJournalDetail(sourceId, destinationId)
                                        },
                                    )
                                    libraryEntries(
                                        onOpenMediaDetail = { backStack.add(MediaDetailRoute(it)) },
                                        onBack = { backStack.removeLastOrNull() },
                                    )
                                    editorEntry(
                                        onNavigateBack = { backStack.removeLastOrNull() },
                                        onEntrySaved = { saved ->
                                            backStack.completeEditorVisit(
                                                saved,
                                                if (appUiState.isOnboarded) HomeRoute else OnboardingComplete,
                                            )
                                        },
                                        metadata = editorRouteTransitionMetadata,
                                    )
                                    if (FeatureFlag.EVENTS.availableForLaunch) {
                                        eventDetailEntry(onGoBack = { backStack.removeLastOrNull() })
                                    } else {
                                        taggedEntry<EventDetailRoute> {
                                            LaunchedEffect(Unit) { backStack.removeLastOrNull() }
                                        }
                                    }
                                    profileEntry(
                                        onBack = { backStack.removeLastOrNull() },
                                        onNavigateToBirthday = { backStack.add(BirthdaySettingsRoute) },
                                    )
                                    rewindDetailEntry(onExitRewind = { backStack.removeLastOrNull() })
                                    settingsEntries(
                                        onBack = { backStack.removeLastOrNull() },
                                        onResetApp = {
                                            backStack.clear()
                                            backStack.add(BaseRoute)
                                        },
                                        onNavigateToProfile = { backStack.add(ProfileRoute) },
                                        onNavigateToCloudAccountCreation = {
                                            backStack.add(CloudAccountSetupRoute(startOnSignIn = false))
                                        },
                                        onNavigateToSignIn = {
                                            backStack.add(CloudAccountSetupRoute(startOnSignIn = true))
                                        },
                                        onNavigateToEvent = { backStack.add(EventDetailRoute(it.toString())) },
                                        onNavigateToWatch =
                                            if (isWatchSupported) {
                                                { backStack.add(WatchSettingsRoute) }
                                            } else {
                                                null
                                            },
                                        onNavigateTo = { route -> backStack.add(route) },
                                        onNavigateToCategory = { backStack.navigateToSettingsCategory(it) },
                                    )
                                    if (isWatchSupported) {
                                        watchEntries(
                                            onNavigateToDetail = { backStack.add(it) },
                                            onNavigateToCategory = { backStack.navigateToSettingsCategory(it) },
                                            onNavigateToProfile = { backStack.add(ProfileRoute) },
                                            onBack = { backStack.removeLastOrNull() },
                                            onNavigateToNotifications = { backStack.add(WatchNotificationSettingsRoute) },
                                            onNavigateToTroubleshooting = { backStack.add(WatchTroubleshootingRoute) },
                                        )
                                    }
                                    watchHandoffEntries(
                                        onOpenDay = { day ->
                                            backStack.removeLastOrNull()
                                            backStack.add(TimelineDetailRoute(day.toString()))
                                        },
                                        onOpenEditor = { id ->
                                            backStack.removeLastOrNull()
                                            backStack.add(EntryEditorRoute(entryId = id))
                                        },
                                        onOpenPlayback = { id ->
                                            backStack.removeLastOrNull()
                                            backStack.add(NoteDetailRoute(id))
                                        },
                                        onBack = { backStack.removeLastOrNull() },
                                    )
                                    accountSetupEntries(backStack, appUiState.isOnboarded)
                                    onboardingEntries(
                                        onContinueWithBluesky = { backStack.add(BlueskySignInRoute()) },
                                        onNavigateBack = { backStack.removeLastOrNull() },
                                        onWelcomeBack = {
                                            onCloudSignIn()
                                            backStack.clear()
                                            backStack.add(HomeRoute)
                                        },
                                        onOnboardingComplete = {
                                            backStack.clear()
                                            backStack.add(HomeRoute)
                                        },
                                        onFirstEntryReady = {
                                            backStack.clear()
                                            backStack.add(FirstEntryImportOffer)
                                        },
                                        onCreateFirstEntry = { backStack.add(EntryEditorRoute(firstEntry = true)) },
                                        onGoToItem = { route -> backStack.add(route) },
                                    )
                                    postcardsEntries(
                                        onOpenPostcard = { backStack.add(PostcardViewerRoute(it)) },
                                        onEditPostcard = { backStack.add(PostcardEditorRoute(it)) },
                                        onCreateNew = { backStack.add(PostcardEditorRoute()) },
                                        onNavigateBack = { backStack.removeLastOrNull() },
                                        onSaved = { backStack.removeLastOrNull() },
                                    )
                                    // Search
                                    taggedEntry<SearchRoute> { route ->
                                        SearchScreen(
                                            onGoBack = { backStack.removeLastOrNull() },
                                            onResultClick = { result -> backStack.add(searchResultRoute(result)) },
                                            onResultOpenDay = { result -> backStack.add(searchResultDayRoute(result)) },
                                            onShareResult = onShareSearchResult,
                                            initialQuery = route.query,
                                            initialTypeFtsValues = route.typeFtsValues,
                                            initialDateRangeName = route.dateRangeName,
                                        )
                                    }
                                    // Location timeline (top-level)
                                    taggedEntry<LocationTimelineRoute> {
                                        if (placesEnabled == true) {
                                            LocationTimelineScreen(
                                                onAddMemory = { backStack.add(EntryEditorRoute(visitContext = it)) },
                                                onOpenNote = { backStack.add(NoteDetailRoute(it)) },
                                            )
                                        }
                                    }
                                    // Timeline per-day detail
                                    taggedEntry<TimelineDetailRoute>(
                                        metadata = ActivityPresentation.Detail("timeline").asMetadata(),
                                    ) { route ->
                                        TimelineDetailEntry(
                                            date = LocalDate.parse(route.dateIso),
                                            scrollToEntryId = route.entryId,
                                            onClose = { backStack.removeLastOrNull() },
                                            onOpenLocations = placesLocationAction(placesEnabled, backStack),
                                            onOpenEvent = { backStack.add(EventDetailRoute(it)) },
                                            onOpenNote = { backStack.add(NoteDetailRoute(it)) },
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> rememberNavAnimatedVisibilityScopeEntryDecorator(): NavEntryDecorator<T> =
    remember {
        NavEntryDecorator { entry ->
            CompositionLocalProvider(
                LocalNavAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current,
            ) {
                entry.Content()
            }
        }
    }
