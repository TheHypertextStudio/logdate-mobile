package app.logdate.feature.editor.ui.handwriting

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EntryStartWritingFlowTest {
    @Test
    fun `handwriting paper expands from its writing tile control`() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent { LogDateTheme(dynamicColor = false) { EntryStartPrototype() } }
            val source = onNodeWithTag("editor_start_handwriting_block").getBoundsInRoot()
            mainClock.autoAdvance = false
            onNodeWithTag("editor_start_handwriting_block").performClick()
            mainClock.advanceTimeBy(32)
            val openingStart = onNodeWithTag("handwriting_drawing_area").getBoundsInRoot()
            assertTrue(
                openingStart.top.value >= source.top.value - 40f,
                "Canvas must start inside the writing tile: $openingStart / $source",
            )
            mainClock.advanceTimeBy(176)
            val opening = onNodeWithTag("handwriting_drawing_area").getBoundsInRoot()
            assertTrue(opening.top.value > 1f && opening.top < source.top, "Canvas must travel into focus: $opening / $source")
            mainClock.advanceTimeBy(800)
            val focused = onNodeWithTag("handwriting_drawing_area").getBoundsInRoot()
            assertTrue(focused.top.value <= 1f && focused.bottom.value >= 799f, "Paper fills the editor: $focused")
            onNodeWithTag("earlier_today").assertDoesNotExist()
            onNodeWithContentDescription("Back").performClick()
            mainClock.advanceTimeBy(208)
            val closing = onNodeWithTag("handwriting_drawing_area").getBoundsInRoot()
            assertTrue(closing.top.value > 1f, "Canvas must travel back into the normal block: $closing")
            mainClock.advanceTimeBy(800)
            onNodeWithTag("earlier_today").assertExists()
            onNodeWithTag("handwriting_preview", useUnmergedTree = true).assertExists()
        }
}
