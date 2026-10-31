package app.logdate.feature.editor.ui.handwriting

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.EntryEditorTopBar
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.shared.model.Journal
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EntryPageProductionTest {
    @Test
    fun productionHeaderSavesAndRetainsDraftActionsAtLargeText() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            var saves = 0
            val journal = Journal(title = "A journal with a long personal title")
            val state =
                EditorState(
                    blocks = listOf(TextBlockUiState(content = "A real thought")),
                    availableJournals = listOf(journal),
                    selectedJournalIds = listOf(journal.id),
                )
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    LogDateTheme(dynamicColor = false) {
                        EntryPageChrome(
                            state,
                            EntryPageOverviewState(false),
                            EntryStartMode.TEXT,
                            {},
                            {},
                            {},
                            header = {
                                EntryEditorTopBar(
                                    state,
                                    false,
                                    AutoSaveStatus.IDLE,
                                    {},
                                    {},
                                    {},
                                    { saves++ },
                                    {},
                                    {},
                                )
                            },
                        ) {}
                    }
                }
            }
            onNodeWithContentDescription("Save entry").assertIsEnabled().performClick()
            assertEquals(1, saves)
            onNodeWithContentDescription("Entry options").performClick()
            onNodeWithTag("entry_open_drafts").assertExists()
            onNodeWithText("Default capture mode").assertDoesNotExist()
        }

    @Test
    fun recordingDisablesCaptureSwitching() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent { LogDateTheme(dynamicColor = false) { StoriesCaptureModes(StoryCaptureMode.Voice, {}, enabled = false) } }
            onNodeWithContentDescription("Handwrite").assertExists()
            onNodeWithContentDescription("Handwrite").assertIsNotEnabled()
        }
}
