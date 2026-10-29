package app.logdate.client

import android.annotation.SuppressLint
import android.app.HandoffActivityData
import android.app.HandoffActivityDataRequestInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import app.logdate.client.activity.PhoneActivityAwareness
import app.logdate.client.database.DatabaseStartupMonitor
import app.logdate.client.database.DatabaseStartupState
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.retainedJournalWorkspace
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.launch.LaunchBootstrapState
import app.logdate.client.launch.LaunchStage
import app.logdate.client.launch.LaunchStageTracker
import app.logdate.client.launch.whenApplicationReady
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.media.audio.EXTRA_NAV_SOURCE
import app.logdate.client.media.audio.NAV_SOURCE_AUDIO_RECORDING
import app.logdate.client.repository.account.AtprotoConnectionRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.testing.onboarding.DEBUG_SKIP_ONBOARDING_EXTRA
import app.logdate.client.testing.onboarding.ONBOARDING_TEST_FIXTURE_EXTRA
import app.logdate.client.testing.onboarding.OnboardingTestFixtureApplier
import app.logdate.client.testing.onboarding.readOnboardingTestFixture
import app.logdate.client.tiles.EXTRA_SHOW_RECORDING
import app.logdate.client.tiles.RecordingWindowTracker
import app.logdate.client.updates.PlayInAppUpdateController
import app.logdate.client.watch.WatchCompanionAssociationManager
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.GlobalAppUiLoadingState
import app.logdate.feature.core.GlobalAppUiState
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.di.ActivityProvider
import app.logdate.feature.core.export.AndroidExportLauncher
import app.logdate.feature.core.export.CloudBackupScheduler
import app.logdate.feature.core.isAppUnlocked
import app.logdate.feature.core.restore.AndroidRestoreLauncher
import app.logdate.feature.core.settings.account.signin.AndroidAccountConnection
import app.logdate.feature.core.settings.account.signin.LocalAccountConnectionProvider
import app.logdate.feature.core.settings.navigation.DataSettingsRoute
import app.logdate.feature.core.settings.navigation.SignInMethodsRoute
import app.logdate.feature.core.settings.updates.AppUpdateCheckTrigger
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.onboarding.flow.OnboardingDeviceStateRepository
import app.logdate.navigation.AccountBrowserReturn
import app.logdate.navigation.LogDateNavDisplay
import app.logdate.navigation.resolveAccountBrowserReturn
import app.logdate.ui.common.HomeScreenPins
import app.logdate.ui.common.LocalEditorWindows
import app.logdate.ui.common.LocalHomeScreenPins
import app.logdate.ui.keyboard.AppShortcutScope
import app.logdate.ui.keyboard.appKeyboardShortcutGroups
import app.logdate.ui.platform.enableTransparentSystemBars
import io.github.aakira.napier.Napier
import io.github.vinceglb.filekit.core.FileKit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * The main app activity.
 *
 * Bridges Android lifecycle and platform launchers (export, restore, app updates, watch
 * association, biometric gatekeeper, multi-window editor windows, deep links, and the
 * Android 16+ handoff API) into the shared [LogDateNavDisplay] graph.
 *
 * Each concern lives in its own `MainActivity<Concern>.kt` file; `onCreate` only sequences them.
 */
class MainActivity : FragmentActivity() {
    private val biometricGatekeeper = inject<BiometricGatekeeper>()
    private val activityProviderDependency = inject<ActivityProvider>()
    private val activityProvider by activityProviderDependency
    private val androidExportLauncher = inject<AndroidExportLauncher>()
    private val androidRestoreLauncher = inject<AndroidRestoreLauncher>()
    private val databaseStartupMonitor: DatabaseStartupMonitor by inject()
    private val homeScreenPins: HomeScreenPins by inject()
    private val recorder: AudioRecordingManager by inject()
    private val recordingWindows: RecordingWindowTracker by inject()
    private val postRestoreDetector: PostRestoreDetector by inject()
    private val playInAppUpdateControllerDependency = inject<PlayInAppUpdateController>()
    private val playInAppUpdateController by playInAppUpdateControllerDependency
    private val watchCompanionAssociationManager = inject<WatchCompanionAssociationManager>()
    private val locationTrackingManager: LocationTrackingManager by inject()
    private val activityAwareness: PhoneActivityAwareness by inject()
    private val profileRepository: ProfileRepository by inject()
    private val userStateRepository: UserStateRepository by inject()
    private val sessionStorage: SessionStorage by inject()
    private val cloudBackupScheduler: CloudBackupScheduler by inject()
    private val memoriesSettingsRepository: MemoriesSettingsRepository by inject()
    private val locationTrackingSettingsRepository: LocationTrackingSettingsRepository by inject()
    private val dayBoundarySettingsRepository: DayBoundarySettingsRepository by inject()
    private val onboardingDeviceStateRepository: OnboardingDeviceStateRepository by inject()

