package app.logdate.feature.editor.ui.handwriting

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.editor.ui.EntryEditorTopBar
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EntryFocusAffordanceTest {
    @Test
    fun doneReturnsToEntryAndSaveIsASeparateExplicitAction() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "This is a retained thought")
            val state = EditorState(blocks = listOf(block), expandedBlockId = block.id, isModified = true)
            var returns = 0
            var saves = 0
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EntryEditorTopBar(
                        state = state,
                        immersive = true,
                        autoSaveStatus = AutoSaveStatus.IDLE,
                        onBack = {},
                        onReturnToEntry = { returns++ },
                        onShowDrafts = {},
                        onSave = { saves++ },
                        onCaptureMode = {},
                        onJournalSelection = {},
                    )
                }
            }
            onNodeWithText("Done").performClick()
            assertEquals(1, returns)
            assertEquals(0, saves, "Done must not pretend to commit the entry")
            onNodeWithContentDescription("Save entry").performClick()
            assertEquals(1, saves)
            assertEquals(block.content, (state.blocks.single() as TextBlockUiState).content)
        }
}
