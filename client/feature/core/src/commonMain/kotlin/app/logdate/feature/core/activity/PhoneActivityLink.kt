@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.activity.PhoneActivityRegistration
import app.logdate.client.domain.activity.PhoneActivityRegistrationState
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel

enum class PhoneActivityLinkState {
    Checking,
    Unavailable,
    Off,
    Requesting,
    Connecting,
    Linked,
    NeedsPermission,
    Denied,
    ProviderFailed,
    LinkWriteFailed,
    UnlinkWriteFailed,
}

data class PhoneActivityLink(
    val state: PhoneActivityLinkState,
    val onLink: () -> Unit = {},
    val onUnlink: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val optedIn: Boolean = false,
) {
    val isAvailable: Boolean get() = state != PhoneActivityLinkState.Unavailable
}

/** The same consent and effective link state for onboarding and Settings. */
@Composable
fun rememberPhoneActivityLink(): PhoneActivityLink {
    val koin = getKoin()
    val registration =
        remember(koin) { koin.getOrNull<PhoneActivityRegistration>() }
            ?: return PhoneActivityLink(PhoneActivityLinkState.Unavailable)
    val viewModel = koinViewModel<PhoneActivityLinkViewModel>()
    val effective by registration.state.collectAsStateWithLifecycle()
    val choice by viewModel.choice.collectAsStateWithLifecycle()
    val optedIn by viewModel.optedIn.collectAsStateWithLifecycle()
    val permission = rememberPhoneActivityPermission(onGranted = { viewModel.setLinked(true) })

    val state =
        when {
            effective == PhoneActivityRegistrationState.Unavailable -> PhoneActivityLinkState.Unavailable
            permission.requesting -> PhoneActivityLinkState.Requesting
            choice == PhoneActivityChoice.Saving -> PhoneActivityLinkState.Connecting
            choice == PhoneActivityChoice.UnlinkFailed -> PhoneActivityLinkState.UnlinkWriteFailed
            choice == PhoneActivityChoice.LinkFailed -> PhoneActivityLinkState.LinkWriteFailed
            permission.denied -> PhoneActivityLinkState.Denied
            else -> effective.toLinkState()
        }

    return PhoneActivityLink(
        state = state,
        onLink = {
            if (permission.granted) viewModel.setLinked(true) else permission.request()
        },
        onUnlink = { viewModel.setLinked(false) },
        onOpenSettings = permission.openSettings,
        optedIn = optedIn,
    )
}

private fun PhoneActivityRegistrationState.toLinkState(): PhoneActivityLinkState =
    when (this) {
        PhoneActivityRegistrationState.Checking -> PhoneActivityLinkState.Checking
        PhoneActivityRegistrationState.Unavailable -> PhoneActivityLinkState.Unavailable
        PhoneActivityRegistrationState.Off -> PhoneActivityLinkState.Off
        PhoneActivityRegistrationState.NeedsPermission -> PhoneActivityLinkState.NeedsPermission
        PhoneActivityRegistrationState.Starting -> PhoneActivityLinkState.Connecting
        PhoneActivityRegistrationState.Linked -> PhoneActivityLinkState.Linked
        PhoneActivityRegistrationState.Failed -> PhoneActivityLinkState.ProviderFailed
    }

internal data class PhoneActivityPermission(
    val granted: Boolean,
    val requesting: Boolean,
    val denied: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
internal expect fun rememberPhoneActivityPermission(onGranted: () -> Unit): PhoneActivityPermission