    private val viewModel by viewModel<AppViewModel>()
    private val accountConnection: AtprotoConnectionRepository by inject()

    private var bootstrapReady = false
    private var startupPendingIntent: Intent? = null
    private var accountReturnGeneration = 0L
    private var appUiState by mutableStateOf<GlobalAppUiState>(GlobalAppUiLoadingState)
    private var pendingNavKey by mutableStateOf<NavKey?>(null)
    private var currentNavKey by mutableStateOf<NavKey?>(null)
    private var databaseStartupState by mutableStateOf<DatabaseStartupState>(DatabaseStartupState.Initializing)
    private val launchTracker = LaunchStageTracker()
    private var hasCheckedForAppUpdates by mutableStateOf(false)

    private val postRestoreCoordinator by lazy {
        PostRestoreCoordinator(postRestoreDetector, viewModel)
    }

    private val launchers =
        MainActivityLaunchers(
            activity = this,
            biometricGatekeeper = biometricGatekeeper,
            activityProvider = activityProviderDependency,
            androidExportLauncher = androidExportLauncher,
            androidRestoreLauncher = androidRestoreLauncher,
            playInAppUpdateController = playInAppUpdateControllerDependency,
            watchCompanionAssociationManager = watchCompanionAssociationManager,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        Napier.i("MainActivity onCreate: starting launch", tag = APP_LAUNCH_TAG)
        markLaunchStage(LaunchStage.ActivityCreated)
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableTransparentSystemBars()
        lifecycleScope.startLaunchWatchdog(launchTracker)
        splashScreen.setKeepOnScreenCondition {
            launchTracker.bootstrapState is LaunchBootstrapState.BlockingSplash
        }
        whenApplicationReady {
            bootstrapReady = true
            completeStartup(savedInstanceState)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeReadyActivity()
            startupPendingIntent?.let { pending ->
                startupPendingIntent = null
                onNewIntent(pending)
            }
        }
    }

    private fun routeLaunchIntent(
        savedInstanceState: Bundle?,
        hasPendingRetainedJournalRecovery: Boolean,
    ) {
        // A recreated activity receives the launch intent again; resolving it a second time
        // would overwrite the Navigation 3 back stack restored across rotation.
        if (savedInstanceState == null) {
            val recordingWindowForwarded = showRecordingWindowIfAsked(intent)
            if (recordingWindowForwarded) {
                pendingNavKey = null
                routeAccountAwareIntent(intent, restoreOnly = true)
            } else if (hasPendingRetainedJournalRecovery) {
                pendingNavKey = DataSettingsRoute
                routeAccountAwareIntent(intent, restoreOnly = true)
            } else {
                routeAccountAwareIntent(intent, coldStart = true)
            }
        } else {
            // Resume only a persisted connection; ordinary sign-in keeps its restored Navigation 3 entry.
            routeAccountAwareIntent(intent, restoreOnly = true)
        }
    }

