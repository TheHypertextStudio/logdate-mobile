package app.logdate.feature.postcards

import androidx.lifecycle.SavedStateHandle
import app.logdate.feature.postcards.fakes.FakeIndexedMediaRepository
import app.logdate.feature.postcards.fakes.FakePostcardRepository
import app.logdate.feature.postcards.fakes.FakeStickerRepository
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.ui.CanvasEditorViewModel
import app.logdate.feature.postcards.ui.ShelfPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Verifies that each drag, pinch or rotate gesture on a canvas element records exactly one
 * undo entry: the document as it was before the gesture started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasEditorGestureUndoTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `one undo restores the document from before a pinch gesture`() =
        runTest {
            val viewModel = newEditor()
            advanceUntilIdle()
            val elementId = viewModel.placePhoto()
            val beforeGesture = viewModel.state.value.document

            viewModel.beginGesture()
            repeat(GESTURE_FRAMES) {
                viewModel.transformElement(elementId, scaleDelta = 1.02f, rotationDelta = 1.5f)
                viewModel.moveElement(elementId, dx = 0.5f, dy = -0.25f)
            }
            assertNotEquals(beforeGesture, viewModel.state.value.document)

            viewModel.undo()

            assertEquals(beforeGesture, viewModel.state.value.document)
            assertOnlyPlacementLeftToUndo(viewModel)
        }

    @Test
    fun `one undo restores the document from before a drag gesture`() =
        runTest {
            val viewModel = newEditor()
            advanceUntilIdle()
            val elementId = viewModel.placePhoto()
            val beforeGesture = viewModel.state.value.document

            viewModel.beginGesture()
            repeat(GESTURE_FRAMES) {
                viewModel.moveElement(elementId, dx = 2f, dy = 1f)
            }
            assertNotEquals(beforeGesture, viewModel.state.value.document)

            viewModel.undo()

            assertEquals(beforeGesture, viewModel.state.value.document)
            assertOnlyPlacementLeftToUndo(viewModel)
        }

    private fun newEditor(): CanvasEditorViewModel =
        CanvasEditorViewModel(
            savedStateHandle = SavedStateHandle(),
            postcardRepository = FakePostcardRepository(),
            stickerRepository = FakeStickerRepository(),
            indexedMediaRepository = FakeIndexedMediaRepository(),
        )

    private fun CanvasEditorViewModel.placePhoto(): Uuid {
        addPhotoElement(
            ShelfPhoto(mediaUri = "logdate-media://library/objects/photo.jpg", momentRef = Uuid.random()),
            x = 40f,
            y = 60f,
        )
        return state.value.document.elements
            .filterIsInstance<CanvasElement.Photo>()
            .single()
            .id
    }

    /** The gesture's entry is gone; the only entry left is the one recorded when the photo was placed. */
    private fun assertOnlyPlacementLeftToUndo(viewModel: CanvasEditorViewModel) {
        assertTrue(viewModel.canUndo)
        viewModel.undo()
        assertTrue(
            viewModel.state.value.document.elements
                .isEmpty(),
        )
        assertFalse(viewModel.canUndo)
    }

    private companion object {
        const val GESTURE_FRAMES = 30
    }
}
