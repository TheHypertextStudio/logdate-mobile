package app.logdate.feature.core.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.account.AuthenticateWithPasskeyUseCase
import app.logdate.client.domain.account.BackfillLocalDataUseCase
import app.logdate.client.domain.account.CheckUsernameAvailabilityUseCase
import app.logdate.client.domain.account.CreatePasskeyAccountUseCase
import app.logdate.client.domain.account.EmailVerificationAvailability
import app.logdate.client.domain.account.GoogleAuthError
import app.logdate.client.domain.account.SignInWithGoogleUseCase
import app.logdate.client.domain.account.TriggerInitialSyncUseCase
import app.logdate.client.domain.account.VerifyEmailUseCase
import app.logdate.client.permissions.EmailVerificationOutcome
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncStatus
import app.logdate.feature.core.settings.ui.ServerConfigurationCoordinator
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.feature.core.settings.ui.ServerValidationState
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.ServerDescriptor
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_failure_server_url_empty
import logdate.client.feature.core.generated.resources.account_validation_display_name_required
import logdate.client.feature.core.generated.resources.account_validation_display_name_too_long
import logdate.client.feature.core.generated.resources.account_validation_username_characters
import logdate.client.feature.core.generated.resources.account_validation_username_too_short
import logdate.client.feature.core.generated.resources.username_is_already_taken
import org.jetbrains.compose.resources.StringResource