    private fun completeStartup(savedInstanceState: Bundle?) {
        // Enqueue only network-constrained work. CloudBackupWorker checks SessionStorage and
        // exits without touching the network when the user is signed out or offline.
        cloudBackupScheduler.schedulePeriodicBackup()
        if (savedInstanceState == null) {
            applyOnboardingTestFixtureFromLaunchIntent()
        }

        FileKit.init(this)
        Napier.i("MainActivity onCreate: FileKit initialized", tag = APP_LAUNCH_TAG)

        launchers.attachPlatformLaunchers()
        setupMultiWindowSupport()
        Napier.i("MainActivity onCreate: multi-window support configured", tag = APP_LAUNCH_TAG)

        observeAppAndDatabaseState()
        enableHandoffIfSupported()
        val hasPendingRetainedJournalRecovery =
            retainedJournalRecoveryDestination(
                hasRetainedJournal = retainedJournalWorkspace(this).current() != null,
                pendingContentRecoveryId = postRestoreDetector.pendingContentRecoveryId(),
            ) != null
        routeLaunchIntent(savedInstanceState, hasPendingRetainedJournalRecovery)

        setContent {
            val state = appUiState as? GlobalAppUiLoadedState
            if (state != null) {
                CompositionLocalProvider(
                    LocalEditorWindows provides remember { editorWindows() },
                    LocalHomeScreenPins provides homeScreenPins,
                    LocalAccountConnectionProvider provides { AndroidAccountConnection() },
                ) {
                    MainActivityContent(
                        state = state,
                        pendingNavKey = pendingNavKey,
                        hasPendingRetainedJournalRecovery = hasPendingRetainedJournalRecovery,
                        onPendingNavKeyConsumed = { pendingNavKey = null },
                        onCurrentNavKeyChanged = { currentNavKey = it },
                        onShowUnlockPrompt = viewModel::showNativeUnlockPrompt,
                        onAutomaticUnlockPrompt = viewModel::showAutomaticUnlockPrompt,
                        onCloudSignIn = viewModel::requestCloudRestoreAfterSignIn,
                        onShareSearchResult = ::shareSearchResult,
                        updateController = playInAppUpdateController,
                        onLaunchUpdate = { checkForUpdates(AppUpdateCheckTrigger.Manual) },
                        onCompleteUpdate = { lifecycleScope.launch { playInAppUpdateController.completeUpdate() } },
                    )
                }
            }
        }
        markLaunchStage(LaunchStage.ComposeAttached)
        Napier.i("MainActivity onCreate: Compose content attached", tag = APP_LAUNCH_TAG)

        if (savedInstanceState == null && intent?.let { handleMultiWindowIntent(it) } == true) {
            return
        }
    }

