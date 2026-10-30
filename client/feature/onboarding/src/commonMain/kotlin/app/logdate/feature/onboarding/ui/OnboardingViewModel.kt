package app.logdate.feature.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.billing.model.LogDateBackupPlanOption
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.v2.NoWatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import app.logdate.feature.onboarding.flow.OnboardingDeviceState
import app.logdate.feature.onboarding.flow.OnboardingDeviceStateRepository
import app.logdate.feature.onboarding.flow.OnboardingEntryMode
import app.logdate.feature.onboarding.flow.OnboardingProgressSnapshot
import app.logdate.feature.onboarding.flow.OnboardingStep
import app.logdate.feature.onboarding.flow.canCompleteOnboarding
import app.logdate.feature.onboarding.flow.firstIncompleteRequiredOnboardingStep
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * A view model for the onboarding flow.
 */
class OnboardingViewModel(
    private val userStateRepository: UserStateRepository,
    private val memoriesSettingsRepository: MemoriesSettingsRepository,
    private val locationTrackingSettingsRepository: LocationTrackingSettingsRepository,
    private val dayBoundarySettingsRepository: DayBoundarySettingsRepository,
    private val observeHealthConnectStatus: ObserveHealthConnectStatusUseCase,
    observeUserIdentity: ObserveUserIdentityUseCase,
    private val onboardingDeviceStateRepository: OnboardingDeviceStateRepository,
    private val refreshStreakUseCase: RefreshStreakUseCase,
    private val identityKeyManager: IdentityKeyManager,
    val watchSetup: WatchSetupCoordinator = NoWatchSetupCoordinator,
    watchRollout: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    private val _healthConnectStatus = MutableStateFlow(HealthConnectStatus.CHECKING)
    private var healthStatusJob: Job? = null

    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    val healthConnectStatus: StateFlow<HealthConnectStatus> = _healthConnectStatus

    val activeEntryMode: StateFlow<OnboardingEntryMode> =
        onboardingDeviceStateRepository.deviceState
            .map { it.activeEntryMode }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = onboardingDeviceStateRepository.deviceState.value.activeEntryMode,
            )

    private val baseProgressSnapshot: StateFlow<OnboardingProgressSnapshot> =
        combine(
            observeUserIdentity(),
            combine(
                combine(
                    onboardingDeviceStateRepository.deviceState,
                    healthConnectStatus,
                    memoriesSettingsRepository.observeSettings(),
                    locationTrackingSettingsRepository.observeSettings(),
                ) { deviceState, healthStatus, memoriesSettings, locationSettings ->
                    PartialOnboardingProgressInputs(
                        deviceState = deviceState,
                        healthStatus = healthStatus,
                        recommendationsEnabled = memoriesSettings.contextualRecommendationsEnabled,
                        locationTrackingEnabled = locationSettings.backgroundTrackingEnabled,
                    )
                },
                dayBoundarySettingsRepository.observeSettings(),
            ) { partialInputs, dayBoundarySettings ->
                OnboardingProgressInputs(
                    deviceState = partialInputs.deviceState,
                    healthStatus = partialInputs.healthStatus,
                    recommendationsEnabled = partialInputs.recommendationsEnabled,
                    locationTrackingEnabled = partialInputs.locationTrackingEnabled,
                    sleepBasedDayBoundariesEnabled = dayBoundarySettings.sleepBasedBoundariesEnabled,
                )
            },
        ) { identity, inputs ->
            OnboardingProgressSnapshot(
                // Only the name is asked for; a blank bio must not send someone back through the
                // introduction every time they open the app.
                hasPersonalIntro = identity.displayName.isNotBlank(),
                hasBirthday = identity.birthday != null,
                hasCloudAccount =
                    identity.isAuthenticated ||
                        identity.cloudAccountId != null ||
                        !identity.username.isNullOrBlank(),
                accountHandledOnThisDevice = inputs.deviceState.accountHandledOnThisDevice,
                recommendationsHandledOnThisDevice = inputs.deviceState.recommendationsHandledOnThisDevice,
                contextualRecommendationsEnabled = inputs.recommendationsEnabled,
                dayBoundariesHandledOnThisDevice = inputs.deviceState.dayBoundariesHandledOnThisDevice,
                sleepBasedDayBoundariesEnabled = inputs.sleepBasedDayBoundariesEnabled,
                locationHandledOnThisDevice = inputs.deviceState.locationHandledOnThisDevice,
                locationTrackingEnabled = inputs.locationTrackingEnabled,
                notificationsHandledOnThisDevice = inputs.deviceState.notificationsHandledOnThisDevice,
                watchHandledOnThisDevice = inputs.deviceState.watchHandledOnThisDevice,
                healthConnectStatus = inputs.healthStatus,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue =
                OnboardingProgressSnapshot(
                    accountHandledOnThisDevice =
                        onboardingDeviceStateRepository.deviceState.value.accountHandledOnThisDevice,
                    recommendationsHandledOnThisDevice =
                        onboardingDeviceStateRepository.deviceState.value.recommendationsHandledOnThisDevice,
                    dayBoundariesHandledOnThisDevice =
                        onboardingDeviceStateRepository.deviceState.value.dayBoundariesHandledOnThisDevice,
                    locationHandledOnThisDevice =
                        onboardingDeviceStateRepository.deviceState.value.locationHandledOnThisDevice,
                    notificationsHandledOnThisDevice =
                        onboardingDeviceStateRepository.deviceState.value.notificationsHandledOnThisDevice,
                    watchHandledOnThisDevice = onboardingDeviceStateRepository.deviceState.value.watchHandledOnThisDevice,
                    healthConnectStatus = healthConnectStatus.value,
                ),
        )

    val progressSnapshot: StateFlow<OnboardingProgressSnapshot> =
        combine(baseProgressSnapshot, watchRollout, watchSetup.connection()) { base, enabled, connection ->
            base.copy(
                watchAvailable =
                    enabled && connection != WatchConnectionState.Loading && connection != WatchConnectionState.NoPairedWatch,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, baseProgressSnapshot.value)

    suspend fun markWatchHandled(): Result<Unit> =
        try {
            onboardingDeviceStateRepository.markWatchHandled()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Napier.e("Could not save watch setup progress", failure)
            Result.failure(failure)
        }

    /**
     * Whether contextual recommendations are currently enabled.
     *
     * Used by the notifications screen to adapt its messaging.
     */
    val recommendationsEnabled: StateFlow<Boolean> =
        memoriesSettingsRepository
            .observeSettings()
            .map { it.contextualRecommendationsEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    init {
        refreshHealthStatus()
        provisionIdentityKey()
    }

    fun updateBirthday(birthday: Instant) {
        viewModelScope.launch {
            persistBirthday(birthday)
        }
    }

    suspend fun persistBirthday(birthday: Instant): Result<Unit> =
        runCatching {
            userStateRepository.setBirthday(birthday)
        }

    suspend fun persistRecommendationsEnabled(enabled: Boolean): Result<Unit> =
        runCatching {
            memoriesSettingsRepository.setContextualRecommendationsEnabled(enabled)
        }

    suspend fun persistLocationTrackingEnabled(): Result<Unit> =
        runCatching {
            locationTrackingSettingsRepository.setBackgroundTrackingEnabled(true)
        }

    suspend fun persistSleepBasedDayBoundariesEnabled(enabled: Boolean): Result<Unit> =
        runCatching {
            dayBoundarySettingsRepository.setSleepBasedBoundariesEnabled(enabled)
        }

    fun refreshHealthStatus() {
        healthStatusJob?.cancel()
        healthStatusJob =
            viewModelScope.launch {
                observeHealthConnectStatus().collect { status ->
                    _healthConnectStatus.value = status
                }
            }
    }

    /**
     * Provisions this device's identity key if it has none.
     *
     * Doing it here rather than behind a recovery-phrase screen keeps the guarantee independent of
     * what the user is shown: they read the phrase from settings when they want it, never as a gate.
     */
    fun provisionIdentityKey() {
        viewModelScope.launch {
            runCatching { identityKeyManager.ensureIdentityKey() }
                .onFailure { error ->
                    // Sync provisions the key too, so this is recoverable rather than terminal --
                    // but uploads fail until then, so it is not a warning.
                    Napier.e("Failed to provision identity key", error)
                }
        }
    }

    /**
     * Selects a backup plan option.
     */
    fun selectPlan(option: LogDateBackupPlanOption) {
        _uiState.update {
            it.copy(planOption = option)
        }
    }

    suspend fun markNotificationsHandled(): Result<Unit> =
        runCatching {
            onboardingDeviceStateRepository.markNotificationsHandled()
        }

    suspend fun markRecommendationsHandled(): Result<Unit> =
        runCatching {
            onboardingDeviceStateRepository.markRecommendationsHandled()
        }

    suspend fun markDayBoundariesHandled(): Result<Unit> =
        runCatching {
            onboardingDeviceStateRepository.markDayBoundariesHandled()
        }

    suspend fun markLocationHandled(): Result<Unit> =
        runCatching {
            onboardingDeviceStateRepository.markLocationHandled()
        }

    suspend fun markAccountHandled(): Result<Unit> =
        runCatching {
            onboardingDeviceStateRepository.markAccountHandled()
        }

    suspend fun setActiveEntryMode(entryMode: OnboardingEntryMode): Result<Unit> =
        runCatching {
            onboardingDeviceStateRepository.setActiveEntryMode(entryMode)
        }

    suspend fun completeOnboardingIfEligible(): Result<Unit> =
        runCatching {
            require(progressSnapshot.value.canCompleteOnboarding()) {
                "Required onboarding steps are still incomplete"
            }
            userStateRepository.setIsOnboardingComplete(true)
            refreshStreakUseCase()
        }

    fun firstIncompleteRequiredOnboardingStep(): OnboardingStep? = progressSnapshot.value.firstIncompleteRequiredOnboardingStep()

    override fun onCleared() {
        healthStatusJob?.cancel()
        super.onCleared()
    }

    private data class OnboardingProgressInputs(
        val deviceState: OnboardingDeviceState,
        val healthStatus: HealthConnectStatus,
        val recommendationsEnabled: Boolean,
        val locationTrackingEnabled: Boolean,
        val sleepBasedDayBoundariesEnabled: Boolean,
    )

    private data class PartialOnboardingProgressInputs(
        val deviceState: OnboardingDeviceState,
        val healthStatus: HealthConnectStatus,
        val recommendationsEnabled: Boolean,
        val locationTrackingEnabled: Boolean,
    )
}
