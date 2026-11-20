@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.camera

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.logdate.client.media.MediaManager
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.text.asString
import app.logdate.client.text.resolve
import app.logdate.feature.editor.ui.audio.expansion.ImmersiveSystemBarEffect
import app.logdate.feature.editor.ui.layout.LocalEntryCreationReserve
import app.logdate.feature.editor.ui.layout.LocalEntryHeaderReserve
import app.logdate.ui.adaptive.FoldableTabletopLayout
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.camera
import logdate.client.feature.editor.generated.resources.camera_close
import logdate.client.feature.editor.generated.resources.switch_camera
import logdate.client.feature.editor.generated.resources.tap_to_enable_camera
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * States the capture screen can be rendered in without a camera, for previews and screenshots.
 */
sealed interface CameraCapturePreviewState {
    data object PermissionRequired : CameraCapturePreviewState

    data class LiveCapture(
        val isRecording: Boolean = false,
        val showCameraPicker: Boolean = false,
    ) : CameraCapturePreviewState
}

private const val CAMERA_SWITCH_PREVIEW_TIMEOUT_MS = 2_000L
private const val MAX_ZOOM = 10f

/** Captures always use the full 3:4 sensor; framing happens where the shot is used. */
private val CaptureRatio = CameraAspectRatio.STANDARD.ratio

/** Room the shutter takes at the bottom of the panel, above the dock. */
private val ShutterZoneHeight = 112.dp
private val ViewfinderCorner = 24.dp
private val SwipeDownToClose = 96.dp

@OptIn(ExperimentalPermissionsApi::class)
@Composable
actual fun CameraCaptureContent(
    onMediaCaptured: (uri: String, mediaType: CapturedMediaType, durationMs: Long) -> Unit,
    onClose: () -> Unit,
    pendingFilePath: String?,
    onPrepareCaptureTarget: suspend (String, CapturedMediaType) -> Boolean,
    modifier: Modifier,
    remoteControl: CameraRemoteControl,
    initialCaptureMode: CaptureMode,
) {
    if (LocalInspectionMode.current) {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.LiveCapture(),
            modifier = modifier,
        )
        return
    }

    val viewModel: CameraViewModel = koinViewModel()
    LaunchedEffect(viewModel, initialCaptureMode) {
        // The mode only matters to the watch remote, whose single button toggles recording.
        if (initialCaptureMode != CaptureMode.PHOTO) viewModel.setCaptureMode(initialCaptureMode)
    }
    val uiState by viewModel.uiState.collectAsState()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnMediaCaptured by rememberUpdatedState(onMediaCaptured)
    val currentOnPrepareCaptureTarget by rememberUpdatedState(onPrepareCaptureTarget)
    val mediaManager: MediaManager = koinInject()
    val context = LocalContext.current

    LaunchedEffect(pendingFilePath) {
        val path = pendingFilePath ?: return@LaunchedEffect
        val recovered =
            try {
                AndroidPendingCameraCaptureRecovery(
                    context.filesDir,
                    save = { sourcePath, name, mime -> mediaManager.saveMediaFromFile(sourcePath, name, mime) },
                ).recover(path)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Napier.w("Could not recover a pending camera capture", error)
                null
            }
        recovered?.let { currentOnMediaCaptured(it.uri, it.type, 0L) }
    }

    val cameraPermissions =
        rememberMultiplePermissionsState(
            permissions =
                listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                ),
        )

    val hasPermissions = cameraPermissions.allPermissionsGranted
    if (hasPermissions) ImmersiveSystemBarEffect()

    // Every shot goes straight into the entry. The block that receives it offers retake.
    LaunchedEffect(uiState.capturedMediaUri) {
        val uri = uiState.capturedMediaUri ?: return@LaunchedEffect
        val mediaType = uiState.capturedMediaType ?: CapturedMediaType.PHOTO
        val duration = if (mediaType == CapturedMediaType.VIDEO) uiState.recordingDurationMs else 0L
        currentOnMediaCaptured(uri, mediaType, duration)
        remoteControl.onCaptureCompleted(uri, mediaType)
        viewModel.clearCapturedMedia()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error -> remoteControl.onCaptureFailed(error.resolve()) }
    }

    LaunchedEffect(uiState.cameraSelection) {
        remoteControl.onCameraSelectionChanged(uiState.cameraSelection)
    }

    LaunchedEffect(remoteControl.commands, viewModel) {
        remoteControl.commands.collect { command ->
            when (command) {
                CameraRemoteCommand.Capture -> {
                    viewModel.capture(currentOnPrepareCaptureTarget)
                }

                CameraRemoteCommand.Close -> {
                    currentOnClose()
                }

                is CameraRemoteCommand.SelectCameraCategory -> {
                    val deviceId =
                        viewModel
                            .uiState
                            .value
                            .cameraSelection
                            .devices
                            .firstOrNull { it.category == command.category }
                            ?.id
                    if (deviceId != null) {
                        viewModel.selectCameraDevice(deviceId)
                    }
                }

                is CameraRemoteCommand.SelectCameraDevice -> {
                    viewModel.selectCameraDevice(command.deviceId)
                }

                CameraRemoteCommand.SwitchBuiltInCamera -> {
                    viewModel.switchCamera()
                }
            }
        }
    }

    if (!hasPermissions) {
        CameraPermissionRequest(
            onRequestPermission = { cameraPermissions.launchMultiplePermissionRequest() },
            modifier = modifier,
        )
    } else {
        InlineCameraCapture(
            viewModel = viewModel,
            uiState = uiState,
            onPrepareCaptureTarget = currentOnPrepareCaptureTarget,
            onClose = currentOnClose,
            modifier = modifier,
        )
    }
}

