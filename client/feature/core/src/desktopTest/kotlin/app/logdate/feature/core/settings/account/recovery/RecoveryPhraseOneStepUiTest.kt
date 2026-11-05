package app.logdate.feature.core.settings.account.recovery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test

/**
 * Recovery settings reveal, copy, and hide the stored phrase without a setup confirmation.
 */
@OptIn(ExperimentalTestApi::class)
class RecoveryPhraseOneStepUiTest {
    private val words = List(12) { "word${it + 1}" }

    @Test
    fun showingAndHidingThePhraseNeverAsksForSetup() =
        runDesktopComposeUiTest(width = 720, height = 1600) {
            var state by mutableStateOf<RecoveryPhraseUiState>(RecoveryPhraseUiState.Available())
            setContent {
                LogDateTheme {
                    RecoveryPhraseContent(
                        state = state,
                        onBack = {},
                        onReveal = { state = RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(words)) },
                        onHide = { state = RecoveryPhraseUiState.Available() },
                        onEnterPhrase = {},
                    )
                }
            }

            onNodeWithText("Show recovery phrase").assertIsDisplayed().performClick()
            onNodeWithText("word1").assertIsDisplayed()
            onNodeWithText("Copy phrase").assertIsDisplayed()
            onNodeWithText("Hide").assertIsDisplayed()
            onAllNodes(hasSetTextAction()).assertCountEquals(0)

            onAllNodesWithText("I’ve saved my recovery phrase").assertCountEquals(0)
            onNodeWithText("Hide").performClick()
            onNodeWithText("Show recovery phrase").assertIsDisplayed()
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            onAllNodesWithText("Verify", substring = true, ignoreCase = true).assertCountEquals(0)
        }

    @Test
    fun accountRecoveryThatNeedsAPasskeyIsShownBeforeAnyCopyIsSaved() =
        runDesktopComposeUiTest(width = 720, height = 1600) {
            var finishes = 0
            setContent {
                LogDateTheme {
                    RecoveryPhraseContent(
                        state = RecoveryPhraseUiState.Available(),
                        onBack = {},
                        onReveal = {},
                        onHide = {},
                        onEnterPhrase = {},
                        accountRecovery = AccountRecoveryUiState.NeedsSetup,
                        onFinishAccountRecovery = { finishes++ },
                    )
                }
            }

            onNode(hasScrollAction()).performScrollToNode(hasText("Finish setting up"))
            onNodeWithText("Finish setting up").assertIsDisplayed().performClick()
            kotlin.test.assertEquals(1, finishes)
        }
}
