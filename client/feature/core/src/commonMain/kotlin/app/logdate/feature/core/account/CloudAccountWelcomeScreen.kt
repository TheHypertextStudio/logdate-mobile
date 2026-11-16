@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.ui.motion.rememberDeviceParallax
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.step.StepSecondaryButton
import app.logdate.ui.theme.FirstLaunchTheme
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.cloud_welcome_body
import logdate.client.feature.core.generated.resources.cloud_welcome_custom_body
import logdate.client.feature.core.generated.resources.cloud_welcome_primary
import logdate.client.feature.core.generated.resources.cloud_welcome_secondary
import logdate.client.feature.core.generated.resources.cloud_welcome_title
import logdate.client.feature.core.generated.resources.custom_server
import org.jetbrains.compose.resources.stringResource

/** Root of the cloud account welcome step. */
const val CLOUD_ACCOUNT_WELCOME_ROOT_TAG = "cloud_account_welcome_root"
const val CLOUD_ACCOUNT_WELCOME_CREATE_TAG = "cloud_account_welcome_create"
const val CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG = "cloud_account_welcome_sign_in"

@Composable
fun CloudAccountWelcomeScreen(
    onContinue: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    serverSelectionState: ServerSelectionState,
    isPasskeySupported: Boolean = true,
    errorMessage: String? = null,
    offer: CloudOffer = CloudOffer.FreeDuringBeta,
    onSeePlans: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    CloudAccountWelcomeContent(
        onContinue = onContinue,
        onSignIn = onSignIn,
        onBack = onBack,
        serverSelectionState = serverSelectionState,
        isPasskeySupported = isPasskeySupported,
        errorMessage = errorMessage,
        offer = offer,
        onSeePlans = onSeePlans,
        // The content puts this modifier on a BoxWithConstraints wrapping both the folded and
        // standard branches, so the tag resolves to exactly one node on every device.
        modifier = modifier.testTag(CLOUD_ACCOUNT_WELCOME_ROOT_TAG),
    )
}

/**
 * The front door to LogDate Cloud: what it does, what it costs, and the two ways in.
 *
 * The words say what it does, the picture shows the same day on two devices, and the peach card
 * says what it costs. It asks one thing: make an account, or use the one you have. Where an
 * account is hosted is not asked here. Signing in works that out from the name, and making an
 * account offers it on the username step, the one place it changes anything. Someone whose app
 * already uses another server sees it named, and is not shown LogDate Cloud's price.
 */
@Composable
fun CloudAccountWelcomeContent(
    onContinue: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    serverSelectionState: ServerSelectionState,
    isPasskeySupported: Boolean = true,
    errorMessage: String? = null,
    offer: CloudOffer = CloudOffer.FreeDuringBeta,
    onSeePlans: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val custom = serverSelectionState.selectedPreset == ServerPreset.CUSTOM
    FirstLaunchTheme {
        val look by rememberDeviceParallax()
        StepScaffold(
            title = stringResource(Res.string.cloud_welcome_title),
            onBack = onBack,
            modifier = modifier,
            supportingText =
                if (custom) {
                    stringResource(Res.string.cloud_welcome_custom_body, serverSelectionState.hostLabel())
                } else {
                    stringResource(Res.string.cloud_welcome_body)
                },
            illustration = { LogDateCloudHero(look = look) },
            actions = {
                StepPrimaryButton(
                    text = stringResource(Res.string.cloud_welcome_primary),
                    onClick = onContinue,
                    modifier = Modifier.testTag(CLOUD_ACCOUNT_WELCOME_CREATE_TAG),
                    enabled = isPasskeySupported,
                )
                StepSecondaryButton(
                    text = stringResource(Res.string.cloud_welcome_secondary),
                    onClick = onSignIn,
                    modifier = Modifier.testTag(CLOUD_ACCOUNT_WELCOME_SIGN_IN_TAG),
                )
            },
            content =
                if (custom && isPasskeySupported && errorMessage == null) {
                    null
                } else {
                    {
                        if (errorMessage != null) StepProblem(errorMessage)
                        if (!isPasskeySupported) PasskeyUnsupportedBanner()
                        if (!custom) CloudOfferCard(offer, onSeePlans = onSeePlans)
                    }
                },
        )
    }
}

/** The server as a person would name it: its address without the scheme, or "Custom server". */
@Composable
internal fun ServerSelectionState.hostLabel(): String =
    customServerUrl
        .substringAfter("://")
        .trimEnd('/')
        .ifBlank { stringResource(Res.string.custom_server) }

@Composable
internal fun StepProblem(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
                .padding(Spacing.lg),
    )
}

@Preview
@Composable
private fun CloudAccountWelcomeScreenPreview() {
    CloudAccountWelcomeContent(
        onContinue = {},
        onSignIn = {},
        onBack = {},
        serverSelectionState = ServerSelectionState(),
    )
}
