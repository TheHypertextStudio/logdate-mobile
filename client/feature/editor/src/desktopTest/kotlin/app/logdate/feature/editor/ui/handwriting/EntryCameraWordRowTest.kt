package app.logdate.feature.editor.ui.handwriting

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class EntryCameraWordRowTest(
    private val direction: LayoutDirection,
) {
    @Test
    fun cameraModeShowsTheDockAsWordsThatStillSwitchModes() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val text = TextBlockUiState(content = "Keep this thought intact.")
            var blocks: List<EntryBlockUiState> = emptyList()
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    EntryPageStudy(initialBlocks = listOf(text), onBlocksChanged = { blocks = it })
                }
            }
            onNodeWithText("Add to entry").performClick()
            waitForIdle()
            onAllNodesWithContentDescription("Camera").onFirst().performClick()
            waitForIdle()
            assertIs<CameraBlockUiState>(blocks.last())

            onNodeWithTag("entry_capture_word_row").assertIsDisplayed()
            val camera = onNodeWithTag("entry_capture_word_Camera")
            camera.assertIsDisplayed().assertIsSelected()
            val bounds = camera.getUnclippedBoundsInRoot()
            val center = (bounds.left + bounds.right) / 2
            assertTrue(abs(center.value - 206f) < 2f, "Camera should sit in the middle of the row, was at $center")
            assertTrue(bounds.bottom > 915.dp * 0.85f, "The word row should sit by the bottom edge, was at ${bounds.bottom}")

            onNodeWithTag("entry_capture_word_Handwrite").performClick()
            waitForIdle()
            assertIs<HandwritingBlockUiState>(blocks.last())
            assertEquals(2, blocks.size)
            assertEquals(text.content, blocks.filterIsInstance<TextBlockUiState>().single().content)
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions() = listOf(arrayOf(LayoutDirection.Ltr), arrayOf(LayoutDirection.Rtl))
    }
}