@Composable
fun CameraCapturePreviewContent(
    state: CameraCapturePreviewState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        CameraCapturePreviewState.PermissionRequired -> {
            CameraPermissionRequest(
                onRequestPermission = {},
                modifier = modifier,
            )
        }

        is CameraCapturePreviewState.LiveCapture -> {
            CameraCaptureLayout(
                isRecording = state.isRecording,
                recordingDuration = "0:12",
                error = null,
                modifier = modifier,
                viewfinder = { viewfinderModifier ->
                    Box(
                        modifier =
                            viewfinderModifier.background(
                                Brush.verticalGradient(listOf(Color(0xFF3A4652), Color(0xFF1B2128), Color(0xFF2D2620))),
                            ),
                    )
                },
                cornerControls = {
                    CameraCornerCluster(
                        controls = CameraCornerControls(showFlip = true, showCameraPicker = state.showCameraPicker),
                        selection = defaultCameraSelection(CameraFacing.BACK),
                        enabled = !state.isRecording,
                        onFlip = {},
                        onDeviceSelected = {},
                    )
                },
                shutter = {
                    CaptureShutter(
                        isRecording = state.isRecording,
                        enabled = true,
                        baseZoom = 1f,
                        onAction = {},
                    )
                },
            )
        }
    }
}

/**
 * Shown before the camera can open. It reads as the camera page, so granting access feels like
 * continuing rather than answering a system prompt out of nowhere.
 */
@Composable
private fun CameraPermissionRequest(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
        contentColor = Color.White,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
                Button(onClick = onRequestPermission, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(Res.string.tap_to_enable_camera))
                }
            }
        }
    }
}

/**
 * The capture screen's layout, shared by the live camera and its previews.
 *
 * Content anchors to the top, near the lens; controls anchor to the bottom, near the thumb.
 * When the 3:4 image fits between the entry header and the shutter it sits in a card with nothing
 * on it. When it doesn't, it fills the panel and the controls float on a scrim.
 */
