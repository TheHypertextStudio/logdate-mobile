@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.account.GoogleAuthError
import app.logdate.client.sync.SyncStatus
import app.logdate.feature.core.settings.ui.CustomServerInfoBottomSheet
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.sync.SyncProgressIndicator
import app.logdate.shared.model.ServerDescriptor
import app.logdate.ui.common.AlertDialog
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_adopt_local_data_body
import logdate.client.feature.core.generated.resources.account_adopt_local_data_confirm
import logdate.client.feature.core.generated.resources.account_adopt_local_data_title
import logdate.client.feature.core.generated.resources.custom_server
import logdate.client.feature.core.generated.resources.first_sync_failed
import logdate.client.feature.core.generated.resources.first_sync_partial
import logdate.client.feature.core.generated.resources.first_sync_progress
import logdate.client.feature.core.generated.resources.first_sync_running
import logdate.client.feature.core.generated.resources.first_sync_success
import logdate.client.feature.core.generated.resources.first_sync_timed_out
import logdate.client.feature.core.generated.resources.first_sync_timed_out_progress
import logdate.client.feature.core.generated.resources.google_sign_in_account_conflict
import logdate.client.feature.core.generated.resources.google_sign_in_cancelled
import logdate.client.feature.core.generated.resources.google_sign_in_failed
import logdate.client.feature.core.generated.resources.google_sign_in_network_error
import logdate.client.feature.core.generated.resources.google_sign_in_no_account
import logdate.client.feature.core.generated.resources.google_sign_in_rate_limited
import logdate.client.feature.core.generated.resources.google_sign_in_server_error
import logdate.client.feature.core.generated.resources.google_sign_in_unavailable
import logdate.client.feature.core.generated.resources.logdate_cloud
import logdate.client.feature.core.generated.resources.sign_in_help_body
import logdate.client.feature.core.generated.resources.sign_in_help_title
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_dismiss
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

private const val FALLBACK_HANDLE_DOMAIN = "logdate.app"
private const val FALLBACK_PRIVACY_POLICY_URL = "https://logdate.app/privacy"
private const val FALLBACK_TERMS_OF_SERVICE_URL = "https://logdate.app/terms"

/** The blocking first sync shown once an account exists. */
const val CLOUD_ACCOUNT_INITIAL_SYNC_ROOT_TAG = "cloud_account_initial_sync_root"

/** Resolves a semantic [GoogleAuthError] to a localized message string. */
@Composable
private fun googleAuthErrorMessage(error: GoogleAuthError): String =
    stringResource(
        when (error) {
            GoogleAuthError.Cancelled -> Res.string.google_sign_in_cancelled
            GoogleAuthError.NoGoogleAccount -> Res.string.google_sign_in_no_account
            GoogleAuthError.NotConfigured -> Res.string.google_sign_in_unavailable
            GoogleAuthError.InvalidToken -> Res.string.google_sign_in_failed
            GoogleAuthError.AccountLinkConflict -> Res.string.google_sign_in_account_conflict
            GoogleAuthError.RateLimited -> Res.string.google_sign_in_rate_limited
            GoogleAuthError.NetworkError -> Res.string.google_sign_in_network_error
            GoogleAuthError.ServerError -> Res.string.google_sign_in_server_error
            is GoogleAuthError.Unknown -> Res.string.google_sign_in_failed
        },
    )

/**
 * Cloud account onboarding screen reusable from both onboarding and settings.
 *
 * By default the flow starts at [OnboardingStep.Welcome]. Call
 * [CloudAccountOnboardingViewModel.setInitialStep] before this composable
 * renders to skip the welcome pitch (e.g. when entering from settings where
 * the user has already seen a promotional screen).
 *
 * **Side-effects observed via [LaunchedEffect]:**
 * - [CloudAccountOnboardingUiState.isAccountCreated] / [CloudAccountOnboardingUiState.isSignedIn] → calls [onAccountCreated]
 * - [CloudAccountOnboardingUiState.isSkipped] → calls [onSkipOnboarding]
 * - [CloudAccountOnboardingUiState.isExitRequested] → calls [onBack]
 *
 * @param onBack Called when the user navigates back past the entry step.
 */
