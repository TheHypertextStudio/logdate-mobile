package app.logdate.wear.presentation.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceUiState
import app.logdate.client.text.asString
import app.logdate.wear.R
import app.logdate.wear.presentation.asString

@Composable
fun WearRemoteCameraScreen(
    viewModel: WearRemoteCameraViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.navigateBack) {
        onNavigateBack()
        return
    }

    when (uiState.phase) {
        RemoteCameraPhase.IDLE -> {
            IdleContent(onRequest = viewModel::requestCamera)
        }

        RemoteCameraPhase.REQUESTING -> {
            RequestingContent()
        }

        RemoteCameraPhase.READY -> {
            ReadyContent(
                selectedCameraLabel = uiState.selectedCameraLabel.asString(),
                selectedCameraDeviceId = uiState.selectedCameraDeviceId,
                availableCameras = uiState.availableCameras,
                onCapture = viewModel::capture,
                onSelectBack = viewModel::selectBackCamera,
                onSelectFront = viewModel::selectFrontCamera,
                onSelectCameraDevice = viewModel::selectCameraDevice,
                onSwitchCamera = viewModel::switchCamera,
                onCancel = viewModel::dismiss,
            )
        }

        RemoteCameraPhase.CAPTURING -> {
            CapturingContent()
        }

        RemoteCameraPhase.PREVIEW -> {
            PreviewContent(
                message = uiState.captureStatusMessage?.asString(),
                onSave = viewModel::dismiss,
                onMore = viewModel::captureMore,
            )
        }

        RemoteCameraPhase.ERROR -> {
            ErrorContent(
                message = uiState.errorMessage?.asString(),
                onDismiss = viewModel::dismiss,
            )
        }
    }
}

@Composable
private fun IdleContent(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Button(onClick = onRequest) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.wear_camera_open),
                modifier = Modifier.size(24.dp),
            )
            Text(stringResource(R.string.wear_camera_label))
        }
    }
}

@Composable
private fun RequestingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.wear_camera_opening),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReadyContent(
    selectedCameraLabel: String,
    selectedCameraDeviceId: String?,
    availableCameras: List<MediaDeviceUiState>,
    onCapture: () -> Unit,
    onSelectBack: () -> Unit,
    onSelectFront: () -> Unit,
    onSelectCameraDevice: (String) -> Unit,
    onSwitchCamera: () -> Unit,
    onCancel: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        timeText = { TimeText() },
        scrollState = listState,
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "selected-camera") {
                Text(
                    text = selectedCameraLabel,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "capture") {
                IconButton(
                    onClick = onCapture,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.wear_camera_take_photo),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            item(key = "quick-controls") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onSelectBack,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraRear,
                            contentDescription = stringResource(R.string.wear_camera_select_back),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(R.string.wear_camera_switch),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(
                        onClick = onSelectFront,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraFront,
                            contentDescription = stringResource(R.string.wear_camera_select_front),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            availableCameras.forEach { camera ->
                item(key = "camera-${camera.id}") {
                    CameraDeviceButton(
                        camera = camera,
                        isSelected = camera.id == selectedCameraDeviceId,
                        onClick = { onSelectCameraDevice(camera.id) },
                    )
                }
            }
            item(key = "close") {
                Button(onClick = onCancel) {
                    Text(stringResource(R.string.wear_camera_close))
                }
            }
        }
    }
}

@Composable
private fun CameraDeviceButton(
    camera: MediaDeviceUiState,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
    ) {
        Icon(
            imageVector = camera.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = camera.label.asString(),
            maxLines = 1,
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.wear_camera_selected),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val MediaDeviceUiState.icon: ImageVector
    get() =
        when (category) {
            MediaDeviceCategory.FRONT_CAMERA -> Icons.Default.CameraFront

            MediaDeviceCategory.BACK_CAMERA -> Icons.Default.CameraRear

            MediaDeviceCategory.USB,
            MediaDeviceCategory.EXTERNAL,
            -> Icons.Default.Videocam

            else -> Icons.Default.CameraAlt
        }

@Composable
private fun CapturingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.wear_camera_capturing),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PreviewContent(
    message: String?,
    onSave: () -> Unit,
    onMore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message ?: stringResource(R.string.wear_camera_photo_captured),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) {
                Text(stringResource(R.string.wear_camera_done))
            }
            Button(onClick = onMore) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.wear_camera_take_another),
                    modifier = Modifier.size(16.dp),
                )
                Text(stringResource(R.string.wear_home_more))
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message ?: stringResource(R.string.wear_recording_error),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onDismiss) {
            Text(stringResource(R.string.wear_camera_error_back))
        }
    }
}
