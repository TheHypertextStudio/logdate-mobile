package app.logdate.client.updates

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.feature.core.settings.updates.AppUpdateCheckTrigger
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.core.settings.updates.AppUpdateFlowType
import app.logdate.feature.core.settings.updates.AppUpdatePromptContext
import app.logdate.feature.core.settings.updates.AppUpdatePromptPolicy
import app.logdate.feature.core.settings.updates.AppUpdateStatus
import app.logdate.feature.core.settings.updates.AppUpdateUiState
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.app_update_check_failed
import logdate.client.feature.core.generated.resources.app_update_downloading
import logdate.client.feature.core.generated.resources.app_update_immediate_required
import logdate.client.feature.core.generated.resources.app_update_prompt_available
import logdate.client.feature.core.generated.resources.app_update_status_canceled
import logdate.client.feature.core.generated.resources.app_update_status_checking
import logdate.client.feature.core.generated.resources.app_update_status_finish_failed
import logdate.client.feature.core.generated.resources.app_update_status_finishing
import logdate.client.feature.core.generated.resources.app_update_status_flow_not_ready
import logdate.client.feature.core.generated.resources.app_update_status_launch_failed
import logdate.client.feature.core.generated.resources.app_update_status_ready_to_install
import logdate.client.feature.core.generated.resources.app_update_unsupported
import logdate.client.feature.core.generated.resources.app_update_up_to_date
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Android implementation of [AppUpdateController] backed by Google Play Core.
 *
 * Responsibilities:
 * - decide whether a release should use the flexible or immediate flow,
 * - launch Play's update UI through an attached Activity Result launcher,
 * - keep shared UI state in sync with Play install-state callbacks,
 * - defer repeated automatic flexible prompts for a short cooldown window.
 */
