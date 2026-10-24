package app.logdate.feature.editor.ui.camera

import android.Manifest
import android.content.Context
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.AndroidMediaPublicationTickets
import app.logdate.client.text.toUiText
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.capture_state_camera_provider_not_ready
import logdate.client.feature.editor.generated.resources.capture_state_camera_selection_failed
import logdate.client.feature.editor.generated.resources.capture_state_lifecycle_owner_not_ready
import logdate.client.feature.editor.generated.resources.capture_state_switch_failed
import logdate.client.feature.editor.generated.resources.capture_state_unknown_error
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [CameraCaptureManager] using CameraX.
 */
class AndroidCameraCaptureManager(
    private val context: Context,
    mediaManager: MediaManager,
    publicationTickets: AndroidMediaPublicationTickets,
) : CameraCaptureManager {
    private val deviceSelection = AndroidCameraDeviceSelection(context)
    private val _state = MutableStateFlow(CameraCaptureState())
    override val state: StateFlow<CameraCaptureState> = _state.asStateFlow()

    private val _previewStreaming = MutableStateFlow(false)

    /**
     * Emits `true` when the attached [PreviewView] reports that frames are streaming.
     */
    val previewStreaming: StateFlow<Boolean> = _previewStreaming.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var previewStreamObserver: Observer<PreviewView.StreamState>? = null

    private val captureOutput = AndroidCameraCaptureOutput(context, mediaManager, _state, publicationTickets)

    /**
     * Sets the lifecycle owner for camera binding.
     * Must be called before starting the preview.
     */
    fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    /**
     * Attaches the [PreviewView] used to render the live camera stream.
     */
    fun attachPreviewView(view: PreviewView?) {
        if (previewView === view) {
            return
        }

        previewStreamObserver?.let { observer ->
            previewView?.previewStreamState?.removeObserver(observer)
        }

        previewView = view

        if (view == null) {
            previewStreamObserver = null
            _previewStreaming.value = false
            return
        }

        val observer =
            Observer<PreviewView.StreamState> { state ->
                _previewStreaming.value = state == PreviewView.StreamState.STREAMING
            }
        previewStreamObserver = observer
        view.previewStreamState.observeForever(observer)
        _previewStreaming.value = view.previewStreamState.value == PreviewView.StreamState.STREAMING

        preview?.surfaceProvider = view.surfaceProvider
    }

    /**
     * Sets the camera zoom ratio.
     * @param ratio Zoom ratio where 1.0 is no zoom. Clamped to the camera's supported range.
     */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
    }

    /**
     * Triggers tap-to-focus at the given point.
     * @param x Pixel x coordinate in the attached [PreviewView].
     * @param y Pixel y coordinate in the attached [PreviewView].
     */
    fun tapToFocus(
        x: Float,
        y: Float,
    ) {
        val cam = camera ?: return
        val currentPreviewView = previewView ?: return
        if (!_previewStreaming.value) {
            return
        }

        val point = currentPreviewView.meteringPointFactory.createPoint(x, y)
        val action =
            FocusMeteringAction
                .Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    override suspend fun startPreview(facing: CameraFacing) =
        withContext(Dispatchers.Main) {
            try {
                val provider = ProcessCameraProvider.awaitInstance(context)
                cameraProvider = provider
                val owner = lifecycleOwner ?: error("LifecycleOwner is not set")
                val cameraSelection =
                    deviceSelection.cameraSelectionFor(
                        provider = provider,
                        preferredDeviceId = _state.value.cameraSelection.selectedDeviceId ?: deviceSelection.preferredCameraDeviceId(),
                        fallbackFacing = facing,
                    )
                val selectedDeviceId = cameraSelection.selectedDeviceId
                val selector = deviceSelection.cameraSelectorForDeviceId(selectedDeviceId)

                _previewStreaming.value = false
                createUseCases()
                provider.unbindAll()
                camera =
                    provider.bindToLifecycle(
                        owner,
                        selector,
                        preview,
                        imageCapture,
                        videoCapture,
                    )

                _state.update {
                    it.copy(
                        isPreviewActive = true,
                        cameraFacing = cameraSelection.facingForSelection(),
                        error = null,
                        cameraSelection = cameraSelection,
                    )
                }
                deviceSelection.persistPreferredCameraDeviceId(selectedDeviceId)
            } catch (e: Exception) {
                Napier.e("Failed to start camera preview", e)
                _previewStreaming.value = false
                _state.update {
                    it.copy(
                        isPreviewActive = false,
                        error = e.cameraFailure(Res.string.capture_state_unknown_error),
                    )
                }
            }
        }

    override suspend fun stopPreview() =
        withContext(Dispatchers.Main) {
            try {
                captureOutput.stopRecording()
                camera = null
                _previewStreaming.value = false
                cameraProvider?.unbindAll()
                _state.update {
                    it.copy(
                        isPreviewActive = false,
                        isRecording = false,
                        recordingDurationMs = 0L,
                    )
                }
            } catch (e: Exception) {
                Napier.e("Error stopping camera preview", e)
            }
        }

    override suspend fun capturePhoto(): String? = captureOutput.capturePhoto(imageCapture = { imageCapture })

    override suspend fun capturePhoto(onTargetPrepared: suspend (String) -> Boolean): String? =
        captureOutput.capturePhoto({ imageCapture }, onTargetPrepared)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun startVideoRecording() {
        captureOutput.startVideoRecording(videoCapture)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun startVideoRecording(onTargetPrepared: suspend (String) -> Boolean) {
        captureOutput.startVideoRecording(videoCapture, onTargetPrepared)
    }

    override suspend fun stopVideoRecording(): String? = captureOutput.stopVideoRecording()

    override suspend fun switchCamera() =
        withContext(Dispatchers.Main) {
            if (_state.value.isRecording) {
                Napier.w("Cannot switch camera while recording")
                return@withContext
            }

            val provider =
                cameraProvider ?: run {
                    _state.update {
                        it.copy(error = CameraCaptureError.Unknown(Res.string.capture_state_camera_provider_not_ready.toUiText()))
                    }
                    return@withContext
                }
            val owner =
                lifecycleOwner ?: run {
                    _state.update {
                        it.copy(error = CameraCaptureError.Unknown(Res.string.capture_state_lifecycle_owner_not_ready.toUiText()))
                    }
                    return@withContext
                }
            val newFacing =
                when (_state.value.cameraFacing) {
                    CameraFacing.FRONT -> CameraFacing.BACK
                    CameraFacing.BACK -> CameraFacing.FRONT
                }
            val cameraSelection =
                deviceSelection.cameraSelectionFor(
                    provider = provider,
                    fallbackFacing = newFacing,
                    requireBuiltIn = true,
                )
            val selectedDeviceId =
                cameraSelection
                    .devices
                    .firstOrNull { it.category.matchesFacing(newFacing) }
                    ?.id
                    ?: cameraSelection.selectedDeviceId

            try {
                _state.update { it.copy(error = null) }
                _previewStreaming.value = false
                createUseCases()
                provider.unbindAll()
                camera =
                    provider.bindToLifecycle(
                        owner,
                        deviceSelection.cameraSelectorForDeviceId(selectedDeviceId),
                        preview,
                        imageCapture,
                        videoCapture,
                    )
                _state.update {
                    it.copy(
                        cameraFacing = cameraSelection.copy(selectedDeviceId = selectedDeviceId).facingForSelection(),
                        error = null,
                        cameraSelection = cameraSelection.copy(selectedDeviceId = selectedDeviceId),
                    )
                }
                deviceSelection.persistPreferredCameraDeviceId(selectedDeviceId)
            } catch (e: Exception) {
                Napier.e("Failed to switch camera", e)
                _previewStreaming.value = false
                _state.update {
                    it.copy(error = e.cameraFailure(Res.string.capture_state_switch_failed))
                }
            }
        }

    override suspend fun selectCameraDevice(deviceId: String) =
        withContext(Dispatchers.Main) {
            if (_state.value.isRecording) {
                Napier.w("Cannot select camera while recording")
                return@withContext
            }

            val provider =
                cameraProvider ?: run {
                    _state.update {
                        it.copy(error = CameraCaptureError.Unknown(Res.string.capture_state_camera_provider_not_ready.toUiText()))
                    }
                    return@withContext
                }
            val owner =
                lifecycleOwner ?: run {
                    _state.update {
                        it.copy(error = CameraCaptureError.Unknown(Res.string.capture_state_lifecycle_owner_not_ready.toUiText()))
                    }
                    return@withContext
                }
            val cameraSelection =
                deviceSelection.cameraSelectionFor(
                    provider = provider,
                    preferredDeviceId = deviceId,
                    fallbackFacing = _state.value.cameraFacing,
                )

            if (cameraSelection.selectedDeviceId != deviceId) {
                Napier.w("Requested camera is unavailable: $deviceId")
                return@withContext
            }
            if (_state.value.cameraSelection.selectedDeviceId == deviceId) return@withContext

            try {
                _state.update { it.copy(error = null) }
                _previewStreaming.value = false
                createUseCases()
                provider.unbindAll()
                camera =
                    provider.bindToLifecycle(
                        owner,
                        deviceSelection.cameraSelectorForDeviceId(deviceId),
                        preview,
                        imageCapture,
                        videoCapture,
                    )
                _state.update {
                    it.copy(
                        cameraFacing = cameraSelection.facingForSelection(),
                        error = null,
                        cameraSelection = cameraSelection,
                    )
                }
                deviceSelection.persistPreferredCameraDeviceId(deviceId)
            } catch (e: Exception) {
                Napier.e("Failed to select camera", e)
                _previewStreaming.value = false
                _state.update {
                    it.copy(error = e.cameraFailure(Res.string.capture_state_camera_selection_failed))
                }
            }
        }

    override fun setCaptureMode(mode: CaptureMode) {
        _state.update { it.copy(captureMode = mode) }
    }

    override fun setAspectRatio(ratio: CameraAspectRatio) {
        _state.update { it.copy(aspectRatio = ratio) }
    }

    override fun clearCapturedUri() {
        _state.update { it.copy(lastCapturedUri = null) }
    }

    override fun release() {
        captureOutput.stopRecording()
        // Camera resources are activity-scoped; capture finalization is not. Do not delete
        // staged output, complete the deferred with failure, or cancel the output finalizer here:
        // release() is called from ViewModel.onCleared during navigation/configuration and
        // the CameraX Finalize callback may still be handing the file to MediaManager.
        previewStreamObserver?.let { observer ->
            previewView?.previewStreamState?.removeObserver(observer)
        }
        previewStreamObserver = null
        previewView = null
        _previewStreaming.value = false
        camera = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        imageCapture = null
        videoCapture = null
        lifecycleOwner = null
        _state.update { CameraCaptureState() }
    }

    private fun createUseCases() {
        val currentPreviewView = previewView ?: error("PreviewView is not attached")
        val resolutionSelector = resolutionSelector()
        val targetRotation = currentDisplayRotation(currentPreviewView)

        preview =
            Preview
                .Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
                .build()
                .apply {
                    setSurfaceProvider(currentPreviewView.surfaceProvider)
                }

        imageCapture =
            ImageCapture
                .Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
                .build()

        // Prefer the highest reasonable quality the device supports, with a
        // graceful fallback so cameras that don't expose UHD/FHD still record
        // instead of failing with no producer.
        val qualitySelector =
            QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
            )

        val recorder =
            Recorder
                .Builder()
                .setQualitySelector(qualitySelector)
                .build()
        videoCapture =
            VideoCapture
                .Builder(recorder)
                .setTargetRotation(targetRotation)
                .build()
    }

    /**
     * Resolves the current display rotation so CameraX can pre-rotate frames.
     * Without an explicit target the camera can output sideways frames on
     * foldables and tablets that change orientation mid-session.
     */
    private fun currentDisplayRotation(view: PreviewView): Int =
        view.display?.rotation
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.context.display.rotation
            } else {
                Surface.ROTATION_0
            }

    private fun resolutionSelector(): ResolutionSelector {
        val aspectRatioStrategy =
            when (_state.value.aspectRatio) {
                CameraAspectRatio.STANDARD -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

                CameraAspectRatio.FULL,
                CameraAspectRatio.SQUARE,
                -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            }

        return ResolutionSelector
            .Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
            .build()
    }
}
