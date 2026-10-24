@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.Dialog
import app.logdate.ui.theme.Spacing
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.connect_device_scanner_cancel
import logdate.client.feature.core.generated.resources.connect_device_scanner_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume

/**
 * Shows "Connect a device" on iPhones and iPads with a camera. Tapping it asks for camera access
 * when needed, then scans the connection code with AVFoundation.
 */
@Composable
actual fun DeviceApprovalAction() {
    if (LocalInspectionMode.current) {
        DeviceApprovalContent(DeviceApprovalUiState.Idle, onConnectClick = {}, onApprove = {}, onReject = {}, onDismiss = {})
        return
    }
    val hasCamera = remember { AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null }
    if (!hasCamera) return

    val viewModel: DeviceApprovalViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }

    DeviceApprovalContent(
        state = state,
        onConnectClick = {
            viewModel.dismiss()
            scope.launch {
                if (requestCameraAccess()) {
                    scanning = true
                } else {
                    viewModel.onScanFailed(DeviceApprovalFailure.CameraDenied)
                }
            }
        },
        onApprove = viewModel::approve,
        onReject = viewModel::reject,
        onDismiss = viewModel::dismiss,
    )
    if (scanning) {
        QrScannerDialog(
            onCode = { code ->
                scanning = false
                viewModel.onCodeScanned(code)
            },
            onUnavailable = {
                scanning = false
                viewModel.onScanFailed(DeviceApprovalFailure.ScanFailed)
            },
            onCancel = { scanning = false },
        )
    }
}

private suspend fun requestCameraAccess(): Boolean =
    when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> {
            true
        }

        AVAuthorizationStatusNotDetermined -> {
            suspendCancellableCoroutine { continuation ->
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted -> continuation.resume(granted) }
            }
        }

        else -> {
            false
        }
    }

@Composable
private fun QrScannerDialog(
    onCode: (String) -> Unit,
    onUnavailable: () -> Unit,
    onCancel: () -> Unit,
) {
    val scanner = remember { QrCodeScanner(onCode) }
    DisposableEffect(scanner) {
        if (!scanner.start()) onUnavailable()
        onDispose { scanner.stop() }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).testTag("connect-device-scanner")) {
            UIKitView(
                factory = { QrPreviewView(scanner.previewLayer) },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.connect_device_scanner_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) {
                    Text(stringResource(Res.string.connect_device_scanner_cancel), color = Color.White)
                }
            }
        }
    }
}

/** Runs an `AVCaptureSession` that reports the first QR code it sees, once. */
private class QrCodeScanner(
    onCode: (String) -> Unit,
) {
    private val session = AVCaptureSession()
    private val sessionQueue = dispatch_queue_create("app.logdate.device-approval.scanner", null)
    private val delegate = QrMetadataDelegate(onCode)

    val previewLayer: AVCaptureVideoPreviewLayer =
        AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    fun start(): Boolean {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device = device, error = null)
        val output = AVCaptureMetadataOutput()
        if (input == null || !session.canAddInput(input) || !session.canAddOutput(output)) {
            Napier.w("QR scanner couldn't attach to the camera")
            return false
        }
        session.addInput(input)
        session.addOutput(output)
        output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        dispatch_async(sessionQueue) { session.startRunning() }
        return true
    }

    fun stop() {
        dispatch_async(sessionQueue) { if (session.isRunning()) session.stopRunning() }
    }
}

private class QrMetadataDelegate(
    private val onCode: (String) -> Unit,
) : NSObject(),
    AVCaptureMetadataOutputObjectsDelegateProtocol {
    private var delivered = false

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (delivered) return
        val value =
            didOutputMetadataObjects
                .filterIsInstance<AVMetadataMachineReadableCodeObject>()
                .firstNotNullOfOrNull { it.stringValue }
                ?: return
        delivered = true
        onCode(value)
    }
}

/** Keeps the camera preview layer sized to the view Compose lays out. */
private class QrPreviewView(
    private val previewLayer: AVCaptureVideoPreviewLayer,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}
