@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.camera

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.logdate.client.media.MediaManager
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.asSystemControlledSelection
import app.logdate.client.text.asString
import app.logdate.client.text.resolve
import app.logdate.client.text.toUiText
import app.logdate.feature.editor.ui.audio.expansion.ImmersiveSystemBarEffect
import app.logdate.ui.adaptive.FoldableTabletopLayout
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.videoFramePercent
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
import logdate.client.feature.editor.generated.resources.camera_microphone_route_message
import logdate.client.feature.editor.generated.resources.captured_photo
import logdate.client.feature.editor.generated.resources.captured_video
import logdate.client.feature.editor.generated.resources.editor_action_record_video
import logdate.client.feature.editor.generated.resources.microphone
import logdate.client.feature.editor.generated.resources.stop_recording
import logdate.client.feature.editor.generated.resources.switch_camera
import logdate.client.feature.editor.generated.resources.take_photo
import logdate.client.feature.editor.generated.resources.tap_to_enable_camera
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Android implementation of the camera capture content.
 * Shows an inline camera with controls for capturing photos/videos.
 */
sealed interface CameraCapturePreviewState {
    data object PermissionRequired : CameraCapturePreviewState

    data class LiveCapture(
        val aspectRatio: CameraAspectRatio = CameraAspectRatio.STANDARD,
        val captureMode: CaptureMode = CaptureMode.PHOTO,
        val isRecording: Boolean = false,
    ) : CameraCapturePreviewState

    data class Review(
        val uri: String,
        val mediaType: CapturedMediaType,
    ) : CameraCapturePreviewState
}

private const val CAMERA_SWITCH_PREVIEW_TIMEOUT_MS = 2_000L

@Suppress("ktlint:standard:function-naming")
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

    LaunchedEffect(uiState.capturedMediaUri) {
        uiState.capturedMediaUri?.let { uri ->
            val mediaType = uiState.capturedMediaType ?: CapturedMediaType.PHOTO
            val duration = if (mediaType == CapturedMediaType.VIDEO) uiState.recordingDurationMs else 0L
            if (remoteControl.autoAcceptCapturedMedia) {
                currentOnMediaCaptured(uri, mediaType, duration)
                remoteControl.onCaptureCompleted(uri, mediaType)
                viewModel.clearCapturedMedia()
            }
        }
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
    } else if (uiState.capturedMediaUri != null) {
        val reviewUri = uiState.capturedMediaUri!!
        val reviewMediaType = uiState.capturedMediaType ?: CapturedMediaType.PHOTO
        val reviewDuration = if (reviewMediaType == CapturedMediaType.VIDEO) uiState.recordingDurationMs else 0L
        MediaReviewContent(
            uri = reviewUri,
            mediaType = reviewMediaType,
            onRetake = { viewModel.clearCapturedMedia() },
            onUse = {
                onMediaCaptured(reviewUri, reviewMediaType, reviewDuration)
                viewModel.clearCapturedMedia()
            },
            modifier = modifier,
        )
    } else {
        InlineCameraCapture(
            viewModel = viewModel,
            uiState = uiState,
            onPrepareCaptureTarget = currentOnPrepareCaptureTarget,
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
            PreviewInlineCameraCapture(
                aspectRatio = state.aspectRatio,
                captureMode = state.captureMode,
                isRecording = state.isRecording,
                modifier = modifier,
            )
        }

        is CameraCapturePreviewState.Review -> {
            MediaReviewContent(
                uri = state.uri,
                mediaType = state.mediaType,
                onRetake = {},
                onUse = {},
                modifier = modifier,
            )
        }
    }
}

