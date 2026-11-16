@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.core.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
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
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepProgress
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.step.StepTextField
import app.logdate.ui.theme.FirstLaunchTheme
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_display_name_description
import logdate.client.feature.core.generated.resources.account_name_label
import logdate.client.feature.core.generated.resources.account_username_display_name_prompt
import logdate.client.feature.core.generated.resources.enter_your_name
import logdate.client.ui.generated.resources.common_continue
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/** Display-name step of cloud account creation. */
const val CLOUD_ACCOUNT_DISPLAY_NAME_ROOT_TAG = "cloud_account_display_name_root"
const val CLOUD_ACCOUNT_DISPLAY_NAME_FIELD_TAG = "cloud_account_display_name_field"
const val CLOUD_ACCOUNT_DISPLAY_NAME_CONTINUE_TAG = "cloud_account_display_name_continue"

@Composable
fun DisplayNameSetupScreen(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    isValid: Boolean = true,
    modifier: Modifier = Modifier,
) {
    DisplayNameSetupContent(
        displayName = displayName,
        onDisplayNameChange = onDisplayNameChange,
        onContinue = onContinue,
        onBack = onBack,
        isValid = isValid,
        modifier = modifier.testTag(CLOUD_ACCOUNT_DISPLAY_NAME_ROOT_TAG),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayNameSetupContent(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    isValid: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    FirstLaunchTheme {
        StepScaffold(
            title = stringResource(Res.string.account_username_display_name_prompt),
            onBack = onBack,
            modifier = modifier,
            supportingText = stringResource(Res.string.account_display_name_description),
            progress = StepProgress(current = 1, total = 3),
            hero = { StepHeroIcon(Icons.Rounded.Badge) },
            headerAlignment = Alignment.CenterHorizontally,
            actions = {
                StepPrimaryButton(
                    text = stringResource(UiRes.string.common_continue),
                    onClick = onContinue,
                    enabled = isValid && displayName.isNotBlank(),
                    modifier = Modifier.testTag(CLOUD_ACCOUNT_DISPLAY_NAME_CONTINUE_TAG),
                )
            },
        ) {
            StepTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(Res.string.account_name_label)) },
                placeholder = { Text(stringResource(Res.string.enter_your_name)) },
                keyboardOptions =
                    KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Words,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (isValid && displayName.isNotBlank()) {
                                onContinue()
                            }
                        },
                    ),
                modifier =
                    Modifier
                        .testTag(CLOUD_ACCOUNT_DISPLAY_NAME_FIELD_TAG)
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
            )
        }
    }
}

@Preview
@Composable
private fun DisplayNameSetupScreenPreview() {
    MaterialTheme {
        Surface {
            DisplayNameSetupContent(
                displayName = "Alex Johnson",
                onDisplayNameChange = {},
                onContinue = {},
                onBack = {},
                isValid = true,
            )
        }
    }
}

@Preview
@Composable
private fun DisplayNameSetupScreenEmptyPreview() {
    MaterialTheme {
        Surface {
            DisplayNameSetupContent(
                displayName = "",
                onDisplayNameChange = {},
                onContinue = {},
                onBack = {},
                isValid = true,
            )
        }
    }
}