class CloudAccountOnboardingViewModel(
    private val createPasskeyAccountUseCase: CreatePasskeyAccountUseCase,
    private val checkUsernameAvailabilityUseCase: CheckUsernameAvailabilityUseCase,
    private val authenticateWithPasskeyUseCase: AuthenticateWithPasskeyUseCase,
    private val signInWithGoogleUseCase: SignInWithGoogleUseCase,
    private val triggerInitialSyncUseCase: TriggerInitialSyncUseCase,
    private val backfillLocalDataUseCase: BackfillLocalDataUseCase,
    private val passkeyManager: PasskeyManager,
    private val verifyEmailUseCase: VerifyEmailUseCase,
    private val emailVerificationAvailability: EmailVerificationAvailability,
    private val profileRepository: ProfileRepository,
    private val serverConfigurationCoordinator: ServerConfigurationCoordinator,
    syncManager: SyncManager,
) : ViewModel() {
    /**
     * Live backup progress, so the first sync can say how far along it is.
     *
     * This screen is the one place a real count matters most - it is shown while several hundred
     * entries upload, and an indeterminate spinner there is the difference between "a moment" and
     * "an hour" left entirely unsaid.
     */
    val syncStatus: StateFlow<SyncStatus> = syncManager.syncStatusFlow

    private val _uiState =
        MutableStateFlow(
            CloudAccountOnboardingUiState(
                serverSelectionState = serverConfigurationCoordinator.initialSelectionState(),
                // Google sign-in is offered only when a web OAuth client ID is configured for this
                // build; otherwise the server would reject every token and the option is hidden.
                isGoogleSignInAvailable = DefaultLogDateConfigRepository.GOOGLE_SERVER_CLIENT_ID.isNotBlank(),
            ),
        )
    val uiState: StateFlow<CloudAccountOnboardingUiState> = _uiState.asStateFlow()

    private var usernameCheckJob: Job? = null
    private var entryStep: OnboardingStep = OnboardingStep.Welcome

    init {
        checkPasskeySupport()
        viewModelScope.launch {
            try {
                _uiState.value =
                    _uiState.value.copy(
                        isEmailVerificationAvailable = emailVerificationAvailability.isAvailable(),
                    )
            } catch (e: Exception) {
                Napier.w("Failed to resolve email verification availability", e)
            }
        }
        prefillFromLocalProfile()
    }

    /**
     * Seeds the flow from the local profile onboarding already captured.
     *
     * Onboarding asks for a name and a bio before this flow ever runs, so asking again - with empty
     * fields - reads as a broken form rather than a review. When a name is already known the
     * DisplayName step is skipped outright; the user can still edit it from the confirmation step.
     */
    private fun prefillFromLocalProfile() {
        viewModelScope.launch {
            val profile =
                try {
                    profileRepository.getCurrentProfile()
                } catch (e: Exception) {
                    Napier.w("Failed to load local profile for account setup prefill", e)
                    return@launch
                }
            val name = profile.displayName.trim()
            val bio = profile.bio?.trim().orEmpty()
            _uiState.update { state ->
                val resolvedName = state.displayName.ifBlank { name }
                state.copy(
                    displayName = resolvedName,
                    bio = state.bio.ifBlank { bio },
                    hasProfileDisplayName = resolvedName.isNotBlank(),
                )
            }
            collapseDisplayNameStepIfKnown()
        }
    }

    /**
     * Moves off the DisplayName step once the profile is known to carry a name.
     *
     * [setInitialStep] and the profile load race each other - the caller sets the entry step from a
     * composition effect while the profile resolves off the main thread - so both call this and
     * whichever finishes last performs the collapse.
     */
    private fun collapseDisplayNameStepIfKnown() {
        if (_uiState.value.currentStep != OnboardingStep.DisplayName) return
        val resolved = resolveStep(OnboardingStep.DisplayName)
        if (resolved == OnboardingStep.DisplayName) return
        _uiState.update { it.copy(currentStep = resolved) }
    }

    /**
     * Maps a requested step to the step the user should actually land on.
     *
     * Server-selection persistence assigns the step from its own coroutine, so resolving at every
     * assignment point - rather than only after the profile loads - keeps the two races from
     * reinstating a step that was already collapsed.
     */
    private fun resolveStep(step: OnboardingStep): OnboardingStep =
        if (step == OnboardingStep.DisplayName && _uiState.value.hasProfileDisplayName) {
            if (entryStep == OnboardingStep.DisplayName) {
                // Keep "back exits the flow" pointing at the step the user actually lands on.
                entryStep = OnboardingStep.Username
            }
            OnboardingStep.Username
        } else {
            step
        }

    /**
     * Sets the initial step for the flow, skipping earlier steps.
     *
     * **Side-effects:**
     * - Updates [CloudAccountOnboardingUiState.currentStep] to [step].
     * - Records [step] as the entry point: [goToPreviousStep] will set
     *   [CloudAccountOnboardingUiState.isExitRequested] instead of navigating
     *   when the user is already at this step.
     * - When [step] is not [OnboardingStep.Welcome], triggers
     *   [prepareServerSelection] to validate and persist the server
     *   configuration (required before DisplayName/SignIn steps can proceed).
     *   This launches a coroutine that may update [CloudAccountOnboardingUiState.serverSelectionState].
     *
     * Should be called once before the composable renders, typically from a
     * `LaunchedEffect(Unit)` block.
     */
    fun setInitialStep(step: OnboardingStep) {
        entryStep = step
        _uiState.value = _uiState.value.copy(currentStep = resolveStep(step))
        if (step != OnboardingStep.Welcome) {
            prepareServerSelection(step)
        }
    }

    /**
     * The first step after Welcome. DisplayName is skipped when onboarding already captured a name,
     * so the flow is Username -> Confirm rather than three screens with a pre-answered one in front.
     */
    private fun stepAfterWelcome(): OnboardingStep = resolveStep(OnboardingStep.DisplayName)

    fun goToNextStep() {
        val currentStep = _uiState.value.currentStep
        when (currentStep) {
            OnboardingStep.Welcome -> {
                prepareServerSelection(stepAfterWelcome())
            }

            OnboardingStep.SignIn -> {
                _uiState.value = _uiState.value.copy(currentStep = OnboardingStep.Complete)
            }

            OnboardingStep.DisplayName -> {
                _uiState.value = _uiState.value.copy(currentStep = OnboardingStep.Username)
            }

            OnboardingStep.Username -> {
                _uiState.value = _uiState.value.copy(currentStep = OnboardingStep.PasskeyCreation)
            }

            OnboardingStep.PasskeyCreation -> {
                val next =
                    if (_uiState.value.isEmailVerificationAvailable) {
                        OnboardingStep.EmailVerification
                    } else {
                        OnboardingStep.Complete
                    }
                _uiState.value = _uiState.value.copy(currentStep = next)
            }

            OnboardingStep.EmailVerification -> {
                _uiState.value = _uiState.value.copy(currentStep = OnboardingStep.Complete)
            }

            OnboardingStep.Complete -> {
                _uiState.value = _uiState.value.copy(currentStep = OnboardingStep.Complete)
            }
        }
    }

    fun goToPreviousStep() {
        val currentStep = _uiState.value.currentStep
        if (currentStep == entryStep) {
            _uiState.value = _uiState.value.copy(isExitRequested = true)
            return
        }
        val previousStep =
            when (currentStep) {
                OnboardingStep.Welcome -> {
                    OnboardingStep.Welcome
                }

                OnboardingStep.SignIn -> {
                    OnboardingStep.Welcome
                }

                OnboardingStep.DisplayName -> {
                    OnboardingStep.Welcome
                }

                OnboardingStep.Username -> {
                    if (_uiState.value.hasProfileDisplayName) OnboardingStep.Welcome else OnboardingStep.DisplayName
                }

                OnboardingStep.PasskeyCreation -> {
                    OnboardingStep.Username
                }

                OnboardingStep.EmailVerification -> {
                    OnboardingStep.PasskeyCreation
                }

                OnboardingStep.Complete -> {
                    OnboardingStep.Complete
                }
            }

        _uiState.value = _uiState.value.copy(currentStep = previousStep)
    }

    fun updateDisplayName(displayName: String) {
        _uiState.value =
            _uiState.value.copy(
                displayName = displayName,
                displayNameError = null,
            )
    }

    fun updateUsername(username: String) {
        _uiState.value =
            _uiState.value.copy(
                username = username,
                usernameError = null,
            )

        // Cancel previous username check
        usernameCheckJob?.cancel()

        if (username.isNotBlank() && username.length >= 3) {
            usernameCheckJob =
                viewModelScope.launch {
                    delay(300)
                    checkUsernameAvailability(username)
                }
        } else {
            _uiState.value =
                _uiState.value.copy(
                    usernameAvailability = UsernameAvailability.Unknown,
                )
        }
    }

    fun updateBio(bio: String) {
        _uiState.value = _uiState.value.copy(bio = bio)
    }

    fun createAccount() {
        val currentState = _uiState.value

        // Validate inputs
        if (!validateInputs()) {
            return
        }

        _uiState.value =
            currentState.copy(
                isCreatingAccount = true,
                errorMessage = null,
            )

        viewModelScope.launch {
            val result =
                createPasskeyAccountUseCase(
                    username = currentState.username,
                    displayName = currentState.displayName,
                    bio = currentState.bio.takeIf { it.isNotBlank() },
                    originalBio = originalBioForSignup(profileRepository, currentState.bio),
                )

            when (result) {
                is CreatePasskeyAccountUseCase.Result.Success -> {
                    syncDisplayNameToLocalProfile(result.account.displayName)
                    _uiState.value =
                        _uiState.value.copy(
                            isCreatingAccount = false,
                            createdAccount = result.account,
                            currentStep = OnboardingStep.Complete,
                            isInitialSyncing = true,
                            initialSyncStatus = InitialSyncStatus.Running,
                        )
                    performInitialSync(markCompletedBy = SyncCompletion.ACCOUNT_CREATED)
                }

                is CreatePasskeyAccountUseCase.Result.Error -> {
                    _uiState.value =
                        currentState.copy(
                            isCreatingAccount = false,
                            errorMessage = accountCreationFailureMessage(result.error),
                        )
                }
            }
        }
    }

    /**
     * Runs the blocking portion of the first sync and transitions to [OnboardingStep.Complete]
     * when it settles (success, partial, timeout, or error). The onboarding flow does not fail
     * because sync had issues — we still let the user into the app — but the UI reflects the
     * outcome so the global sync-status banner can prompt retries downstream.
     *
     * [markCompletedBy] determines which terminal flag flips at the end. This is what drives the
     * screen's completion LaunchedEffect, so it must only flip once sync is done — otherwise the
     * host navigates away mid-sync and the user never sees the sync progress state.
     */
    private suspend fun performInitialSync(markCompletedBy: SyncCompletion) {
        // First-sign-in for an account ID re-enqueues local data into the sync queue, since
        // accountless writes don't enqueue. Idempotent: same account never backfills twice.
        // Logged inside the use case; failures don't block the sync attempt — the worst case
        // is that fullSync uploads a partial set on this run and the rest on a later run.
        _uiState.value.createdAccount?.id?.toString()?.let { accountId ->
            backfillLocalDataUseCase(accountId)
        }
        val syncResult = triggerInitialSyncUseCase()
        val status =
            when (syncResult) {
                is TriggerInitialSyncUseCase.Result.Success -> {
                    InitialSyncStatus.Success
                }

                is TriggerInitialSyncUseCase.Result.Partial -> {
                    Napier.w(
                        "Initial sync partial: uploads=${syncResult.uploadedItems}, " +
                            "downloads=${syncResult.downloadedItems}, errors=${syncResult.errorMessages}",
                    )
                    InitialSyncStatus.Partial
                }

                is TriggerInitialSyncUseCase.Result.TimedOut -> {
                    InitialSyncStatus.TimedOut
                }

                is TriggerInitialSyncUseCase.Result.Error -> {
                    InitialSyncStatus.Failed
                }
            }
        _uiState.value =
            _uiState.value.copy(
                isInitialSyncing = false,
                initialSyncStatus = status,
                currentStep = OnboardingStep.Complete,
                isAccountCreated = _uiState.value.isAccountCreated || markCompletedBy == SyncCompletion.ACCOUNT_CREATED,
                isSignedIn = _uiState.value.isSignedIn || markCompletedBy == SyncCompletion.SIGNED_IN,
            )
    }

    private enum class SyncCompletion {
        ACCOUNT_CREATED,
        SIGNED_IN,
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, googleAuthError = null)
    }

    fun goToSignIn() {
        prepareServerSelection(OnboardingStep.SignIn)
    }

    fun signInWithPasskey(username: String) {
        signInWithPasskey(username, adoptLocalData = false)
    }

    /**
     * Signs in and accepts that entries already on this device become part of the account. Only
     * called once the user has seen [CloudAccountOnboardingUiState.pendingLocalDataAdoption] and
     * agreed.
     */
    fun signInAdoptingLocalData(username: String) {
        signInWithPasskey(username, adoptLocalData = true)
    }

    fun dismissLocalDataAdoption() {
        _uiState.value = _uiState.value.copy(pendingLocalDataAdoption = null)
    }

    private fun signInWithPasskey(
        username: String,
        adoptLocalData: Boolean,
    ) {
        _uiState.value =
            _uiState.value.copy(
                isSigningIn = true,
                errorMessage = null,
                pendingLocalDataAdoption = null,
            )

        viewModelScope.launch {
            val usernameParam = username.takeIf { it.isNotBlank() }
            val result = authenticateWithPasskeyUseCase(usernameParam, adoptLocalData)

            when (result) {
                is AuthenticateWithPasskeyUseCase.Result.Success -> {
                    syncDisplayNameToLocalProfile(result.account.displayName)
                    _uiState.value =
                        _uiState.value.copy(
                            isSigningIn = false,
                            createdAccount = result.account,
                            currentStep = OnboardingStep.Complete,
                            isInitialSyncing = true,
                            initialSyncStatus = InitialSyncStatus.Running,
                        )
                    performInitialSync(markCompletedBy = SyncCompletion.SIGNED_IN)
                }

                is AuthenticateWithPasskeyUseCase.Result.Error -> {
                    val needsConsent =
                        result.error == AuthenticateWithPasskeyUseCase.AuthenticationError.LocalDataNeedsAdoption
                    _uiState.value =
                        _uiState.value.copy(
                            isSigningIn = false,
                            pendingLocalDataAdoption = username.takeIf { needsConsent } ?: "",
                            errorMessage = if (needsConsent) null else signInFailureMessage(result.error),
                        )
                    if (!needsConsent) {
                        _uiState.value = _uiState.value.copy(pendingLocalDataAdoption = null)
                    }
                }
            }
        }
    }

    /**
     * Signs in (or links/creates, per the server's implicit Google linking) with a Google account.
     * Mirrors [signInWithPasskey]: on success it runs the blocking initial sync before completing.
     */
    fun signInWithGoogle() {
        _uiState.value =
            _uiState.value.copy(
                isSigningIn = true,
                errorMessage = null,
                googleAuthError = null,
            )

        viewModelScope.launch {
            when (val result = signInWithGoogleUseCase()) {
                is SignInWithGoogleUseCase.Result.Success -> {
                    syncDisplayNameToLocalProfile(result.account.displayName)
                    _uiState.value =
                        _uiState.value.copy(
                            isSigningIn = false,
                            createdAccount = result.account,
                            currentStep = OnboardingStep.Complete,
                            isInitialSyncing = true,
                            initialSyncStatus = InitialSyncStatus.Running,
                        )
                    performInitialSync(markCompletedBy = SyncCompletion.SIGNED_IN)
                }

                is SignInWithGoogleUseCase.Result.Error -> {
                    // Semantic error type; the UI maps it to a localized string resource.
                    _uiState.value =
                        _uiState.value.copy(
                            isSigningIn = false,
                            googleAuthError = result.error,
                        )
                }
            }
        }
    }

    fun skipOnboarding() {
        _uiState.value =
            _uiState.value.copy(
                currentStep = OnboardingStep.Complete,
                isSkipped = true,
            )
    }

    fun resetFlow() {
        _uiState.value =
            CloudAccountOnboardingUiState(
                serverSelectionState = serverConfigurationCoordinator.initialSelectionState(),
            )
        checkPasskeySupport()
        // Restarting the flow must not discard what onboarding already knows about the user;
        // otherwise the name and bio fields come back empty and the flow re-asks for both.
        prefillFromLocalProfile()
    }

    fun selectServerPreset(preset: ServerPreset) {
        _uiState.value =
            _uiState.value.copy(
                serverSelectionState =
                    _uiState.value.serverSelectionState.copy(
                        selectedPreset = preset,
                        validationState = ServerValidationState.Idle,
                    ),
                errorMessage = null,
            )
    }

    fun updateCustomServerUrl(url: String) {
        _uiState.value =
            _uiState.value.copy(
                serverSelectionState =
                    _uiState.value.serverSelectionState.copy(
                        customServerUrl = url,
                        validationState = ServerValidationState.Idle,
                    ),
                errorMessage = null,
            )
    }

    private fun checkPasskeySupport() {
        viewModelScope.launch {
            val capabilities = passkeyManager.getCapabilities()
            _uiState.value =
                _uiState.value.copy(
                    isPasskeySupported = capabilities.isSupported,
                    isPlatformAuthenticatorAvailable = capabilities.isPlatformAuthenticatorAvailable,
                )
        }
    }

    private suspend fun checkUsernameAvailability(username: String) {
        _uiState.value =
            _uiState.value.copy(
                usernameAvailability = UsernameAvailability.Checking,
            )

        val result = checkUsernameAvailabilityUseCase(username)

        _uiState.value =
            _uiState.value.copy(
                usernameAvailability =
                    when (result) {
                        is CheckUsernameAvailabilityUseCase.Result.Success -> {
                            if (result.isAvailable) {
                                UsernameAvailability.Available
                            } else {
                                UsernameAvailability.Taken
                            }
                        }

                        is CheckUsernameAvailabilityUseCase.Result.Error -> {
                            UsernameAvailability.Error
                        }
                    },
            )
    }

    private fun prepareServerSelection(nextStep: OnboardingStep) {
        viewModelScope.launch {
            val serverSelectionState = _uiState.value.serverSelectionState
            when (serverSelectionState.selectedPreset) {
                ServerPreset.PRODUCTION -> {
                    persistServerSelection(
                        nextStep = nextStep,
                        failureMessage = { error -> serverSelectionFailureMessage(error, ServerPreset.PRODUCTION) },
                    ) {
                        serverConfigurationCoordinator.saveLogDateCloudSelection()
                    }
                }

                ServerPreset.CUSTOM -> {
                    val customServerUrl = serverSelectionState.customServerUrl
                    if (customServerUrl.isBlank()) {
                        updateServerSelectionState(
                            validationState = ServerValidationState.Error(Res.string.account_failure_server_url_empty),
                            errorMessage = Res.string.account_failure_server_url_empty,
                        )
                        return@launch
                    }

                    updateServerSelectionState(
                        validationState = ServerValidationState.Validating,
                        errorMessage = null,
                    )

                    persistServerSelection(
                        nextStep = nextStep,
                        failureMessage = { error -> serverSelectionFailureMessage(error, ServerPreset.CUSTOM) },
                    ) {
                        serverConfigurationCoordinator.validateAndSaveCustomServer(customServerUrl)
                    }
                }
            }
        }
    }

    private suspend fun persistServerSelection(
        nextStep: OnboardingStep,
        failureMessage: (Throwable) -> StringResource,
        save: suspend () -> Result<ServerConfigurationCoordinator.SaveResult>,
    ) {
        save()
            .onSuccess { result ->
                updateServerSelectionState(
                    validationState = ServerValidationState.Success(result.serverVersion),
                    errorMessage = null,
                    nextStep = resolveStep(nextStep),
                    activeServerDescriptor = result.descriptor,
                    customServerUrl =
                        result.serverOrigin.takeIf {
                            _uiState.value.serverSelectionState.selectedPreset == ServerPreset.CUSTOM
                        },
                )
            }.onFailure { error ->
                Napier.w("Server validation failed", error)
                val message = failureMessage(error)
                updateServerSelectionState(
                    validationState = ServerValidationState.Error(message),
                    errorMessage = message,
                )
            }
    }

    private fun updateServerSelectionState(
        validationState: ServerValidationState,
        errorMessage: StringResource?,
        nextStep: OnboardingStep? = null,
        activeServerDescriptor: ServerDescriptor? = _uiState.value.serverSelectionState.activeServerDescriptor,
        customServerUrl: String? = null,
    ) {
        _uiState.value =
            _uiState.value.copy(
                currentStep = nextStep ?: _uiState.value.currentStep,
                errorMessage = errorMessage,
                serverSelectionState =
                    _uiState.value.serverSelectionState.copy(
                        validationState = validationState,
                        activeServerDescriptor = activeServerDescriptor,
                        customServerUrl = customServerUrl ?: _uiState.value.serverSelectionState.customServerUrl,
                    ),
            )
    }

    private fun validateInputs(): Boolean {
        val currentState = _uiState.value
        var isValid = true

        // Display name validation
        if (currentState.displayName.isBlank()) {
            _uiState.value =
                currentState.copy(
                    displayNameError = Res.string.account_validation_display_name_required,
                )
            isValid = false
        } else if (currentState.displayName.length > 100) {
            _uiState.value =
                currentState.copy(
                    displayNameError = Res.string.account_validation_display_name_too_long,
                )
            isValid = false
        }

        // Username validation
        if (currentState.username.isBlank() || currentState.username.length < 3) {
            _uiState.value =
                currentState.copy(
                    usernameError = Res.string.account_validation_username_too_short,
                )
            isValid = false
        } else if (!currentState.username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            _uiState.value =
                currentState.copy(
                    usernameError = Res.string.account_validation_username_characters,
                )
            isValid = false
        } else if (currentState.usernameAvailability == UsernameAvailability.Taken) {
            _uiState.value =
                currentState.copy(
                    usernameError = Res.string.username_is_already_taken,
                )
            isValid = false
        }

        return isValid
    }

    private suspend fun syncDisplayNameToLocalProfile(displayName: String) {
        if (displayName.isNotBlank()) {
            profileRepository.updateDisplayName(displayName).onFailure { e ->
                Napier.e("Failed to sync display name to local profile", e)
            }
        }
    }

    fun onVerifyEmailClicked() {
        if (_uiState.value.isVerifyingEmail) return
        _uiState.value =
            _uiState.value.copy(
                isVerifyingEmail = true,
                emailVerificationOutcome = null,
            )
        viewModelScope.launch {
            val outcome =
                try {
                    verifyEmailUseCase()
                } catch (e: Exception) {
                    Napier.e("Email verification crashed", e)
                    EmailVerificationOutcome.Failed("verification_crashed")
                }
            _uiState.value =
                _uiState.value.copy(
                    isVerifyingEmail = false,
                    emailVerificationOutcome = outcome,
                )
            if (outcome is EmailVerificationOutcome.Success) {
                // Hold the success screen for a beat so the user sees confirmation,
                // then advance. UI may also dismiss via a manual continue.
                delay(1_000)
                if (_uiState.value.currentStep == OnboardingStep.EmailVerification) {
                    _uiState.value = _uiState.value.copy(currentStep = OnboardingStep.Complete)
                }
            }
        }
    }

    fun onSkipEmailVerification() {
        _uiState.value =
            _uiState.value.copy(
                currentStep = OnboardingStep.Complete,
                isSkipped = true,
            )
    }

    fun dismissEmailVerificationOutcome() {
        _uiState.value = _uiState.value.copy(emailVerificationOutcome = null)
    }
}

