package app.logdate.feature.editor.ui.camera

import app.logdate.client.media.device.DefaultMediaDevices
import app.logdate.client.text.toUiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [CameraUiState].
 *
 * Tests cover:
 * - Initial state verification
 * - Duration formatting
 * - State copying
 */
class CameraUiStateTest {
    @Test
    fun `initial state has correct defaults`() {
        val state = CameraUiState()

        assertFalse(state.isPreviewActive)
        assertEquals(CameraFacing.BACK, state.cameraFacing)
        assertEquals(DefaultMediaDevices.backCamera.id, state.cameraSelection.selectedDeviceId)
        assertEquals(CaptureMode.PHOTO, state.captureMode)
        assertFalse(state.isCapturing)
        assertFalse(state.isRecording)
        assertEquals(0L, state.recordingDurationMs)
        assertNull(state.capturedMediaUri)
        assertNull(state.capturedMediaType)
        assertNull(state.error)
    }

    @Test
    fun `formattedDuration formats correctly for seconds`() {
        val state = CameraUiState(recordingDurationMs = 5000L)
        assertEquals("00:05", state.formattedDuration)
    }

    @Test
    fun `formattedDuration formats correctly for minutes and seconds`() {
        val state = CameraUiState(recordingDurationMs = 65000L)
        assertEquals("01:05", state.formattedDuration)
    }

    @Test
    fun `formattedDuration formats correctly for zero`() {
        val state = CameraUiState(recordingDurationMs = 0L)
        assertEquals("00:00", state.formattedDuration)
    }

    @Test
    fun `formattedDuration formats correctly for long recordings`() {
        val state = CameraUiState(recordingDurationMs = 3661000L) // 61 minutes, 1 second
        assertEquals("61:01", state.formattedDuration)
    }

    @Test
    fun `copy updates fields correctly`() {
        val initial = CameraUiState()
        val updated =
            initial.copy(
                isPreviewActive = true,
                cameraFacing = CameraFacing.FRONT,
                captureMode = CaptureMode.VIDEO,
                isRecording = true,
                recordingDurationMs = 10000L,
            )

        assertTrue(updated.isPreviewActive)
        assertEquals(CameraFacing.FRONT, updated.cameraFacing)
        assertEquals(CaptureMode.VIDEO, updated.captureMode)
        assertTrue(updated.isRecording)
        assertEquals(10000L, updated.recordingDurationMs)
    }

    @Test
    fun `copy with captured media updates correctly`() {
        val initial = CameraUiState()
        val updated =
            initial.copy(
                capturedMediaUri = "content://test/photo.jpg",
                capturedMediaType = CapturedMediaType.PHOTO,
            )

        assertEquals("content://test/photo.jpg", updated.capturedMediaUri)
        assertEquals(CapturedMediaType.PHOTO, updated.capturedMediaType)
    }

    @Test
    fun `copy with video capture state updates correctly`() {
        val initial = CameraUiState()
        val updated =
            initial.copy(
                captureMode = CaptureMode.VIDEO,
                isRecording = true,
                capturedMediaUri = "content://test/video.mp4",
                capturedMediaType = CapturedMediaType.VIDEO,
            )

        assertEquals(CaptureMode.VIDEO, updated.captureMode)
        assertTrue(updated.isRecording)
        assertEquals("content://test/video.mp4", updated.capturedMediaUri)
        assertEquals(CapturedMediaType.VIDEO, updated.capturedMediaType)
    }

    @Test
    fun `copy with error state updates correctly`() {
        val initial = CameraUiState()
        val updated = initial.copy(error = "Camera not available".toUiText())

        assertEquals("Camera not available".toUiText(), updated.error)
    }
}

/**
 * Fake implementation of [CameraCaptureManager] for testing purposes.
 *
 * Tracks method calls and allows simulation of various states and responses.
 * This is a test double, not production code.
 */
class FakeCameraCaptureManager : CameraCaptureManager {
    private val _state = MutableStateFlow(CameraCaptureState())
    override val state: StateFlow<CameraCaptureState> = _state

    var isPreviewStarted = false
        private set
    var isPreviewStopped = false
        private set
    var switchCameraCalled = false
        private set
    var capturePhotoCalled = false
        private set
    var startVideoRecordingCalled = false
        private set
    var stopVideoRecordingCalled = false
        private set
    var currentCaptureMode: CaptureMode = CaptureMode.PHOTO
        private set
    var released = false
        private set

    var photoResultUri: String? = null

    /** When set, recording starts only once this completes, like CameraX's asynchronous start. */
    var startGate: CompletableDeferred<Unit>? = null
    var videoResultUri: String? = null

    override suspend fun startPreview(facing: CameraFacing) {
        isPreviewStarted = true
        _state.value =
            _state.value.copy(
                isPreviewActive = true,
                cameraFacing = facing,
            )
    }

    override suspend fun stopPreview() {
        isPreviewStopped = true
        _state.value = _state.value.copy(isPreviewActive = false)
    }

    override suspend fun capturePhoto(): String? {
        capturePhotoCalled = true
        return photoResultUri
    }

    override suspend fun startVideoRecording() {
        startVideoRecordingCalled = true
        startGate?.await()
        _state.value = _state.value.copy(isRecording = true)
    }

    override suspend fun stopVideoRecording(): String? {
        stopVideoRecordingCalled = true
        _state.value = _state.value.copy(isRecording = false)
        return videoResultUri
    }

    override suspend fun switchCamera() {
        switchCameraCalled = true
        val newFacing =
            when (_state.value.cameraFacing) {
                CameraFacing.BACK -> CameraFacing.FRONT
                CameraFacing.FRONT -> CameraFacing.BACK
            }
        _state.value =
            _state.value.copy(
                cameraFacing = newFacing,
                cameraSelection = defaultCameraSelection(newFacing),
            )
    }

    override suspend fun selectCameraDevice(deviceId: String) {
        val requestedFacing =
            when (deviceId) {
                DefaultMediaDevices.frontCamera.id -> CameraFacing.FRONT
                DefaultMediaDevices.backCamera.id -> CameraFacing.BACK
                else -> return
            }
        if (_state.value.cameraFacing == requestedFacing) return
        switchCamera()
    }

    override fun setCaptureMode(mode: CaptureMode) {
        currentCaptureMode = mode
        _state.value = _state.value.copy(captureMode = mode)
    }

    override fun setAspectRatio(ratio: CameraAspectRatio) {
        _state.value = _state.value.copy(aspectRatio = ratio)
    }

    override fun clearCapturedUri() {
        _state.value = _state.value.copy(lastCapturedUri = null)
    }

    override fun release() {
        released = true
    }

    fun simulateRecordingState(isRecording: Boolean) {
        _state.value = _state.value.copy(isRecording = isRecording)
    }

    fun simulateError(error: CameraCaptureError) {
        _state.value = _state.value.copy(error = error)
    }

    fun simulateRecordingDuration(durationMs: Long) {
        _state.value = _state.value.copy(recordingDurationMs = durationMs)
    }
}
