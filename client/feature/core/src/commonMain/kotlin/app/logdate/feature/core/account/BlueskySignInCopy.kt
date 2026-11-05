package app.logdate.feature.core.account

import app.logdate.client.repository.account.BlueskySignInFailure
import app.logdate.client.repository.account.BlueskySignInState
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.bluesky_approval_body
import logdate.client.feature.core.generated.resources.bluesky_approval_title
import logdate.client.feature.core.generated.resources.bluesky_browser_body
import logdate.client.feature.core.generated.resources.bluesky_browser_title
import logdate.client.feature.core.generated.resources.bluesky_canceled
import logdate.client.feature.core.generated.resources.bluesky_check_handle
import logdate.client.feature.core.generated.resources.bluesky_check_profile
import logdate.client.feature.core.generated.resources.bluesky_confirm_body
import logdate.client.feature.core.generated.resources.bluesky_confirm_title
import logdate.client.feature.core.generated.resources.bluesky_connecting
import logdate.client.feature.core.generated.resources.bluesky_consent_body
import logdate.client.feature.core.generated.resources.bluesky_consent_title
import logdate.client.feature.core.generated.resources.bluesky_context_changed
import logdate.client.feature.core.generated.resources.bluesky_continue
import logdate.client.feature.core.generated.resources.bluesky_expired
import logdate.client.feature.core.generated.resources.bluesky_intro
import logdate.client.feature.core.generated.resources.bluesky_unavailable
import logdate.client.feature.core.generated.resources.bluesky_unavailable_body
import logdate.client.feature.core.generated.resources.bluesky_unavailable_title
import org.jetbrains.compose.resources.StringResource

internal fun blueskyTitle(
    ui: BlueskySignInUiState,
    stage: BlueskySignInState,
): StringResource =
    when {
        !ui.availabilityResolved -> Res.string.bluesky_connecting
        !ui.available -> Res.string.bluesky_unavailable_title
        stage is BlueskySignInState.AwaitingBrowser -> Res.string.bluesky_browser_title
        stage is BlueskySignInState.ConfirmPasskey -> Res.string.bluesky_confirm_title
        stage is BlueskySignInState.DeviceApproval -> Res.string.bluesky_approval_title
        stage is BlueskySignInState.LocalDataConsent -> Res.string.bluesky_consent_title
        stage is BlueskySignInState.Working || stage is BlueskySignInState.Complete -> Res.string.bluesky_connecting
        else -> Res.string.bluesky_continue
    }

internal fun blueskyDescription(
    ui: BlueskySignInUiState,
    stage: BlueskySignInState,
): StringResource? =
    when {
        !ui.availabilityResolved -> null
        !ui.available -> Res.string.bluesky_unavailable_body
        stage is BlueskySignInState.AwaitingBrowser -> Res.string.bluesky_browser_body
        stage is BlueskySignInState.ConfirmPasskey -> Res.string.bluesky_confirm_body
        stage is BlueskySignInState.DeviceApproval -> Res.string.bluesky_approval_body
        stage is BlueskySignInState.LocalDataConsent ->
            if (stage.requiresNewSignIn) Res.string.bluesky_expired else Res.string.bluesky_consent_body
        stage is BlueskySignInState.Working || stage is BlueskySignInState.Complete -> null
        else -> Res.string.bluesky_intro
    }

internal fun blueskyFailure(
    failure: BlueskySignInFailure,
    stage: BlueskySignInState,
): StringResource =
    when (failure) {
        BlueskySignInFailure.CANCELED -> Res.string.bluesky_canceled
        BlueskySignInFailure.EXPIRED -> Res.string.bluesky_expired
        BlueskySignInFailure.CONTEXT_CHANGED -> Res.string.bluesky_context_changed
        BlueskySignInFailure.UNAVAILABLE -> Res.string.bluesky_unavailable
        BlueskySignInFailure.INVALID_REQUEST ->
            if (stage is BlueskySignInState.AccountDetails) Res.string.bluesky_check_profile else Res.string.bluesky_check_handle
    }
