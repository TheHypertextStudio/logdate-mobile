@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.util.localTime
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class CameraEntryHeaderTest {
    private val now = Instant.parse("2026-11-21T00:12:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    @Test
    fun liveCameraHeaderShowsTheTimeAndWhatTheEntryHoldsSoFar() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val photo = ImageBlockUiState(uri = "file:///photo.jpg")
            val note = TextBlockUiState(content = "Lunch by the water")
            val camera = CameraBlockUiState()
            setContent { CameraPage(EditorState(blocks = listOf(photo, note, camera), expandedBlockId = camera.id)) }

            onNodeWithTag("entry_camera_header").assertIsDisplayed()
            onNodeWithText("\u2068${now.localTime}\u2069").assertIsDisplayed()
            onNodeWithTag("entry_camera_filmstrip_${photo.id}").assertIsDisplayed()
            onNodeWithTag("entry_camera_filmstrip_${note.id}").assertIsDisplayed()
            onAllNodesWithTag("entry_camera_filmstrip_${camera.id}").assertCountEquals(0)
            onNodeWithContentDescription("Photo").assertIsDisplayed()
        }

    @Test
    fun aNewEntryShowsOnlyTheTime() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val camera = CameraBlockUiState()
            setContent { CameraPage(EditorState(blocks = listOf(camera), expandedBlockId = camera.id)) }

            onNodeWithText("\u2068${now.localTime}\u2069").assertIsDisplayed()
            onAllNodesWithTag("entry_camera_filmstrip").assertCountEquals(0)
        }

    @Test
    fun aCaptureInProgressQuietsTheHeaderSoOnlyTheShotHasAttention() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val note = TextBlockUiState(content = "Lunch by the water")
            var camera by mutableStateOf(CameraBlockUiState())
            setContent { CameraPage(EditorState(blocks = listOf(note, camera), expandedBlockId = camera.id)) }
            onNodeWithText("\u2068${now.localTime}\u2069").assertIsDisplayed()

            runOnIdle { camera = camera.copy(pendingFilePath = "file:///clip.mp4") }
            waitForIdle()

            val hidden = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            onNode(hasTestTag("entry_camera_header_time") and hidden, useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Return to entry").assertIsNotEnabled()
        }

    @Test
    fun aCapturedShotGoesBackToTheUsualFocusedHeader() =
        runDesktopComposeUiTest(width = 412, height = 915) {
            val shot = CameraBlockUiState(uri = "file:///shot.jpg")
            setContent { CameraPage(EditorState(blocks = listOf(shot), expandedBlockId = shot.id)) }

            onAllNodesWithTag("entry_camera_header").assertCountEquals(0)
            onNodeWithTag("entry_mode_focus").assertIsDisplayed()
        }

    @androidx.compose.runtime.Composable
    private fun CameraPage(editor: EditorState) {
        CompositionLocalProvider(LocalCameraHeaderClock provides clock) {
            MaterialTheme {
                EntryPageChrome(editor, EntryPageOverviewState(false), EntryStartMode.CAMERA, {}, {}, {}) {}
            }
        }
    }
}
