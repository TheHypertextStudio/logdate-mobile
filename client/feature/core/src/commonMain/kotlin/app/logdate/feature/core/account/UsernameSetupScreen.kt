@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports", "ktlint:standard:max-line-length")

package app.logdate.feature.core.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepProgress
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_username_choose_screen_title
import logdate.client.feature.core.generated.resources.account_username_domain_hint
import logdate.client.feature.core.generated.resources.account_username_network_address_description
import logdate.client.feature.core.generated.resources.account_username_requirements
import logdate.client.feature.core.generated.resources.at
import logdate.client.feature.core.generated.resources.error_checking_username
import logdate.client.feature.core.generated.resources.username
import logdate.client.feature.core.generated.resources.username_available
import logdate.client.feature.core.generated.resources.username_is_already_taken
import logdate.client.feature.core.generated.resources.username_is_available_2
import logdate.client.feature.core.generated.resources.username_taken
import logdate.client.feature.core.generated.resources.your_username
import logdate.client.ui.generated.resources.common_continue
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/** Username step of cloud account creation. */
const val CLOUD_ACCOUNT_USERNAME_ROOT_TAG = "cloud_account_username_root"
const val CLOUD_ACCOUNT_USERNAME_FIELD_TAG = "cloud_account_username_field"
const val CLOUD_ACCOUNT_USERNAME_CONTINUE_TAG = "cloud_account_username_continue"

@Composable
fun UsernameSetupScreen(
    username: String,
    onUsernameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    handleDomain: String,
    stepNumber: Int,
    stepCount: Int,
    usernameAvailability: UsernameAvailability = UsernameAvailability.Unknown,
    isValid: Boolean = true,
    modifier: Modifier = Modifier,
) {
    UsernameSetupContent(
        username = username,
        onUsernameChange = onUsernameChange,
        onContinue = onContinue,
        onBack = onBack,
        handleDomain = handleDomain,
        stepNumber = stepNumber,
        stepCount = stepCount,
        usernameAvailability = usernameAvailability,
        isValid = isValid,
        modifier = modifier.testTag(CLOUD_ACCOUNT_USERNAME_ROOT_TAG),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsernameSetupContent(
    username: String,
    onUsernameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    handleDomain: String,
    stepNumber: Int,
    stepCount: Int,
    usernameAvailability: UsernameAvailability,
    isValid: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    StepScaffold(
        title = stringResource(Res.string.account_username_choose_screen_title),
        onBack = onBack,
        modifier = modifier,
        supportingText = stringResource(Res.string.account_username_network_address_description),
        progress = if (stepCount > 0) StepProgress(current = stepNumber, total = stepCount) else null,
        hero = { StepHeroIcon(Icons.Rounded.AlternateEmail) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            Button(
                onClick = onContinue,
                enabled = isValid && username.isNotBlank() && usernameAvailability == UsernameAvailability.Available,
                modifier = Modifier.fillMaxWidth().testTag(CLOUD_ACCOUNT_USERNAME_CONTINUE_TAG),
            ) {
                Text(stringResource(UiRes.string.common_continue))
            }
        },
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(Res.string.username)) },
            placeholder = { Text(stringResource(Res.string.your_username)) },
            prefix = { Text(stringResource(Res.string.at)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.AlternateEmail,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                when (usernameAvailability) {
                    UsernameAvailability.Checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    UsernameAvailability.Available -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(Res.string.username_available),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    UsernameAvailability.Taken -> {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = stringResource(Res.string.username_taken),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }

                    UsernameAvailability.Error -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(Res.string.error_checking_username),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }

                    UsernameAvailability.Unknown -> {
                        null
                    }
                }
            },
            supportingText = {
                when (usernameAvailability) {
                    UsernameAvailability.Available -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(Res.string.username_is_available_2),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    UsernameAvailability.Taken -> {
                        Text(
                            text = stringResource(Res.string.username_is_already_taken),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        Text(stringResource(Res.string.account_username_domain_hint, handleDomain))
                    }
                }
            },
            isError = usernameAvailability == UsernameAvailability.Taken,
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (isValid && username.isNotBlank() && usernameAvailability == UsernameAvailability.Available) {
                            onContinue()
                        }
                    },
                ),
            modifier =
                Modifier
                    .testTag(CLOUD_ACCOUNT_USERNAME_FIELD_TAG)
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
        )

        // The constraints that actually change what you can type, in one line.
        // This screen previously stacked an ActivityPub explainer and a bulleted tips
        // card beneath the field, which buried the single decision it exists for.
        Text(
            text = stringResource(Res.string.account_username_requirements),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun UsernameSetupScreenPreview() {
    MaterialTheme {
        Surface {
            UsernameSetupContent(
                username = "alex_j",
                onUsernameChange = {},
                onContinue = {},
                onBack = {},
                handleDomain = "logdate.app",
                stepNumber = 1,
                stepCount = 2,
                usernameAvailability = UsernameAvailability.Available,
                isValid = true,
            )
        }
    }
}

@Preview
@Composable
private fun UsernameSetupScreenTakenPreview() {
    MaterialTheme {
        Surface {
            UsernameSetupContent(
                username = "admin",
                onUsernameChange = {},
                onContinue = {},
                onBack = {},
                handleDomain = "logdate.app",
                stepNumber = 1,
                stepCount = 2,
                usernameAvailability = UsernameAvailability.Taken,
                isValid = false,
            )
        }
    }
}