data class CloudAccountOnboardingUiState(
    /**
     * Set to the username being signed in when this device already holds entries that no account
     * has claimed. Signing in makes them part of that account, so the user confirms first.
     */
    val pendingLocalDataAdoption: String? = null,
    val currentStep: OnboardingStep = OnboardingStep.Welcome,
    val serverSelectionState: ServerSelectionState = ServerSelectionState(),
    val displayName: String = "",
    val username: String = "",
    val bio: String = "",
    /** True when onboarding already captured a name, which collapses the DisplayName step. */
    val hasProfileDisplayName: Boolean = false,
    val displayNameError: StringResource? = null,
    val usernameError: StringResource? = null,
    val usernameAvailability: UsernameAvailability = UsernameAvailability.Unknown,
    val isPasskeySupported: Boolean = true,
    val isPlatformAuthenticatorAvailable: Boolean = true,
    /** True when a Google web OAuth client ID is configured; gates the "Continue with Google" option. */
    val isGoogleSignInAvailable: Boolean = false,
    val isCreatingAccount: Boolean = false,
    val isAccountCreated: Boolean = false,
    val isSigningIn: Boolean = false,
    val isSignedIn: Boolean = false,
    val isInitialSyncing: Boolean = false,
    val initialSyncStatus: InitialSyncStatus = InitialSyncStatus.NotStarted,
    val isSkipped: Boolean = false,
    /**
     * True when the user pressed back from the entry step set via
     * [CloudAccountOnboardingViewModel.setInitialStep], signaling
     * that the host should pop this flow from the navigation stack.
     */
    val isExitRequested: Boolean = false,
    val createdAccount: LogDateAccount? = null,
    val errorMessage: StringResource? = null,
    /** Semantic Google sign-in failure; the UI resolves it to a localized string resource. */
    val googleAuthError: GoogleAuthError? = null,
    /**
     * Cached once at init time from EmailVerificationAvailability. Gates the
     * EmailVerification sub-step transition + the inline UI in [EmailVerificationStep].
     * Defaults to false so the flow degrades to its prior PasskeyCreation -> Complete
     * transition until the availability check resolves.
     */
    val isEmailVerificationAvailable: Boolean = false,
    val isVerifyingEmail: Boolean = false,
    val emailVerificationOutcome: EmailVerificationOutcome? = null,
) {
    val canContinueFromDisplayName: Boolean
        get() = displayName.isNotBlank() && displayNameError == null

    val canContinueFromUsername: Boolean
        get() =
            username.isNotBlank() &&
                usernameError == null &&
                usernameAvailability == UsernameAvailability.Available

    val canCreateAccount: Boolean
        get() =
            canContinueFromDisplayName &&
                canContinueFromUsername &&
                isPasskeySupported &&
                !isCreatingAccount
}