@Composable
fun CloudAccountOnboardingScreen(
    viewModel: CloudAccountOnboardingViewModel,
    onAccountCreated: () -> Unit,
    onSkipOnboarding: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val showCustomServerInfo = remember { mutableStateOf(false) }
    val showRecoveryInfo = remember { mutableStateOf(false) }
    val productionDisplayName = stringResource(Res.string.logdate_cloud)
    val customDisplayName = stringResource(Res.string.custom_server)
    val serverPresentation =
        remember(uiState.serverSelectionState, productionDisplayName, customDisplayName) {
            uiState.serverSelectionState.toPresentation(productionDisplayName, customDisplayName)
        }

    if (showCustomServerInfo.value) {
        CustomServerInfoBottomSheet(
            onDismiss = { showCustomServerInfo.value = false },
            onUseCustomServer = {
                viewModel.selectServerPreset(ServerPreset.CUSTOM)
                showCustomServerInfo.value = false
            },
        )
    }

    if (showRecoveryInfo.value) {
        AlertDialog(
            onDismissRequest = { showRecoveryInfo.value = false },
            title = { Text(stringResource(Res.string.sign_in_help_title)) },
            text = { Text(stringResource(Res.string.sign_in_help_body)) },
            confirmButton = {
                TextButton(onClick = { showRecoveryInfo.value = false }) {
                    Text(stringResource(UiRes.string.common_dismiss))
                }
            },
        )
    }

    // Signing in on a device that has already been written in folds those entries into the
    // account. That is usually exactly what someone wants on a second device, but it is not
    // something to do without saying so.
    uiState.pendingLocalDataAdoption?.let { pendingUsername ->
        AlertDialog(
            onDismissRequest = viewModel::dismissLocalDataAdoption,
            title = { Text(stringResource(Res.string.account_adopt_local_data_title)) },
            text = { Text(stringResource(Res.string.account_adopt_local_data_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.signInAdoptingLocalData(pendingUsername) }) {
                    Text(stringResource(Res.string.account_adopt_local_data_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLocalDataAdoption) {
                    Text(stringResource(UiRes.string.common_cancel))
                }
            },
        )
    }

    // Handle completion and exit
    LaunchedEffect(uiState.isAccountCreated, uiState.isSignedIn, uiState.isSkipped, uiState.isExitRequested) {
        if (uiState.isAccountCreated || uiState.isSignedIn) {
            onAccountCreated()
        } else if (uiState.isSkipped) {
            onSkipOnboarding()
        } else if (uiState.isExitRequested) {
            onBack()
        }
    }

    // LogDateTheme supplies the background and content colour; this only has to keep the step
    // indicator and progress bar clear of the status bar, which none of these steps did.
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when (uiState.currentStep) {
            OnboardingStep.Welcome -> {
                CloudAccountWelcomeScreen(
                    onContinue = viewModel::goToNextStep,
                    onSignIn = viewModel::goToSignIn,
                    onSkip = viewModel::skipOnboarding,
                    serverSelectionState = uiState.serverSelectionState,
                    onSelectServerPreset = viewModel::selectServerPreset,
                    onCustomServerUrlChange = viewModel::updateCustomServerUrl,
                    onShowCustomServerInfo = { showCustomServerInfo.value = true },
                    isPasskeySupported = uiState.isPasskeySupported,
                    modifier = modifier,
                )
            }

            OnboardingStep.SignIn -> {
                val googleAuthError = uiState.googleAuthError
                val signInError =
                    uiState.errorMessage?.let { stringResource(it) }
                        ?: if (googleAuthError != null) googleAuthErrorMessage(googleAuthError) else null
                CloudAccountSignInScreen(
                    onSignIn = viewModel::signInWithPasskey,
                    onAccountRecovery = { showRecoveryInfo.value = true },
                    onPrivacyPolicy = serverPresentation.privacyPolicyUrl?.let { { uriHandler.openUri(it) } },
                    onTermsOfService = serverPresentation.termsOfServiceUrl?.let { { uriHandler.openUri(it) } },
                    onBack = viewModel::goToPreviousStep,
                    isSigningIn = uiState.isSigningIn,
                    errorMessage = signInError,
                    onClearError = viewModel::clearError,
                    onSignInWithGoogle =
                        if (uiState.isGoogleSignInAvailable) viewModel::signInWithGoogle else null,
                    serverDisplayName = serverPresentation.displayName,
                    serverHandleDomain = serverPresentation.handleDomain,
                    modifier = modifier,
                )
            }

            OnboardingStep.DisplayName -> {
                DisplayNameSetupScreen(
                    displayName = uiState.displayName,
                    onDisplayNameChange = viewModel::updateDisplayName,
                    onContinue = viewModel::goToNextStep,
                    onBack = viewModel::goToPreviousStep,
                    isValid = uiState.canContinueFromDisplayName,
                    modifier = modifier,
                )
            }

            OnboardingStep.Username -> {
                val stepCount = if (uiState.hasProfileDisplayName) 2 else 3
                UsernameSetupScreen(
                    username = uiState.username,
                    onUsernameChange = viewModel::updateUsername,
                    onContinue = viewModel::goToNextStep,
                    onBack = viewModel::goToPreviousStep,
                    usernameAvailability = uiState.usernameAvailability,
                    isValid = uiState.canContinueFromUsername,
                    handleDomain = serverPresentation.handleDomain,
                    stepNumber = stepCount - 1,
                    stepCount = stepCount,
                    modifier = modifier,
                )
            }

            OnboardingStep.PasskeyCreation -> {
                // The DisplayName step is skipped when onboarding already captured a name, so the
                // stepper reports the number of steps this user actually sees.
                val stepCount = if (uiState.hasProfileDisplayName) 2 else 3
                PasskeyAccountCreationFinalContent(
                    displayName = uiState.displayName,
                    username = uiState.username,
                    onCreateAccount = viewModel::createAccount,
                    onBack = viewModel::goToPreviousStep,
                    isCreatingAccount = uiState.isCreatingAccount,
                    errorMessage = uiState.errorMessage?.let { stringResource(it) },
                    onClearError = viewModel::clearError,
                    isPasskeySupported = uiState.isPasskeySupported,
                    serverDisplayName = serverPresentation.displayName,
                    stepNumber = stepCount,
                    stepCount = stepCount,
                    modifier = modifier,
                    onEditUsername = viewModel::goToPreviousStep,
                    onDisplayNameChange = viewModel::updateDisplayName,
                )
            }

            OnboardingStep.EmailVerification -> {
                EmailVerificationStep(
                    isVerifying = uiState.isVerifyingEmail,
                    outcome = uiState.emailVerificationOutcome,
                    onVerifyClick = viewModel::onVerifyEmailClicked,
                    onSkip = viewModel::onSkipEmailVerification,
                    onContinue = viewModel::goToNextStep,
                    modifier = modifier,
                )
            }

            OnboardingStep.Complete -> {
                // Stay here until the first sync finishes so the user sees we're actually doing
                // something; the completion LaunchedEffect above navigates away once isAccountCreated
                // / isSignedIn flips, which [CloudAccountOnboardingViewModel.performInitialSync] only
                // sets after sync settles.
                InitialSyncProgressScreen(
                    status = uiState.initialSyncStatus,
                    syncStatus = syncStatus,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun InitialSyncProgressScreen(
    status: InitialSyncStatus,
    syncStatus: SyncStatus?,
    modifier: Modifier = Modifier,
) {
    // The one screen where a real count matters most: it is shown while several hundred entries
    // upload, and a bare spinner leaves the difference between a moment and an hour unsaid.
    val total = syncStatus?.totalForRun
    val completed = syncStatus?.completedInRun ?: 0

    Column(
        modifier = modifier.testTag(CLOUD_ACCOUNT_INITIAL_SYNC_ROOT_TAG).fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Material 3 Expressive's loading indicator, which morphs through a shape sequence rather
        // than spinning a ring - determinate when there is a real fraction to show. Only the
        // Running status has a fraction worth trusting; other statuses fall back to indeterminate.
        SyncProgressIndicator(
            total = total.takeIf { status == InitialSyncStatus.Running },
            completed = completed,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text =
                when (val message = initialSyncMessageFor(status, total, completed)) {
                    is InitialSyncMessage.Progress -> {
                        stringResource(Res.string.first_sync_progress, message.completed, message.total)
                    }

                    InitialSyncMessage.Running -> {
                        stringResource(Res.string.first_sync_running)
                    }

                    InitialSyncMessage.Success -> {
                        stringResource(Res.string.first_sync_success)
                    }

                    InitialSyncMessage.Partial -> {
                        stringResource(Res.string.first_sync_partial)
                    }

                    is InitialSyncMessage.TimedOut -> {
                        if (message.total != null && message.total > 0) {
                            stringResource(Res.string.first_sync_timed_out_progress, message.completed, message.total)
                        } else {
                            stringResource(Res.string.first_sync_timed_out)
                        }
                    }

                    InitialSyncMessage.Failed -> {
                        stringResource(Res.string.first_sync_failed)
                    }

                    InitialSyncMessage.None -> {
                        ""
                    }
                },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/** What [InitialSyncProgressScreen] should say, decided independently of Compose so it's testable. */
internal sealed class InitialSyncMessage {
    data class Progress(
        val completed: Int,
        val total: Int,
    ) : InitialSyncMessage()

    data object Running : InitialSyncMessage()

    data object Success : InitialSyncMessage()

    data object Partial : InitialSyncMessage()

    /**
     * The blocking onboarding wait gave up, but the run may have gotten partway through before
     * that happened -- when it did, say so instead of falling back to a purely generic message,
     * since "247 of 312 synced, continuing in the background" is far more reassuring than silence
     * about what actually happened.
     */
    data class TimedOut(
        val completed: Int,
        val total: Int?,
    ) : InitialSyncMessage()

    data object Failed : InitialSyncMessage()

    data object None : InitialSyncMessage()
}

internal fun initialSyncMessageFor(
    status: InitialSyncStatus,
    total: Int?,
    completed: Int,
): InitialSyncMessage =
    when (status) {
        InitialSyncStatus.Running -> {
            if (total != null && total > 0) {
                InitialSyncMessage.Progress(completed, total)
            } else {
                InitialSyncMessage.Running
            }
        }

        InitialSyncStatus.Success -> {
            InitialSyncMessage.Success
        }

        InitialSyncStatus.Partial -> {
            InitialSyncMessage.Partial
        }

        InitialSyncStatus.TimedOut -> {
            InitialSyncMessage.TimedOut(completed, total)
        }

        InitialSyncStatus.Failed -> {
            InitialSyncMessage.Failed
        }

        InitialSyncStatus.NotStarted -> {
            InitialSyncMessage.None
        }
    }

private data class ServerPresentation(
    val displayName: String,
    val handleDomain: String,
    val privacyPolicyUrl: String?,
    val termsOfServiceUrl: String?,
)

private fun app.logdate.feature.core.settings.ui.ServerSelectionState.toPresentation(
    productionDisplayName: String,
    customDisplayName: String,
): ServerPresentation {
    val descriptor = activeServerDescriptor
    val isProduction = selectedPreset == ServerPreset.PRODUCTION
    return ServerPresentation(
        displayName = descriptor.displayNameOrFallback(isProduction, productionDisplayName, customDisplayName),
        handleDomain = descriptor?.handleDomain ?: if (isProduction) FALLBACK_HANDLE_DOMAIN else "your-server.example.com",
        privacyPolicyUrl = descriptor?.privacyPolicyUrl ?: if (isProduction) FALLBACK_PRIVACY_POLICY_URL else null,
        termsOfServiceUrl = descriptor?.termsOfServiceUrl ?: if (isProduction) FALLBACK_TERMS_OF_SERVICE_URL else null,
    )
}

private fun ServerDescriptor?.displayNameOrFallback(
    isProduction: Boolean,
    productionDisplayName: String,
    customDisplayName: String,
): String = this?.displayName ?: if (isProduction) productionDisplayName else customDisplayName
