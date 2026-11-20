package app.logdate.feature.editor.ui.camera

import app.logdate.client.media.device.DefaultMediaDevices
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.media.device.MediaDeviceUiState
import app.logdate.client.text.toUiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelRecordingTest {
    private val manager = FakeCameraCaptureManager()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `takePhoto captures a photo even when the camera opened for video`() =
        runTest {
            manager.photoResultUri = "content://photo.jpg"
            val viewModel = CameraViewModel(manager)
            viewModel.setCaptureMode(CaptureMode.VIDEO)

            viewModel.takePhoto()

            assertTrue(manager.capturePhotoCalled)
            assertFalse(manager.startVideoRecordingCalled)
            assertEquals("content://photo.jpg", viewModel.uiState.value.capturedMediaUri)
            assertEquals(CapturedMediaType.PHOTO, viewModel.uiState.value.capturedMediaType)
        }

    @Test
    fun `startRecording records without switching to a video mode first`() =
        runTest {
            val viewModel = CameraViewModel(manager)

            viewModel.startRecording()

            assertTrue(manager.startVideoRecordingCalled)
            assertTrue(viewModel.uiState.value.isRecording)
            assertEquals(CaptureMode.PHOTO, viewModel.uiState.value.captureMode)
        }

    @Test
    fun `stopRecording hands back the clip`() =
        runTest {
            manager.videoResultUri = "content://clip.mp4"
            val viewModel = CameraViewModel(manager)
            viewModel.startRecording()

            viewModel.stopRecording()

            assertTrue(manager.stopVideoRecordingCalled)
            assertEquals("content://clip.mp4", viewModel.uiState.value.capturedMediaUri)
            assertEquals(CapturedMediaType.VIDEO, viewModel.uiState.value.capturedMediaType)
        }

    @Test
    fun `stopRecording does nothing when nothing is recording`() =
        runTest {
            val viewModel = CameraViewModel(manager)

            viewModel.stopRecording()

            assertFalse(manager.stopVideoRecordingCalled)
            assertNull(viewModel.uiState.value.capturedMediaUri)
        }

    @Test
    fun `a stop that arrives while recording is starting ends it once it starts`() =
        runTest {
            manager.videoResultUri = "content://short.mp4"
            manager.startGate = CompletableDeferred()
            val viewModel = CameraViewModel(manager)

            viewModel.startRecording()
            viewModel.stopRecording()
            assertFalse(manager.stopVideoRecordingCalled)

            manager.startGate?.complete(Unit)

            assertTrue(manager.stopVideoRecordingCalled)
            assertEquals("content://short.mp4", viewModel.uiState.value.capturedMediaUri)
        }

    @Test
    fun `startRecording does nothing while already recording`() =
        runTest {
            val viewModel = CameraViewModel(manager)
            manager.simulateRecordingState(true)

            viewModel.startRecording()

            assertFalse(manager.startVideoRecordingCalled)
        }
}

class CameraCornerControlsTest {
    private fun device(
        id: String,
        category: MediaDeviceCategory,
    ) = MediaDeviceUiState(
        id = id,
        label = id.toUiText(),
        kind = MediaDeviceKind.CAMERA,
        category = category,
        isExternal = category == MediaDeviceCategory.EXTERNAL,
    )

    private fun selection(vararg devices: MediaDeviceUiState) =
        MediaDeviceSelectionUiState(
            kind = MediaDeviceKind.CAMERA,
            devices = devices.toList(),
            selectedDeviceId = devices.firstOrNull()?.id,
        )

    @Test
    fun `front and back cameras show only the flip control`() {
        val controls = cameraCornerControls(defaultCameraSelection(CameraFacing.BACK))

        assertTrue(controls.showFlip)
        assertFalse(controls.showCameraPicker)
    }

    @Test
    fun `a single built-in camera shows no corner controls`() {
        val controls = cameraCornerControls(selection(device("back", MediaDeviceCategory.BACK_CAMERA)))

        assertFalse(controls.showFlip)
        assertFalse(controls.showCameraPicker)
    }

    @Test
    fun `an external camera adds the camera picker`() {
        val controls =
            cameraCornerControls(
                selection(
                    DefaultMediaDevices.backCamera,
                    DefaultMediaDevices.frontCamera,
                    device("usb", MediaDeviceCategory.EXTERNAL),
                ),
            )

        assertTrue(controls.showFlip)
        assertTrue(controls.showCameraPicker)
    }
}
