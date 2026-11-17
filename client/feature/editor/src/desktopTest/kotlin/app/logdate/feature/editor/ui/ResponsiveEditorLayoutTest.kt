package app.logdate.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.layout.ImmersiveEditorLayout
import app.logdate.feature.editor.ui.state.BlocksUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ResponsiveEditorLayoutTest {
    @Test
    fun `wide editor respects its readable content width`() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            setContent {
                ImmersiveEditorLayout(
                    topBarContent = {},
                    editorContent = { Box(Modifier.fillMaxSize().testTag("editor")) },
                    bottomContent = {},
                )
            }
            val bounds = onNodeWithTag("editor").getBoundsInRoot()
            assertTrue((bounds.right - bounds.left) <= 1200.dp, "Editor stretched to ${(bounds.right - bounds.left)}")
            assertTrue(bounds.left > 0.dp, "Editor should be centered")
        }

    @Test
    fun `short window leaves room for journal controls without overlapping editor`() =
        runDesktopComposeUiTest(width = 700, height = 280) {
            setContent {
                ImmersiveEditorLayout(
                    topBarContent = {},
                    editorContent = { Box(Modifier.fillMaxSize().testTag("editor")) },
                    bottomContent = { Box(Modifier.size(100.dp, 48.dp).testTag("journal")) },
                )
            }
            val editor = onNodeWithTag("editor").getBoundsInRoot()
            val journal = onNodeWithTag("journal").getBoundsInRoot()
            assertTrue(editor.bottom <= journal.top, "$editor overlaps $journal")
            assertTrue(journal.bottom <= 280.dp, "Journal controls are outside the window")
        }

    @Test
    fun `entry keeps adding available and preserves text selection through resizing`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            var block by mutableStateOf(TextBlockUiState(content = "A morning with Milo"))
            var width by mutableStateOf(1100.dp)
            setContent {
                MaterialTheme {
                    Box(Modifier.width(width)) {
                        MainEditorContent(
                            uiState =
                                BlocksUiState(
                                    blocks = listOf(block),
                                    availableJournals = emptyList(),
                                    selectedJournalIds = emptyList(),
                                    onBlockFocused = {},
                                    onJournalSelectionChanged = {},
                                    onUpdateBlock = { block = it as TextBlockUiState },
                                    onCreateBlock = { _, _ -> block },
                                    onDeleteBlock = {},
                                ),
                            shouldReturnToPickerOnBack = false,
                            onDismissExpanded = {},
                        )
                    }
                }
            }
            val draft = onNodeWithText("A morning with Milo").getBoundsInRoot()
            val add = onNodeWithTag("add_to_entry").getBoundsInRoot()
            assertTrue(add.top > draft.bottom, "Add action should stay below the scrolling entry")
            onNodeWithText("A morning with Milo").performTextReplacement("Milo's first camping trip")
            onNodeWithText("Milo's first camping trip").performTextInputSelection(TextRange(2, 6))
            runOnIdle { width = 380.dp }
            assertEquals(
                TextRange(2, 6),
                onNodeWithText("Milo's first camping trip").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
            )
            onNodeWithTag("add_to_entry").assertExists()
            runOnIdle { width = 1100.dp }
            assertEquals(
                TextRange(2, 6),
                onNodeWithText("Milo's first camping trip").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
            )
            onNodeWithTag("add_to_entry").assertExists()
        }
}
