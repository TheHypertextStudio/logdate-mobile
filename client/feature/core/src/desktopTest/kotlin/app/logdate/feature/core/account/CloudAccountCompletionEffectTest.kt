package app.logdate.feature.core.account

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CloudAccountCompletionEffectTest {
    @Test
    fun `completed sign in requests cloud restore`() =
        runDesktopComposeUiTest {
            val calls = mutableListOf<String>()
            setContent {
                CloudAccountCompletionEffect(
                    isSignedIn = true,
                    isAccountCreated = false,
                    isSkipped = false,
                    isExitRequested = false,
                    onSignedIn = { calls += "restore" },
                    onAccountCreated = { calls += "created" },
                    onSkipped = {},
                    onBack = {},
                )
            }

            waitForIdle()
            assertEquals(listOf("restore"), calls)
        }
}
