package app.logdate.feature.core.main

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RetainedJournalRecoveryNoticeTest {
    @Test
    fun retainedJournalNoticeExplainsTheStateAndReopensRecoveryOptions() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var opens = 0
            setContent {
                LogDateTheme {
                    RetainedJournalRecoveryNotice(onOpenRecoveryOptions = { opens++ })
                }
            }

            onNodeWithText("Your journal is protected").assertExists()
            onNodeWithText("Open recovery options").performClick()

            assertEquals(1, opens)
        }
}