class PlayInAppUpdateController(
    context: Context,
    appInfoProvider: AppInfoProvider,
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context),
    private val clock: Clock = Clock.System,
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) : AppUpdateController {
    private val appInfo = appInfoProvider.getAppInfo()

    // Prevent concurrent checks from racing each other or overlapping with resume handling.
    private val checkMutex = Mutex()

    // Keep the shared UI state aligned with Play's background download/install lifecycle.
    private val installStateListener =
        InstallStateUpdatedListener { state ->
            when (state.installStatus()) {
                InstallStatus.DOWNLOADED -> {
                    updateUiState(
                        status = AppUpdateStatus.Downloaded,
                        message = Res.string.app_update_status_ready_to_install.toUiText(),
                    )
                }

                InstallStatus.DOWNLOADING -> {
                    updateUiState(
                        status = AppUpdateStatus.Downloading,
                        message = Res.string.app_update_downloading.toUiText(),
                    )
                }

                InstallStatus.INSTALLED -> {
                    clearDeferredFlexiblePrompt()
                    updateUiState(
                        status = AppUpdateStatus.UpToDate,
                        message = Res.string.app_update_up_to_date.toUiText(),
                    )
                }

                InstallStatus.FAILED,
                InstallStatus.CANCELED,
                -> {
                    Napier.w("Play in-app update install state ended unexpectedly: ${state.installStatus()}")
                }

                else -> {
                    Unit
                }
            }
        }
    private val _uiState =
        MutableStateFlow(
            AppUpdateUiState(
                currentVersionName = appInfo.versionName,
                currentVersionCode = appInfo.versionCode,
            ),
        )

    /** Shared app-update state consumed by settings and root-level Android UI. */
    override val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    private var launcher: AppUpdateFlowLauncher? = null
    private var activeFlowType: AppUpdateFlowType? = null
    private var lastCheckTrigger: AppUpdateCheckTrigger = AppUpdateCheckTrigger.Automatic

    init {
        appUpdateManager.registerListener(installStateListener)
    }

    /**
     * Attaches the launcher that will be used to hand control to Google Play.
     *
     * MainActivity owns the real Activity Result launcher and provides it once the
     * activity is ready.
     */
    fun attachLauncher(launcher: AppUpdateFlowLauncher) {
        this.launcher = launcher
    }

    /**
     * Receives the result of the Play update UI flow.
     *
     * Flexible cancellations start the cooldown used to suppress repeated automatic prompts.
     */
    fun onUpdateFlowResult(resultCode: Int) {
        val flowType = activeFlowType ?: return
        val trigger = lastCheckTrigger
        activeFlowType = null

        if (resultCode == Activity.RESULT_OK) {
            if (flowType == AppUpdateFlowType.Flexible) {
                updateUiState(
                    status = AppUpdateStatus.Downloading,
                    message = Res.string.app_update_downloading.toUiText(),
                )
            }
            return
        }

        if (flowType == AppUpdateFlowType.Flexible) {
            deferFlexiblePrompt()
        }

        if (trigger == AppUpdateCheckTrigger.Manual) {
            updateUiState(
                status = AppUpdateStatus.Error,
                message = Res.string.app_update_status_canceled.toUiText(),
            )
        } else {
            Napier.i("Play in-app update flow canceled for $flowType")
        }
    }

    /**
     * Re-enters an immediate update flow when Play reports that one was already in progress.
     *
     * This is called from MainActivity's resume path so configuration changes or app
     * backgrounding do not strand the user in a half-started immediate update.
     */
    suspend fun resumeIfUpdateInProgress() {
        checkMutex.withLock {
            try {
                val appUpdateInfo = appUpdateManager.appUpdateInfo.await()
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    updateUiState(
                        status = AppUpdateStatus.Downloaded,
                        message = Res.string.app_update_status_ready_to_install.toUiText(),
                    )
                    return
                }

                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    launchUpdateFlow(
                        appUpdateInfo = appUpdateInfo,
                        flowType = AppUpdateFlowType.Immediate,
                        trigger = AppUpdateCheckTrigger.Automatic,
                    )
                }
            } catch (exception: Exception) {
                Napier.e("Failed to resume Play in-app update flow", exception)
            }
        }
    }

    /**
     * Queries Play for the latest update state and launches the selected flow when appropriate.
     */
    override suspend fun checkForUpdates(trigger: AppUpdateCheckTrigger) {
        checkMutex.withLock {
            lastCheckTrigger = trigger
            updateUiState(status = AppUpdateStatus.Checking, message = Res.string.app_update_status_checking.toUiText())

            try {
                val appUpdateInfo = appUpdateManager.appUpdateInfo.await()
                val now = clock.now()

                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    updateUiState(
                        status = AppUpdateStatus.Downloaded,
                        message = Res.string.app_update_status_ready_to_install.toUiText(),
                        lastCheckedAt = now,
                    )
                    return
                }

                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    launchUpdateFlow(
                        appUpdateInfo = appUpdateInfo,
                        flowType = AppUpdateFlowType.Immediate,
                        trigger = trigger,
                    )
                    return
                }

                if (appUpdateInfo.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                    updateUiState(
                        status = AppUpdateStatus.UpToDate,
                        message = Res.string.app_update_up_to_date.toUiText(),
                        lastCheckedAt = now,
                    )
                    return
                }

                val flowType =
                    AppUpdatePromptPolicy.chooseFlow(
                        context =
                            AppUpdatePromptContext(
                                updatePriority = appUpdateInfo.updatePriority(),
                                stalenessDays = appUpdateInfo.clientVersionStalenessDays(),
                                immediateAllowed = appUpdateInfo.isUpdateTypeAllowed(immediateOptions()),
                                flexibleAllowed = appUpdateInfo.isUpdateTypeAllowed(flexibleOptions()),
                                isFlexiblePromptDeferred =
                                    trigger == AppUpdateCheckTrigger.Automatic &&
                                        isFlexiblePromptDeferred(appUpdateInfo.availableVersionCode()),
                            ),
                        trigger = trigger,
                    )

                if (flowType == null) {
                    updateUiState(
                        status = AppUpdateStatus.UpToDate,
                        message = Res.string.app_update_up_to_date.toUiText(),
                        lastCheckedAt = now,
                    )
                    return
                }

                launchUpdateFlow(
                    appUpdateInfo = appUpdateInfo,
                    flowType = flowType,
                    trigger = trigger,
                )
            } catch (exception: InstallException) {
                handleInstallException(exception, trigger)
            } catch (exception: Exception) {
                Napier.e("Failed to check for Play in-app updates", exception)
                if (trigger == AppUpdateCheckTrigger.Manual) {
                    updateUiState(
                        status = AppUpdateStatus.Error,
                        message = Res.string.app_update_check_failed.toUiText(),
                    )
                } else {
                    updateUiState(status = AppUpdateStatus.Idle)
                }
            }
        }
    }

    /**
     * Completes a downloaded flexible update.
     *
     * Play performs the install after this call succeeds, which normally restarts the app.
     */
    override suspend fun completeUpdate() {
        try {
            updateUiState(
                status = AppUpdateStatus.Downloading,
                message = Res.string.app_update_status_finishing.toUiText(),
            )
            appUpdateManager.completeUpdate().await()
        } catch (exception: Exception) {
            Napier.e("Failed to complete Play in-app update", exception)
            updateUiState(
                status = AppUpdateStatus.Error,
                message = Res.string.app_update_status_finish_failed.toUiText(),
            )
        }
    }

    /**
     * Converts Play install exceptions into user-facing unsupported/error states.
     */
    private fun handleInstallException(
        exception: InstallException,
        trigger: AppUpdateCheckTrigger,
    ) {
        Napier.e("Play in-app updates are unavailable", exception)

        if (exception.errorCode in unsupportedErrorCodes()) {
            updateUiState(
                status = AppUpdateStatus.Unsupported,
                message = Res.string.app_update_unsupported.toUiText(),
            )
            return
        }

        if (trigger == AppUpdateCheckTrigger.Manual) {
            updateUiState(
                status = AppUpdateStatus.Error,
                message = Res.string.app_update_check_failed.toUiText(),
            )
        } else {
            updateUiState(status = AppUpdateStatus.Idle)
        }
    }

    /**
     * Starts the chosen Play update flow through the attached launcher.
     */
    private fun launchUpdateFlow(
        appUpdateInfo: AppUpdateInfo,
        flowType: AppUpdateFlowType,
        trigger: AppUpdateCheckTrigger,
    ) {
        val flowLauncher = launcher
        if (flowLauncher == null) {
            Napier.w("App update launcher not attached; skipping $flowType update flow")
            if (trigger == AppUpdateCheckTrigger.Manual) {
                updateUiState(
                    status = AppUpdateStatus.Error,
                    message = Res.string.app_update_status_flow_not_ready.toUiText(),
                )
            } else {
                updateUiState(status = AppUpdateStatus.Idle)
            }
            return
        }

        activeFlowType = flowType
        lastCheckTrigger = trigger
        updateUiState(
            status = AppUpdateStatus.Available,
            availableVersionCode = appUpdateInfo.availableVersionCode(),
            flowType = flowType,
            message =
                when (flowType) {
                    AppUpdateFlowType.Flexible -> Res.string.app_update_prompt_available.toUiText()
                    AppUpdateFlowType.Immediate -> Res.string.app_update_immediate_required.toUiText()
                },
        )

        val launched =
            flowLauncher.launch(
                appUpdateManager = appUpdateManager,
                appUpdateInfo = appUpdateInfo,
                options =
                    when (flowType) {
                        AppUpdateFlowType.Flexible -> flexibleOptions()
                        AppUpdateFlowType.Immediate -> immediateOptions()
                    },
            )

        if (!launched) {
            activeFlowType = null
            if (flowType == AppUpdateFlowType.Flexible) {
                deferFlexiblePrompt()
            }
            updateUiState(
                status = AppUpdateStatus.Error,
                message = Res.string.app_update_status_launch_failed.toUiText(),
            )
        }
    }

    /**
     * Rebuilds the shared UI state while preserving the installed app version metadata.
     */
    private fun updateUiState(
        status: AppUpdateStatus,
        availableVersionCode: Int? = _uiState.value.availableVersionCode,
        flowType: AppUpdateFlowType? = _uiState.value.flowType,
        message: UiText? = _uiState.value.message,
        lastCheckedAt: Instant? = _uiState.value.lastCheckedAt,
    ) {
        _uiState.value =
            AppUpdateUiState(
                currentVersionName = appInfo.versionName,
                currentVersionCode = appInfo.versionCode,
                status = status,
                availableVersionCode = availableVersionCode,
                flowType = flowType,
                message = message,
                lastCheckedAt = lastCheckedAt,
            )
    }

    /**
     * Starts the cooldown used to avoid re-showing a dismissed flexible update on every launch.
     */
    private fun deferFlexiblePrompt() {
        preferences
            .edit()
            .putLong(DEFERRED_PROMPT_AT_KEY, clock.now().toEpochMilliseconds())
            .putInt(DEFERRED_VERSION_CODE_KEY, _uiState.value.availableVersionCode ?: NO_DEFERRED_VERSION)
            .apply()
    }

    /**
     * Clears any stored flexible-update cooldown after the app has been updated.
     */
    private fun clearDeferredFlexiblePrompt() {
        preferences
            .edit()
            .remove(DEFERRED_PROMPT_AT_KEY)
            .remove(DEFERRED_VERSION_CODE_KEY)
            .apply()
    }

    /**
     * Returns true when automatic flexible prompts for the specified version are still deferred.
     */
    private fun isFlexiblePromptDeferred(availableVersionCode: Int): Boolean {
        val deferredAt = preferences.getLong(DEFERRED_PROMPT_AT_KEY, 0L)
        val deferredVersionCode = preferences.getInt(DEFERRED_VERSION_CODE_KEY, NO_DEFERRED_VERSION)
        if (deferredAt == 0L || deferredVersionCode != availableVersionCode) {
            return false
        }

        val expiresAt = Instant.fromEpochMilliseconds(deferredAt) + FLEXIBLE_PROMPT_DEFERRAL
        return clock.now() < expiresAt
    }

    /**
     * Default Play Core options for an immediate update.
     */
    private fun immediateOptions(): AppUpdateOptions = AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)

    /**
     * Default Play Core options for a flexible update.
     */
    private fun flexibleOptions(): AppUpdateOptions = AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)

    /** Install error codes that indicate the build or device cannot use in-app updates. */
    private fun unsupportedErrorCodes(): Set<Int> =
        setOf(
            ERROR_API_NOT_AVAILABLE,
            ERROR_INSTALL_UNAVAILABLE,
            ERROR_PLAY_STORE_NOT_FOUND,
            ERROR_APP_NOT_OWNED,
        )

    companion object {
        private const val PREFERENCES_NAME = "play_in_app_updates"
        private const val DEFERRED_PROMPT_AT_KEY = "deferred_prompt_at"
        private const val DEFERRED_VERSION_CODE_KEY = "deferred_version_code"
        private const val NO_DEFERRED_VERSION = -1

        /** Duration of the automatic flexible-prompt cooldown after dismissal. */
        private val FLEXIBLE_PROMPT_DEFERRAL: Duration = 24.hours

        // InstallException exposes raw error codes; these are the Play Core values for unsupported installs.
        private const val ERROR_API_NOT_AVAILABLE = -3
        private const val ERROR_INSTALL_UNAVAILABLE = -5
        private const val ERROR_PLAY_STORE_NOT_FOUND = -9
        private const val ERROR_APP_NOT_OWNED = -10
    }
}
