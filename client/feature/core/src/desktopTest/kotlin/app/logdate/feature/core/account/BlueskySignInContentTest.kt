package app.logdate.feature.core.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BlueskySignInContentTest {
    @Test
    fun `browser sign in needs an entered handle and an explicit action`() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            var ui by mutableStateOf(BlueskySignInUiState(available = true))
            var starts = 0
            setContent {
                MaterialTheme {
                    BlueskySignInContent(
                        ui = ui,
                        stage = BlueskySignInState.Idle,
                        accountSetupContent = {},
                        onHandleChange = { ui = ui.copy(handle = it) },
                        onStart = { starts++ },
                        onConfirm = {},
                        onAdopt = {},
                        onRestart = {},
                        onCancel = {},
                    )
                }
            }
            onNodeWithTag("bluesky_start").assertIsNotEnabled()
            onNodeWithTag("bluesky_handle").performTextInput("alice.test")
            onNodeWithTag("bluesky_start").assertIsEnabled().performClick()
            assertEquals(1, starts)
        }

    @Test
    fun `account creation delegates to the existing signup UI`() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val profile = AccountCreationRequest("alice", "Alice")
            var creates = 0
            setContent {
                MaterialTheme {
                    BlueskySignInContent(
                        ui = BlueskySignInUiState(available = true, details = profile, detailsLocked = true),
                        stage = BlueskySignInState.AccountDetails(profile),
                        accountSetupContent = {
                            PasskeyAccountCreationFinalContent(
                                displayName = "Alice",
                                username = "alice",
                                onCreateAccount = { creates++ },
                                onBack = {},
                                isCreatingAccount = false,
                                errorMessage = null,
                                onClearError = {},
                                isPasskeySupported = true,
                                serverDisplayName = "LogDate",
                                stepNumber = 1,
                                stepCount = 1,
                                detailsEditable = false,
                            )
                        },
                        onHandleChange = {},
                        onStart = {},
                        onConfirm = {},
                        onAdopt = {},
                        onRestart = {},
                        onCancel = {},
                    )
                }
            }
            onNodeWithTag(CLOUD_ACCOUNT_PASSKEY_ROOT_TAG).assertIsDisplayed()
            assertEquals(0, creates)
            onNodeWithTag(CLOUD_ACCOUNT_PASSKEY_CREATE_TAG).assertIsEnabled().performClick()
            assertEquals(1, creates)
        }

    @Test
    fun `spent approval offers a fresh flow rather than another passkey submission`() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            var restarts = 0
            setContent {
                MaterialTheme {
                    BlueskySignInContent(
                        ui = BlueskySignInUiState(available = true),
                        stage = BlueskySignInState.DeviceApproval(true),
                        accountSetupContent = {},
                        onHandleChange = {},
                        onStart = {},
                        onConfirm = {},
                        onAdopt = {},
                        onRestart = { restarts++ },
                        onCancel = {},
                    )
                }
            }
            onNodeWithTag("bluesky_confirm").assertDoesNotExist()
            onNodeWithTag("bluesky_restart").performClick()
            assertEquals(1, restarts)
        }
}