@Composable
private fun CameraCaptureLayout(
    isRecording: Boolean,
    recordingDuration: String,
    error: String?,
    viewfinder: @Composable BoxScope.(Modifier) -> Unit,
    cornerControls: @Composable () -> Unit,
    shutter: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
        contentColor = Color.White,
    ) {
        FoldableTabletopLayout(
            modifier = Modifier.fillMaxSize(),
            minPaneHeight = 220.dp,
            topPane = {
                viewfinder(Modifier.fillMaxSize())
                ViewfinderOverlays(
                    isRecording = isRecording,
                    recordingDuration = recordingDuration,
                    error = error,
                    cornerControls = cornerControls,
                    topInset = 0.dp,
                )
            },
            bottomPane = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                            .cameraCreationClearance(),
                    contentAlignment = Alignment.Center,
                ) {
                    shutter()
                }
            },
            standardContent = {
                StandardCaptureLayout(
                    isRecording = isRecording,
                    recordingDuration = recordingDuration,
                    error = error,
                    viewfinder = viewfinder,
                    cornerControls = cornerControls,
                    shutter = shutter,
                )
            },
        )
    }
}

@Composable
private fun StandardCaptureLayout(
    isRecording: Boolean,
    recordingDuration: String,
    error: String?,
    viewfinder: @Composable BoxScope.(Modifier) -> Unit,
    cornerControls: @Composable () -> Unit,
    shutter: @Composable () -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val headerReserve = LocalEntryHeaderReserve.current
    val creationReserve = LocalEntryCreationReserve.current
    val topReserve = statusTop + headerReserve
    val shutterBottom = navigationBottom + creationReserve

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val fit =
            cameraViewfinderFit(
                panelWidth = maxWidth,
                panelHeight = maxHeight,
                topReserve = topReserve,
                bottomReserve = shutterBottom + ShutterZoneHeight,
                ratio = CaptureRatio,
            )
        when (fit) {
            is CameraViewfinderFit.Card -> {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = fit.top)
                            .size(fit.width, fit.height)
                            .clip(RoundedCornerShape(ViewfinderCorner)),
                ) {
                    viewfinder(Modifier.fillMaxSize())
                    ViewfinderOverlays(
                        isRecording = isRecording,
                        recordingDuration = recordingDuration,
                        error = error,
                        cornerControls = cornerControls,
                        topInset = 0.dp,
                    )
                }
            }

            CameraViewfinderFit.Overlay -> {
                viewfinder(Modifier.fillMaxSize())
                // Scrims only where controls sit on the image, so text stays readable on snow or sky.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(topReserve + 72.dp)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(shutterBottom + ShutterZoneHeight + 48.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))),
                )
                ViewfinderOverlays(
                    isRecording = isRecording,
                    recordingDuration = recordingDuration,
                    error = error,
                    cornerControls = cornerControls,
                    topInset = topReserve,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = shutterBottom)
                    .fillMaxWidth()
                    .height(ShutterZoneHeight),
            contentAlignment = Alignment.Center,
        ) {
            shutter()
        }
    }
}

/** What sits on the image: the corner cluster, the recording time and any error. */
@Composable
private fun BoxScope.ViewfinderOverlays(
    isRecording: Boolean,
    recordingDuration: String,
    error: String?,
    cornerControls: @Composable () -> Unit,
    topInset: Dp,
) {
    AnimatedVisibility(
        visible = !isRecording,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = topInset + 12.dp, end = 12.dp),
    ) {
        cornerControls()
    }

    RecordingIndicator(
        visible = isRecording,
        duration = recordingDuration,
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInset + 16.dp),
    )

    if (error != null) {
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The only control on the image. Front and back cameras need just a flip; an external camera adds
 * a picker beside it.
 */
@Composable
private fun CameraCornerCluster(
    controls: CameraCornerControls,
    selection: MediaDeviceSelectionUiState,
    enabled: Boolean,
    onFlip: () -> Unit,
    onDeviceSelected: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (controls.showCameraPicker) {
            CameraRouteSelector(
                selection = selection,
                onDeviceSelected = onDeviceSelected,
                label = stringResource(Res.string.camera),
                enabled = enabled,
            )
        }
        if (controls.showFlip) {
            IconButton(
                onClick = onFlip,
                enabled = enabled,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        contentColor = Color.White,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = stringResource(Res.string.switch_camera),
                )
            }
        }
    }
}

