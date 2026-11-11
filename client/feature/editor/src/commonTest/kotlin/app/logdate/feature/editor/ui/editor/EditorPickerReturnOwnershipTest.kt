package app.logdate.feature.editor.ui.editor

import app.logdate.feature.editor.ui.camera.CaptureMode
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class EditorPickerReturnOwnershipTest {
    @Test
    fun pendingCameraFileCannotBeRemovedByReturningToPicker() = retains(CameraBlockUiState(pendingFilePath = "/capture/pending.jpg"))

    @Test
    fun cameraCaptionCannotBeRemovedByReturningToPicker() = retains(CameraBlockUiState(caption = "Keep this caption"))

    @Test
    fun cameraDurationCannotBeRemovedByReturningToPicker() = retains(CameraBlockUiState(durationMs = 1))

    @Test
    fun photoCaptionCannotBeRemovedByReturningToPicker() = retains(ImageBlockUiState(caption = "Before selection completes"))

    @Test
    fun audioCaptionCannotBeRemovedByReturningToPicker() = retains(AudioBlockUiState(caption = "Keep"))

    @Test
    fun audioTranscriptionCannotBeRemovedByReturningToPicker() = retains(AudioBlockUiState(transcription = "Keep"))

    @Test
    fun audioStartIntentCannotBeRemovedByReturningToPicker() = retains(AudioBlockUiState(startOnOpen = true))

    @Test
    fun completedAndInFlightCaptureStatesAreNotEmptyEvenWithoutAUri() {
        listOf(AudioCaptureState.Recording(), AudioCaptureState.Stopping(), AudioCaptureState.Ready("", 0)).forEach {
            retains(AudioBlockUiState(captureState = it))
        }
        retains(CameraBlockUiState(uri = "file:///camera.jpg"))
        retains(ImageBlockUiState(uri = "file:///photo.jpg"))
    }

    @Test
    fun untouchedSupportedPlaceholdersCanReturnToPicker() {
        listOf(
            TextBlockUiState(),
            HandwritingBlockUiState(),
            ImageBlockUiState(uri = " ", caption = " "),
            CameraBlockUiState(uri = " ", pendingFilePath = " ", caption = " "),
            CameraBlockUiState(initialCaptureMode = CaptureMode.VIDEO, mediaType = CapturedMediaType.VIDEO),
            AudioBlockUiState(),
        ).forEach { assertTrue(state(it).shouldReturnToPickerOnBack(), "$it is an untouched supported placeholder") }
    }

    @Test
    fun unsupportedStandaloneVideoDoesNotReturnToPicker() = retains(VideoBlockUiState())

    @Test
    fun activeDraftCannotReturnToPicker() {
        assertFalse(state(CameraBlockUiState()).copy(draftState = DraftState.Active(Uuid.random())).shouldReturnToPickerOnBack())
    }

    @Test
    fun audioStartReservationCannotReturnToPicker() {
        val camera = CameraBlockUiState()
        assertFalse(state(camera).copy(audioCaptureStartReservations = mapOf(camera.id to Uuid.random())).shouldReturnToPickerOnBack())
    }

    @Test
    fun zeroAndMultipleBlocksCannotReturnToPicker() {
        assertFalse(EditorState().shouldReturnToPickerOnBack())
        assertFalse(EditorState(blocks = listOf(CameraBlockUiState(), TextBlockUiState())).shouldReturnToPickerOnBack())
    }

    private fun retains(block: EntryBlockUiState) =
        assertFalse(state(block).shouldReturnToPickerOnBack(), "$block owns content or capture intent")

    private fun state(block: EntryBlockUiState) = EditorState(blocks = listOf(block), expandedBlockId = block.id)
}
