package app.logdate.feature.editor.ui.layout

import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorFocusPresentationTest {
    @Test
    fun `a photo that is the open piece is the one the keyboard gives way to`() {
        val text = TextBlockUiState()
        val photo = ImageBlockUiState(uri = "file:///photo.jpg")
        val choosing = ImageBlockUiState()

        assertEquals(photo.id, openPhotoId(listOf(text, photo), photo.id))
        assertNull(openPhotoId(listOf(text, photo), text.id), "writing keeps its keyboard")
        assertNull(openPhotoId(listOf(text, choosing), choosing.id), "choosing a photo is not a photo yet")
        assertNull(openPhotoId(listOf(text, photo), null), "nothing is open")
    }

    @Test
    fun `recording owner takes precedence over another selected block`() {
        val text = TextBlockUiState()
        val audio = AudioBlockUiState(captureState = AudioCaptureState.Recording())
        assertEquals(EditorFocus.Recording(audio.id), EditorState(blocks = listOf(text, audio), expandedBlockId = text.id).editorFocus())
    }

    @Test
    fun `recording ownership prevents a selected empty camera from taking over`() {
        val camera = CameraBlockUiState()
        val audio = AudioBlockUiState(captureState = AudioCaptureState.Recording())
        assertEquals(EditorFocus.Recording(audio.id), editorFocus(listOf(camera, audio), camera.id))
    }

    @Test
    fun `finalization keeps recording focus until a durable file is ready`() {
        val audio = AudioBlockUiState(captureState = AudioCaptureState.Stopping())
        assertEquals(EditorFocus.Recording(audio.id), EditorState(blocks = listOf(audio)).editorFocus())
        val ready = audio.copy(captureState = AudioCaptureState.Ready("file:///memory.m4a", 5000))
        assertEquals(EditorFocus.Editing(audio.id), EditorState(blocks = listOf(ready), expandedBlockId = audio.id).editorFocus())
    }

    @Test
    fun `mobile recording anchors the block and hides unrelated entry controls`() {
        val policy = editorFocusPresentation(EditorFocus.Recording(), 390.dp, false, false)
        assertFalse(policy.blockScrollEnabled)
        assertFalse(policy.entryActionsEnabled)
        assertFalse(policy.showJournalContext)
        assertFalse(policy.showSecondaryContext)
    }

    @Test
    fun `tablet recording keeps passive context while preventing structural edits`() {
        val policy = editorFocusPresentation(EditorFocus.Recording(), 720.dp, true, false)
        assertFalse(policy.blockScrollEnabled)
        assertFalse(policy.entryActionsEnabled)
        assertTrue(policy.showJournalContext)
        assertTrue(policy.showSecondaryContext)
    }

    @Test
    fun `foldable uses active pane width for chrome while retaining its other pane`() {
        val policy = editorFocusPresentation(EditorFocus.Recording(), 380.dp, true, false)
        assertFalse(policy.showJournalContext)
        assertTrue(policy.showSecondaryContext)
        assertFalse(policy.entryActionsEnabled)
    }

    @Test
    fun `recording never shows an empty journal selector on a tablet`() {
        val policy = editorFocusPresentation(EditorFocus.Recording(), 720.dp, true, false, hasJournalSelection = false)
        assertFalse(policy.showJournalContext)
    }

    @Test
    fun `saving locks structural entry actions`() {
        val policy = editorFocusPresentation(EditorFocus.Entry, 720.dp, false, false, entryLocked = true)
        assertFalse(policy.entryActionsEnabled)
    }

    @Test
    fun `keyboard hides journal context without locking ordinary text editing`() {
        val policy = editorFocusPresentation(EditorFocus.Entry, 720.dp, false, true)
        assertFalse(policy.showJournalContext)
        assertTrue(policy.entryActionsEnabled)
        assertTrue(policy.blockScrollEnabled)
    }
}
