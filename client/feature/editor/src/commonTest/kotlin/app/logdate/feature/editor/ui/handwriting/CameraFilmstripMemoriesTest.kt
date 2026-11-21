package app.logdate.feature.editor.ui.handwriting

import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraFilmstripMemoriesTest {
    @Test
    fun keepsTheEntrysMemoriesInOrderWithoutTheLiveCamera() {
        val photo = ImageBlockUiState(uri = "file:///photo.jpg")
        val note = TextBlockUiState(content = "Lunch by the water")
        val shot = CameraBlockUiState(uri = "file:///shot.jpg")
        val live = CameraBlockUiState()

        assertEquals(listOf(photo, note, shot), cameraFilmstripMemories(listOf(photo, note, shot, live), live.id))
    }

    @Test
    fun leavesOutMemoriesThatHaveNothingInThemYet() {
        val blankNote = TextBlockUiState(content = "  ")
        val emptyVoice = AudioBlockUiState()
        val live = CameraBlockUiState()

        assertEquals(emptyList(), cameraFilmstripMemories(listOf(blankNote, emptyVoice, live), live.id))
    }
}