/**
 * Displays a request UI when camera permissions are not granted.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun CameraPermissionRequest(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onRequestPermission,
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 200.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.tap_to_enable_camera),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewInlineCameraCapture(
    aspectRatio: CameraAspectRatio = CameraAspectRatio.STANDARD,
    captureMode: CaptureMode = CaptureMode.PHOTO,
    isRecording: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Viewfinder placeholder
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .aspectRatio(aspectRatio.ratio)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF101318)),
            )

            // Top controls — recording indicator
            if (isRecording) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .statusBarsPadding()
                            .cameraHeaderClearance()
                            .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "01:45",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // Bottom controls
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .navigationBarsPadding()
                        .cameraCreationClearance()
                        .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!isRecording) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AspectRatioSelector(
                            selected = aspectRatio,
                            onSelected = {},
                        )

                        PhotoVideoToggle(
                            currentMode = captureMode,
                            onModeChanged = {},
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Shutter row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.size(48.dp))

                    ShutterButton(
                        isRecording = isRecording,
                        captureMode = captureMode,
                        isCapturing = false,
                        onClick = {},
                    )

                    IconButton(
                        onClick = {},
                        enabled = !isRecording,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                                    CircleShape,
                                ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(Res.string.switch_camera),
                            tint =
                                if (isRecording) {
                                    MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.inverseOnSurface
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Review screen shown after capturing media.
 * Lets the user retake or confirm before committing to the journal entry.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun MediaReviewContent(
    uri: String,
    mediaType: CapturedMediaType,
    onRetake: () -> Unit,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDesc =
        stringResource(
            when (mediaType) {
                CapturedMediaType.PHOTO -> Res.string.captured_photo
                CapturedMediaType.VIDEO -> Res.string.captured_video
            },
        )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalPlatformContext.current)
                            .data(uri)
                            .crossfade(true)
                            .apply {
                                if (mediaType == CapturedMediaType.VIDEO) {
                                    videoFramePercent(0.1)
                                }
                            }.build(),
                    contentDescription = contentDesc,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                // Play icon overlay for video captures
                if (mediaType == CapturedMediaType.VIDEO) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier =
                            Modifier
                                .size(64.dp)
                                .align(Alignment.Center),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = Color.Black,
                            )
                        }
                    }
                }
            }

            CameraReviewActions(
                mediaType,
                onRetake,
                onUse,
                Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * First-class inline camera capture interface with live preview and controls.
 * Uses an attached [PreviewView] so the UI can wait for CameraX's streaming signal.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun InlineCameraCapture(
    viewModel: CameraViewModel,
    uiState: CameraUiState,
    onPrepareCaptureTarget: suspend (String, CapturedMediaType) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val audioRouteRepository: AudioRouteRepository = koinInject()
    val inputSelection by audioRouteRepository.inputDevices.collectAsState()

    val manager = viewModel.getCaptureManager() as? AndroidCameraCaptureManager
    val previewStreaming by manager?.previewStreaming?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val currentPreviewStreaming by rememberUpdatedState(previewStreaming)
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

    // Start preview every time this composable enters composition with the attached PreviewView.
    LaunchedEffect(manager, lifecycleOwner, previewView) {
        manager?.attachPreviewView(previewView)
        manager?.setLifecycleOwner(lifecycleOwner)
        viewModel.startPreview()
    }

    // Stop preview when this composable leaves composition (review screen, block delete, etc.)
    DisposableEffect(manager, previewView) {
        onDispose {
            viewModel.stopPreview()
            manager?.attachPreviewView(null)
        }
    }

    // Camera switch overlay — smooth fade to black, switch, then fade out.
    val switchOverlayAlpha = remember { Animatable(0f) }
    var isSwitchingCamera by remember { mutableStateOf(false) }

    // Capture flash state
    var showFlash by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isCapturing) {
        if (!uiState.isCapturing && showFlash) {
            delay(150)
            showFlash = false
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    // Focus ring state
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

    // Orchestrate camera switch: fade to black → switch → wait for new feed → fade out
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

    // Zoom state
    var currentZoom by remember { mutableFloatStateOf(1f) }

    val animatedAspectRatio by animateFloatAsState(
        targetValue = uiState.aspectRatio.ratio,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "viewfinderAspectRatio",
    )
    val showCameraSwitchShortcut =
        uiState.cameraSelection.devices.count { device ->
            device.category == MediaDeviceCategory.FRONT_CAMERA ||
                device.category == MediaDeviceCategory.BACK_CAMERA
        } >= 2

    @Composable
    fun PreviewPane(modifier: Modifier = Modifier) {
        Box(modifier = modifier) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .aspectRatio(animatedAspectRatio)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                currentZoom = (currentZoom * zoom).coerceIn(1f, 10f)
                                manager?.setZoomRatio(currentZoom)
                            }
                        }.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (!currentPreviewStreaming) {
                                        return@detectTapGestures
                                    }

                                    focusPoint = offset
                                    manager?.tapToFocus(offset.x, offset.y)
                                },
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
                        val ringSize = 80f * focusRingScale.value
                        drawCircle(
                            color = primaryColor.copy(alpha = focusRingAlpha.value),
                            radius = ringSize / 2,
                            center = point,
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showFlash,
                enter = fadeIn(tween(50)),
                exit = fadeOut(tween(100)),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.White),
                )
            }

            if (switchOverlayAlpha.value > 0f) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = switchOverlayAlpha.value)),
                )
            }

            RecordingIndicator(
                visible = uiState.isRecording,
                duration = uiState.formattedDuration,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .statusBarsPadding()
                        .cameraHeaderClearance()
                        .padding(16.dp),
            )
        }
    }

    @Composable
    fun CameraDeviceControls(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraRouteSelector(
                selection = uiState.cameraSelection,
                onDeviceSelected = { deviceId ->
                    if (!uiState.isRecording) {
                        scope.launch { viewModel.selectCameraDevice(deviceId) }
                    }
                },
                label = stringResource(Res.string.camera),
                enabled = !uiState.isRecording,
            )

            if (uiState.captureMode == CaptureMode.VIDEO) {
                CameraRouteSelector(
                    selection =
                        inputSelection.asSystemControlledSelection(
                            routeControlMessage = Res.string.camera_microphone_route_message.toUiText(),
                        ),
                    onDeviceSelected = {},
                    label = stringResource(Res.string.microphone),
                    enabled = !uiState.isRecording,
                )
            }
        }
    }

    @Composable
    fun CaptureControls(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = !uiState.isRecording,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AspectRatioSelector(
                        selected = uiState.aspectRatio,
                        onSelected = { viewModel.setAspectRatio(it) },
                    )

                    PhotoVideoToggle(
                        currentMode = uiState.captureMode,
                        onModeChanged = { viewModel.setCaptureMode(it) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.size(48.dp))

                ShutterButton(
                    isRecording = uiState.isRecording,
                    captureMode = uiState.captureMode,
                    isCapturing = uiState.isCapturing,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (uiState.captureMode == CaptureMode.PHOTO) {
                            showFlash = true
                        }
                        viewModel.capture(onPrepareCaptureTarget)
                    },
                )

                if (showCameraSwitchShortcut) {
                    IconButton(
                        onClick = {
                            if (!isSwitchingCamera) {
                                isSwitchingCamera = true
                                currentZoom = 1f
                            }
                        },
                        enabled = !uiState.isRecording && !isSwitchingCamera,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                                    CircleShape,
                                ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(Res.string.switch_camera),
                            tint =
                                if (uiState.isRecording) {
                                    MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.inverseOnSurface
                                },
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }
    }

    @Composable
    fun ErrorMessage(modifier: Modifier = Modifier) {
        uiState.error?.let { error ->
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = error.asString(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        FoldableTabletopLayout(
            modifier = Modifier.fillMaxSize(),
            minPaneHeight = 220.dp,
            topPane = {
                PreviewPane(modifier = Modifier.fillMaxSize())
            },
            bottomPane = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .navigationBarsPadding()
                            .cameraCreationClearance()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CameraDeviceControls(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                    CaptureControls(modifier = Modifier.fillMaxWidth())
                    ErrorMessage(modifier = Modifier.padding(top = 16.dp))
                }
            },
            standardContent = {
                PreviewPane(modifier = Modifier.fillMaxSize())
                CameraDeviceControls(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .statusBarsPadding()
                            .cameraHeaderClearance()
                            .padding(16.dp),
                )
                CaptureControls(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .navigationBarsPadding()
                            .cameraCreationClearance()
                            .padding(bottom = 24.dp),
                )
                ErrorMessage(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .statusBarsPadding()
                            .cameraHeaderClearance()
                            .padding(top = 16.dp),
                )
            },
        )
    }
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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = duration,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Shutter button for capturing photos or starting/stopping video recording.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun ShutterButton(
    isRecording: Boolean,
    captureMode: CaptureMode,
    isCapturing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        stringResource(
            when {
                isRecording -> Res.string.stop_recording
                captureMode == CaptureMode.PHOTO -> Res.string.take_photo
                else -> Res.string.editor_action_record_video
            },
        )
    val buttonSize = 72.dp
    val innerSize = if (isRecording) 24.dp else 56.dp

    Surface(
        modifier =
            modifier
                .size(buttonSize)
                .semantics { contentDescription = label }
                .clickable(enabled = !isCapturing, role = Role.Button) { onClick() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(innerSize),
                shape = if (isRecording) RoundedCornerShape(4.dp) else CircleShape,
                color =
                    if (captureMode == CaptureMode.VIDEO || isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            ) {}
        }
    }
}

/**
 * MD3 Expressive sliding-pill aspect ratio selector.
 *
 * A compact rounded container with three ratio labels. A filled indicator pill
 * slides behind the selected label with a smooth ease-out transition.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun AspectRatioSelector(
    selected: CameraAspectRatio,
    onSelected: (CameraAspectRatio) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = CameraAspectRatio.entries
    val selectedIndex = options.indexOf(selected)

    // Track each slot's x-offset and width so the indicator can slide to the right position.
    val slotOffsets = remember { mutableMapOf<Int, Float>() }
    val slotWidths = remember { mutableMapOf<Int, Float>() }

    val indicatorOffset by animateDpAsState(
        targetValue = (slotOffsets[selectedIndex] ?: 0f).pxToDp(),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "indicatorOffset",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = (slotWidths[selectedIndex] ?: 0f).pxToDp(),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "indicatorWidth",
    )

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.3f),
    ) {
        Box {
            // Sliding indicator pill
            if (indicatorWidth > 0.dp) {
                Surface(
                    modifier =
                        Modifier
                            .offset(x = indicatorOffset)
                            .size(width = indicatorWidth, height = 36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                ) {}
            }

            // Label slots
            Row {
                options.forEachIndexed { index, ratio ->
                    Box(
                        modifier =
                            Modifier
                                .clickable { onSelected(ratio) }
                                .onSizeChanged { size ->
                                    slotWidths[index] = size.width.toFloat()
                                }.onGloballyPositioned { coordinates ->
                                    slotOffsets[index] = coordinates.positionInParent().x
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ratio.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (ratio == selected) {
                                    MaterialTheme.colorScheme.inverseOnSurface
                                } else {
                                    MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f)
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Converts a pixel value to [Dp] in a composable context.
 */
@Composable
private fun Float.pxToDp(): Dp {
    val density = LocalDensity.current
    return with(density) { this@pxToDp.toDp() }
}