enum class OnboardingStep {
    Welcome,
    SignIn,
    DisplayName,
    Username,
    PasskeyCreation,
    EmailVerification,
    Complete,
}

/**
 * Outcome of the blocking first sync that follows a successful account creation or sign-in.
 *
 *  - [NotStarted]: no sync has been attempted yet.
 *  - [Running]: sync is in flight; the UI shows "Syncing your library…" instead of "Ready".
 *  - [Success]: first round-trip completed cleanly; the user has seen the server state.
 *  - [Partial]: the call finished but reported errors (e.g. some uploads failed). The user gets
 *    into the app and the global sync banner can prompt retries.
 *  - [TimedOut]: network was too slow to finish within the onboarding budget; the periodic worker
 *    will retry in the background.
 *  - [Failed]: an unexpected error escaped; surface in the banner for retry.
 */
enum class InitialSyncStatus {
    NotStarted,
    Running,
    Success,
    Partial,
    TimedOut,
    Failed,
}

private suspend fun originalBioForSignup(
    profileRepository: ProfileRepository,
    bio: String,
): String? {
    val localProfile =
        try {
            profileRepository.getCurrentProfile()
        } catch (error: Exception) {
            Napier.w("Could not read original bio for Cloud signup", error)
            null
        }
    return if (bio == localProfile?.bio) {
        localProfile.originalBio
    } else {
        bio.takeIf { it.isNotBlank() }
    }
}