    private fun observeAppAndDatabaseState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState
                        .combine(databaseStartupMonitor.state) { ui, database -> ui to database }
                        .onEach { (state, database) ->
                            databaseStartupState = database
                            appUiState = state
                            if (state is GlobalAppUiLoadedState) {
                                markLaunchStage(LaunchStage.AppUiLoaded)
                                detectPostRestoreOnce(state)
                            }
                            maybeCheckForUpdates(state)
                        }.collect {}
                }
                launch {
                    databaseStartupMonitor.state
                        .onEach { state ->
                            databaseStartupState = state
                            markLaunchStage(LaunchStage.DatabaseStateObserved)
                            if (state is DatabaseStartupState.RecoveryRequired) {
                                Napier.w(
                                    "MainActivity launch gate: recovery required (${state.reason})",
                                    tag = APP_LAUNCH_TAG,
                                )
                            } else {
                                Napier.i("MainActivity launch gate: database state ready", tag = APP_LAUNCH_TAG)
                            }
                        }.collect {}
                }
            }
        }
    }

    private fun applyOnboardingTestFixtureFromLaunchIntent() {
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val fixture = intent?.readOnboardingTestFixture(isDebuggable) ?: return
        runBlocking {
            OnboardingTestFixtureApplier(
                profileRepository = profileRepository,
                userStateRepository = userStateRepository,
                sessionStorage = sessionStorage,
                memoriesSettingsRepository = memoriesSettingsRepository,
                locationTrackingSettingsRepository = locationTrackingSettingsRepository,
                dayBoundarySettingsRepository = dayBoundarySettingsRepository,
                onboardingDeviceStateRepository = onboardingDeviceStateRepository,
            ).apply(fixture)
        }
        intent?.removeExtra(ONBOARDING_TEST_FIXTURE_EXTRA)
        intent?.removeExtra(DEBUG_SKIP_ONBOARDING_EXTRA)
        Napier.i("Applied onboarding test fixture from launch intent", tag = APP_LAUNCH_TAG)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        accountReturnGeneration += 1
        if (!bootstrapReady) {
            startupPendingIntent = intent
            return
        }
        if (handleMultiWindowIntent(intent)) {
            return
        }
        if (showRecordingWindowIfAsked(intent)) return
        routeAccountAwareIntent(intent)
    }

    private fun routeAccountAwareIntent(
        intent: Intent?,
        coldStart: Boolean = false,
        restoreOnly: Boolean = false,
    ) {
        val generation = ++accountReturnGeneration
        val target = resolveMainActivityNavKey(intent)
        if (target !is BlueskySignInRoute || target.attemptId == null) {
            val recordingReturn = intent?.action == Intent.ACTION_MAIN || intent?.getBooleanExtra(EXTRA_SHOW_RECORDING, false) == true
            val destination =
                when {
                    coldStart -> target ?: activeRecordingDestination()
                    recordingReturn -> activeRecordingDestination() ?: target
                    else -> target
                }
            if (!restoreOnly && destination != null) pendingNavKey = destination
            return
        }
        lifecycleScope.launch {
            val resolved = resolveAccountBrowserReturn(intent?.dataString, accountConnection::resume)
            if (generation != accountReturnGeneration || restoreOnly) return@launch
            when (resolved) {
                AccountBrowserReturn.CONNECTION -> if (coldStart) pendingNavKey = SignInMethodsRoute
                AccountBrowserReturn.UNAVAILABLE -> pendingNavKey = SignInMethodsRoute
                AccountBrowserReturn.ORDINARY_SIGN_IN, AccountBrowserReturn.NOT_A_RETURN -> pendingNavKey = target
            }
        }
    }

    /**
     * Brings forward the window a voice recording runs in, when the Record voice tile asked for it.
     * A recording in this window needs nothing more, since this window is already in front.
     */
    private fun showRecordingWindowIfAsked(intent: Intent?): Boolean {
        val recordingTap = intent?.getStringExtra(EXTRA_NAV_SOURCE) == NAV_SOURCE_AUDIO_RECORDING
        val launcherReturn = intent?.action == Intent.ACTION_MAIN && activeRecordingDestination() != null
        if (intent?.getBooleanExtra(EXTRA_SHOW_RECORDING, false) != true && !recordingTap && !launcherReturn) return false
        return recordingWindows.bringRecordingWindowForward(this)
    }

    private fun activeRecordingDestination(): EntryEditorRoute? =
        if (recorder.isRecording || recorder.isStartingRecording) {
            recorder.currentRecordingDraftId?.let { EntryEditorRoute(draftId = it.toString(), resumeRecording = true) }
        } else {
            null
        }

    @SuppressLint("NewApi")
    override fun onHandoffActivityDataRequested(info: HandoffActivityDataRequestInfo): HandoffActivityData? {
        val url = currentNavKey?.toWebUrl() ?: return null
        return HandoffActivityData
            .Builder(ComponentName(this, MainActivity::class.java))
            .setFallbackUri(Uri.parse(url))
            .build()
    }

    override fun onResume() {
        super.onResume()
        if (bootstrapReady) resumeReadyActivity()
    }

    private fun resumeReadyActivity() {
        viewModel.onAppForegrounded()
        locationTrackingManager.onActivityResumed()
        activityAwareness.onActivityResumed()
        activityProvider.currentActivity = this
        lifecycleScope.launch {
            playInAppUpdateController.resumeIfUpdateInProgress()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!bootstrapReady) return
        locationTrackingManager.onActivityPaused()
        if (!isChangingConfigurations) {
            viewModel.onAppPaused()
        }
        if (activityProvider.currentActivity === this) {
            activityProvider.currentActivity = null
        }
    }

    private fun detectPostRestoreOnce(state: GlobalAppUiLoadedState) {
        postRestoreCoordinator.detectPostRestoreOnce(state, databaseStartupState)
    }

    private fun maybeCheckForUpdates(state: GlobalAppUiState) {
        val loadedState = state as? GlobalAppUiLoadedState ?: return
        if (hasCheckedForAppUpdates) return
        if (!loadedState.isOnboarded || !loadedState.isAppUnlocked) return

        hasCheckedForAppUpdates = true
        checkForUpdates(AppUpdateCheckTrigger.Automatic)
    }

    private fun checkForUpdates(trigger: AppUpdateCheckTrigger) {
        lifecycleScope.launch {
            playInAppUpdateController.checkForUpdates(trigger)
        }
    }

    private fun markLaunchStage(stage: LaunchStage) {
        if (!launchTracker.mark(stage)) return
        Napier.i("MainActivity launch stage: ${stage.analyticsName}", tag = APP_LAUNCH_TAG)
    }

    /** Lists LogDate's keyboard shortcuts in Android's shortcut helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += appKeyboardShortcutGroups(listOf(AppShortcutScope.App, AppShortcutScope.Home, AppShortcutScope.Editor))
    }
}

internal fun retainedJournalRecoveryDestination(
    hasRetainedJournal: Boolean,
    pendingContentRecoveryId: String?,
): NavKey? =
    if (hasRetainedJournal && pendingContentRecoveryId != null) {
        DataSettingsRoute
    } else {
        null
    }

internal const val APP_LAUNCH_TAG = "LogDateAppLaunch"
