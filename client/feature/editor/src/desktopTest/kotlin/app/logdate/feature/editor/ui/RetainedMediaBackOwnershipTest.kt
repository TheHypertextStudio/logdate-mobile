package app.logdate.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.state.BlocksUiState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class RetainedMediaBackOwnershipTest {
    @Test(timeout = 60_000)
    fun photoCaptionOwnerSurvivesPredictiveSeekAndCancel() =
        captionOwner(ImageBlockUiState(uri = "file:///synthetic-photo.png", caption = "Retain photo"))

    @Test(timeout = 60_000)
    fun capturedCameraCaptionOwnerSurvivesPredictiveSeekAndCancel() =
        captionOwner(CameraBlockUiState(uri = "file:///synthetic-camera.png", caption = "Retain capture"))

    private fun captionOwner(block: EntryBlockUiState) =
        runDesktopComposeUiTest(width = 400, height = 800) {
            val fixture = MediaBackFixture(block)
            setContent { MaterialTheme { fixture.render() } }
            waitForIdle()
            val initial = onNode(hasSetTextAction()).fetchSemanticsNode().id
            mainClock.autoAdvance = false
            runOnIdle { fixture.motion.seekBack(0.3f) }
            mainClock.advanceTimeBy(32)
            assertEquals(
                initial,
                onNode(hasSetTextAction()).fetchSemanticsNode().id,
                "Seeking Back must keep the actual caption input mounted",
            )
            onNodeWithTag("block_menu_${block.id}").assertDoesNotExist()
            assertEquals(block.id, fixture.focused)
            runOnIdle { fixture.motion.cancelBack() }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(initial, onNode(hasSetTextAction()).fetchSemanticsNode().id)
            assertEquals(0, fixture.dismissals)
            runOnIdle { fixture.motion.commitBack() }
            mainClock.advanceTimeBy(32)
            assertEquals(
                initial,
                onNode(hasSetTextAction()).fetchSemanticsNode().id,
                "Committing Back must retain the caption until ownership is actually dismissed",
            )
            onNodeWithTag("block_menu_${block.id}").assertDoesNotExist()
            assertEquals(block.id, fixture.focused)
            assertEquals(0, fixture.dismissals)
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(null, fixture.focused)
            assertEquals(1, fixture.dismissals)
            assertEquals(block, fixture.block, "Back must preserve the accepted media and caption")
            mainClock.autoAdvance = true
        }

    @Test(timeout = 60_000)
    fun liveCameraStaysMountedUntilBackActuallyCommits() =
        runDesktopComposeUiTest(width = 400, height = 800) {
            val fixture = MediaBackFixture(CameraBlockUiState(pendingFilePath = "/synthetic/pending.jpg"))
            setContent { MaterialTheme { fixture.render() } }
            val camera = onNodeWithText("Camera not available on Desktop")
            // The real Desktop camera capability view, not a substitute capture implementation.
            waitForIdle()
            val initial = camera.fetchSemanticsNode().id
            mainClock.autoAdvance = false
            runOnIdle { fixture.motion.seekBack(0.4f) }
            mainClock.advanceTimeBy(32)
            camera.assertIsDisplayed()
            assertEquals(initial, camera.fetchSemanticsNode().id)
            onNodeWithTag("block_menu_${fixture.block.id}").assertDoesNotExist()
            runOnIdle { fixture.motion.cancelBack() }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(initial, camera.fetchSemanticsNode().id)
            runOnIdle { fixture.motion.commitBack() }
            mainClock.advanceTimeBy(32)
            assertEquals(initial, camera.fetchSemanticsNode().id)
            assertEquals(fixture.block.id, fixture.focused)
            onNodeWithTag("block_menu_${fixture.block.id}").assertDoesNotExist()
            assertEquals(0, fixture.dismissals)
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(1, fixture.dismissals)
            assertEquals(null, fixture.focused)
            assertNotNull((fixture.block as CameraBlockUiState).pendingFilePath)
            mainClock.autoAdvance = true
        }
}

private class MediaBackFixture(
    initialBlock: EntryBlockUiState,
) {
    var block by mutableStateOf(initialBlock)
    var focused by mutableStateOf<Uuid?>(initialBlock.id)
    var dismissals = 0
    lateinit var motion: RetainedEditorFocusMotion

    @Composable
    fun render() {
        val ui =
            BlocksUiState(
                blocks = listOf(block),
                expandedBlockId = focused,
                availableJournals = emptyList(),
                selectedJournalIds = emptyList(),
                onBlockFocused = { focused = it },
                onJournalSelectionChanged = {},
                onUpdateBlock = { block = it },
                onCreateBlock = { _, _ -> block },
                onDeleteBlock = { error("Back must not delete media") },
            )
        val dismiss =
            focusDismissCallback(focused, emptyMap()) { id, _ ->
                if (focused != id) {
                    false
                } else {
                    dismissals++
                    focused = null
                    true
                }
            }
        motion = rememberRetainedEditorFocusMotion(focused, setOf(block.id), false, dismiss, {}, {}, {}, {})
        val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
        Box(Modifier.fillMaxSize()) {
            host.Content(
                block,
                motion.targetId == block.id,
                motion.progress[block.id] ?: 0f,
                Modifier.fillMaxSize(),
                { focused = block.id },
            )
        }
    }
}
