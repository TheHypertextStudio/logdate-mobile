package app.logdate.feature.editor.ui.audio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.feature.editor.ui.handwriting.EntryPageChrome
import app.logdate.feature.editor.ui.handwriting.EntryPageOverviewState
import app.logdate.feature.editor.ui.handwriting.recorderFixture
import app.logdate.feature.editor.ui.layout.EditorFocus
import app.logdate.feature.editor.ui.layout.editorFocus
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AudioEntryCreationFlowTest {
    @Test
    fun `editorFocus returns Editing when audio block is idle and Recording when active`() {
        val audioBlock = AudioBlockUiState(id = Uuid.random(), captureState = AudioCaptureState.Empty)
        assertEquals(EditorFocus.Editing(audioBlock.id), editorFocus(listOf(audioBlock), audioBlock.id))
        assertEquals(
            EditorFocus.Recording(audioBlock.id),
            editorFocus(listOf(audioBlock.copy(captureState = AudioCaptureState.Recording())), audioBlock.id),
        )
    }

    @Test
    fun `tapping the waveform box calling tap record to begin triggers onStartRecording`() {
        var startTaps = 0
        runDesktopComposeUiTest(width = 390, height = 780) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    AudioRecordingControls(
                        recordingState = RecordingState.INACTIVE,
                        audioLevels = emptyList(),
                        recordingDuration = 0.seconds,
                        onStartRecording = { startTaps++ },
                        onStopRecording = {},
                    )
                }
            }
            onNodeWithText("Tap record to begin").assertIsDisplayed()
            onNodeWithText("Tap record to begin").performClick()
            assertEquals(1, startTaps)
        }
    }

    @Test
    fun `audio recording block in entry chrome does not get occluded by entry creation dock`() {
        val block = AudioBlockUiState(id = Uuid.random(), captureState = AudioCaptureState.Empty)
        val fixture = recorderFixture(block)
        val state = EditorState(blocks = listOf(block), expandedBlockId = block.id)
        runDesktopComposeUiTest(width = 390, height = 780) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EntryPageChrome(
                        editor = state,
                        overview = EntryPageOverviewState(false),
                        defaultMode = EntryStartMode.AUDIO,
                        onReturn = {},
                        onExit = {},
                        onCapture = {},
                        header = {},
                    ) {
                        MainEditorContent(
                            BlocksUiState(
                                blocks = listOf(block),
                                expandedBlockId = block.id,
                                availableJournals = emptyList(),
                                selectedJournalIds = emptyList(),
                                onBlockFocused = {},
                                onJournalSelectionChanged = {},
                                onUpdateBlock = {},
                                onCreateBlock = { _, _ -> block },
                                onDeleteBlock = {},
                                blockPresentations = mapOf(fixture),
                            ),
                            shouldReturnToPickerOnBack = false,
                            onDismissExpanded = {},
                            expandAnySelectedBlock = true,
                            floatingChrome = true,
                        )
                    }
                }
            }

            onNodeWithTag("entry_capture_tools").assertIsDisplayed()
            val dockBounds = onNodeWithTag("entry_capture_tools").getUnclippedBoundsInRoot()
            val recordButtonBounds = onNodeWithTag("audio_record_start_button").getUnclippedBoundsInRoot()
            assertTrue(
                recordButtonBounds.bottom < dockBounds.top,
                "Record button ($recordButtonBounds) must sit strictly above the dock ($dockBounds)",
            )
        }
    }
}
