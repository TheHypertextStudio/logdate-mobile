package app.logdate.feature.editor.ui.handwriting

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.EntryEditorTopBar
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                        blockFocusEnabled = true,
                        immersive = true,
                        autoSaveStatus = AutoSaveStatus.IDLE,
                        onBack = {},
                        onReturnToEntry = { returns++ },
                        onShowDrafts = {},
                        onSave = { saves++ },
                        onCaptureMode = {},
                        onJournalSelection = {},
                        floating = true,
                    )
                }
            }
            onNodeWithText("Done").assertDoesNotExist()
            onNodeWithText("Save entry").assertDoesNotExist()
            onNodeWithText("Text").assertDoesNotExist()
            onNodeWithText("Editing memory").assertDoesNotExist()
            val doneBounds = onNodeWithContentDescription("Return to entry").getUnclippedBoundsInRoot()
            val saveBounds = onNodeWithContentDescription("Save entry").getUnclippedBoundsInRoot()
            assertTrue(doneBounds.right - doneBounds.left >= 48.dp && doneBounds.bottom - doneBounds.top >= 48.dp)
            assertTrue(saveBounds.right - saveBounds.left >= 48.dp && saveBounds.bottom - saveBounds.top >= 48.dp)
            onNodeWithContentDescription("Return to entry").performClick()
            assertEquals(1, returns)
            assertEquals(0, saves, "Done must not pretend to commit the entry")
            onNodeWithContentDescription("Save entry").performClick()
            assertEquals(1, saves)
            assertEquals(block.content, (state.blocks.single() as TextBlockUiState).content)
        }

    @Test
    fun meaningfulDraftErrorRemainsVisibleAndSavingKeepsBothActionsDisabled() =
        runDesktopComposeUiTest(width = 320, height = 200) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    FocusedEntryHeader(
                        TextBlockUiState(content = "Keep the unfinished draft"),
                        recording = false,
                        onDone = { error("Saving must block Done") },
                        onSave = { error("Saving must block duplicate Save") },
                        saveEnabled = true,
                        saving = true,
                        draftStatus = AutoSaveStatus.ERROR,
                    )
                }
            }
            onNodeWithText("Draft not saved").assertExists()
            onNodeWithContentDescription("Return to entry").assertIsNotEnabled()
            onNodeWithContentDescription("Save entry").assertIsNotEnabled()
        }
}
