package app.logdate.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.restore.LocalAcknowledgeCloudRestore
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.timeline.newstuff.PostRestoreEmptyState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PostRestoreEmptyStateTest {
    @Test
    fun `manual sign in stays available without acknowledging local recovery`() =
        runDesktopComposeUiTest(width = 360, height = 640) {
            var signIn = 0
            var acknowledged = 0
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalAcknowledgeCloudRestore provides { acknowledged++ }) {
                        PostRestoreEmptyState(
                            onImportBackup = {},
                            onStartWriting = {},
                            onSignIn = { signIn++ },
                        )
                    }
                }
            }

            onNodeWithText("Sign in to restore your account").assertIsDisplayed().performClick()

            assertEquals(1, signIn)
            assertEquals(0, acknowledged)
        }
}