/**
 * The live camera. Uses an attached [PreviewView] so the UI can wait for CameraX's streaming
 * signal.
 *
 * Gestures on the image: tap to focus, double-tap to flip, pinch to zoom and swipe down to return
 * to the entry. A sideways swipe does nothing, so mode switching stays on the dock as it does
 * everywhere else in the editor.
 */
@Composable
private fun InlineCameraCapture(
    viewModel: CameraViewModel,
    uiState: CameraUiState,
    onPrepareCaptureTarget: suspend (String, CapturedMediaType) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val manager = viewModel.getCaptureManager() as? AndroidCameraCaptureManager
    val previewStreaming by manager?.previewStreaming?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val currentPreviewStreaming by rememberUpdatedState(previewStreaming)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentIsRecording by rememberUpdatedState(uiState.isRecording)
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

    LaunchedEffect(manager, lifecycleOwner, previewView) {
        manager?.attachPreviewView(previewView)
        manager?.setLifecycleOwner(lifecycleOwner)
        viewModel.startPreview()
    }

    DisposableEffect(manager, previewView) {
        onDispose {
            viewModel.stopPreview()
            manager?.attachPreviewView(null)
        }
    }

    // Camera switch overlay: fade to black, switch, wait for the new feed, fade out.
    val switchOverlayAlpha = remember { Animatable(0f) }
    var isSwitchingCamera by remember { mutableStateOf(false) }
    var showFlash by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isCapturing) {
        if (!uiState.isCapturing && showFlash) {
            delay(150)
            showFlash = false
        }
    }

    val focusColor = Color.White
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusRingAlpha = remember { Animatable(0f) }
    val focusRingScale = remember { Animatable(0f) }
    val currentFocusPoint = focusPoint
    LaunchedEffect(currentFocusPoint) {
        if (currentFocusPoint != null) {
            focusRingAlpha.snapTo(1f)
            focusRingScale.snapTo(1.5f)
            focusRingScale.animateTo(1f, tween(200))
            delay(800)
            focusRingAlpha.animateTo(0f, tween(300))
        }
    }

    var currentZoom by remember { mutableFloatStateOf(1f) }
    var pinchCentroid by remember { mutableStateOf<Offset?>(null) }

    fun applyZoom(ratio: Float) {
        val requested = ratio.coerceIn(1f, MAX_ZOOM)
        currentZoom = manager?.setZoomRatio(requested)?.coerceAtLeast(1f) ?: requested
    }

    fun flip() {
        if (currentIsRecording || isSwitchingCamera) return
        isSwitchingCamera = true
        currentZoom = 1f
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    LaunchedEffect(isSwitchingCamera) {
        if (isSwitchingCamera) {
            val previewManager = manager
            if (previewManager == null) {
                switchOverlayAlpha.snapTo(0f)
                isSwitchingCamera = false
                return@LaunchedEffect
            }

            switchOverlayAlpha.animateTo(1f, tween(120, easing = FastOutSlowInEasing))
            val waitForStreaming =
                async {
                    withTimeoutOrNull(CAMERA_SWITCH_PREVIEW_TIMEOUT_MS) {
                        awaitFreshPreviewStreaming(previewManager.previewStreaming)
                    }
                }

            viewModel.switchCamera()

            val previewReady =
                if (previewManager.state.value.error == null) {
                    waitForStreaming.await() != null
                } else {
                    waitForStreaming.cancel()
                    false
                }

            if (!previewReady) {
                Napier.w("Camera switch preview did not report streaming before overlay timeout")
            }
            switchOverlayAlpha.animateTo(0f, tween(250, easing = LinearEasing))
            isSwitchingCamera = false
        }
    }

    val cornerControls = cameraCornerControls(uiState.cameraSelection)
    val closeLabel = stringResource(Res.string.camera_close)
    val flipLabel = stringResource(Res.string.switch_camera)
    val swipeToClosePx = with(density) { SwipeDownToClose.toPx() }

    CameraCaptureLayout(
        isRecording = uiState.isRecording,
        recordingDuration = uiState.formattedDuration,
        error = uiState.error?.asString(),
        modifier = modifier,
        viewfinder = { viewfinderModifier ->
            Box(
                modifier =
                    viewfinderModifier
                        .background(Color.Black)
                        .semantics {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(closeLabel) {
                                        currentOnClose()
                                        true
                                    },
                                    CustomAccessibilityAction(flipLabel) {
                                        flip()
                                        true
                                    },
                                )
                        }.pointerInput(manager) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var pinching = false
                                var travel = Offset.Zero
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2) {
                                        pinching = true
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f) {
                                            applyZoom(currentZoom * zoom)
                                            pinchCentroid = event.calculateCentroid(useCurrent = true)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } else if (!pinching) {
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change != null) travel += change.positionChange()
                                    }
                                } while (event.changes.any { it.pressed })
                                pinchCentroid = null
                                val swipedDown = travel.y > swipeToClosePx && abs(travel.y) > abs(travel.x) * 2
                                if (!pinching && swipedDown && !currentIsRecording) currentOnClose()
                            }
                        }.pointerInput(manager) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (!currentPreviewStreaming) return@detectTapGestures
                                    focusPoint = offset
                                    manager?.tapToFocus(offset.x, offset.y)
                                },
                                onDoubleTap = { flip() },
                            )
                        },
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    },
                )

                currentFocusPoint?.let { point ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val ringSize = 72.dp.toPx() * focusRingScale.value
                        drawCircle(
                            color = focusColor.copy(alpha = focusRingAlpha.value),
                            radius = ringSize / 2,
                            center = point,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }

                pinchCentroid?.let { centroid ->
                    ZoomReadout(
                        zoom = currentZoom,
                        modifier =
                            Modifier.offset {
                                IntOffset(
                                    (centroid.x - 28.dp.toPx()).roundToInt(),
                                    (centroid.y - 64.dp.toPx()).roundToInt(),
                                )
                            },
                    )
                }

                AnimatedVisibility(
                    visible = showFlash,
                    enter = fadeIn(tween(50)),
                    exit = fadeOut(tween(100)),
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White))
                }

                if (switchOverlayAlpha.value > 0f) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = switchOverlayAlpha.value)),
                    )
                }
            }
        },
        cornerControls = {
            CameraCornerCluster(
                controls = cornerControls,
                selection = uiState.cameraSelection,
                enabled = !uiState.isRecording && !isSwitchingCamera,
                onFlip = ::flip,
                onDeviceSelected = { deviceId ->
                    if (!uiState.isRecording) {
                        scope.launch { viewModel.selectCameraDevice(deviceId) }
                    }
                },
            )
        },
        shutter = {
            CaptureShutter(
                isRecording = uiState.isRecording,
                enabled = !uiState.isCapturing && !isSwitchingCamera,
                baseZoom = currentZoom,
                onAction = { action ->
                    when (action) {
                        ShutterAction.TakePhoto -> {
                            showFlash = true
                            viewModel.takePhoto(onPrepareCaptureTarget)
                        }

                        ShutterAction.StartRecording -> {
                            viewModel.startRecording(onPrepareCaptureTarget)
                        }

                        ShutterAction.StopRecording -> {
                            viewModel.stopRecording()
                        }

                        ShutterAction.LockRecording -> {
                            // The recording is already running; locking only changes the shutter.
                        }

                        is ShutterAction.Zoom -> {
                            applyZoom(action.factor)
                        }
                    }
                },
            )
        },
    )
}

@Composable
private fun RecordingIndicator(
    visible: Boolean,
    duration: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
