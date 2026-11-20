package app.logdate.feature.editor.ui.camera

import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.ui.media.MediaDeviceSelector

/**
 * The external camera picker. It sits on the live image beside the flip button, so it uses the
 * same quiet dark glass as that button instead of a light chip that pulls the eye off the image.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun CameraRouteSelector(
    selection: MediaDeviceSelectionUiState,
    onDeviceSelected: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    MediaDeviceSelector(
        selection,
        onDeviceSelected,
        label = label,
        enabled = enabled,
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = Color.Black.copy(alpha = 0.6f),
                labelColor = Color.White,
                leadingIconContentColor = Color.White.copy(alpha = 0.85f),
                disabledContainerColor = Color.Black.copy(alpha = 0.6f),
                disabledLabelColor = Color.White.copy(alpha = 0.5f),
                disabledLeadingIconContentColor = Color.White.copy(alpha = 0.5f),
            ),
    )
}
