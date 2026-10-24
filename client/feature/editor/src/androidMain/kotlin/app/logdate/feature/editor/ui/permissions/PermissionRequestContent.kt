package app.logdate.feature.editor.ui.permissions

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.Dialog
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.grant_permission
import logdate.client.feature.editor.generated.resources.open_app_settings
import logdate.client.feature.editor.generated.resources.permission_explanation_camera
import logdate.client.feature.editor.generated.resources.permission_explanation_gallery
import logdate.client.feature.editor.generated.resources.permission_explanation_microphone
import logdate.client.feature.editor.generated.resources.permission_explanation_other
import logdate.client.feature.editor.generated.resources.permission_permanently_denied_camera
import logdate.client.feature.editor.generated.resources.permission_permanently_denied_gallery
import logdate.client.feature.editor.generated.resources.permission_permanently_denied_microphone
import logdate.client.feature.editor.generated.resources.permission_required
import logdate.client.ui.generated.resources.common_not_now
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * A reusable component for requesting permissions with explanation.
 *
 * @param permission The permission being requested
 * @param onRequestPermission Callback when user wants to grant permission (or open settings if permanently denied)
 * @param onDismiss Callback when user dismisses the request
 * @param isPermanentlyDenied Whether the permission has been permanently denied ("Don't ask again")
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PermissionRequestContent(
    permission: String,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
    isPermanentlyDenied: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.permission_required),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(16.dp))

                val explanationText =
                    if (isPermanentlyDenied) {
                        when (permission) {
                            Manifest.permission.CAMERA -> stringResource(Res.string.permission_permanently_denied_camera)

                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            "android.permission.READ_MEDIA_IMAGES",
                            -> stringResource(Res.string.permission_permanently_denied_gallery)

                            Manifest.permission.RECORD_AUDIO -> stringResource(Res.string.permission_permanently_denied_microphone)

                            else -> stringResource(Res.string.permission_permanently_denied_gallery)
                        }
                    } else {
                        stringResource(permissionExplanation(permission))
                    }

                Text(
                    text = explanationText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            if (isPermanentlyDenied) {
                                stringResource(Res.string.open_app_settings)
                            } else {
                                stringResource(Res.string.grant_permission)
                            },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(UiRes.string.common_not_now),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Returns a user-friendly explanation for why the app needs a specific permission.
 */
private fun permissionExplanation(permission: String): StringResource =
    when (permission) {
        Manifest.permission.CAMERA -> Res.string.permission_explanation_camera

        Manifest.permission.READ_EXTERNAL_STORAGE,
        "android.permission.READ_MEDIA_IMAGES",
        -> Res.string.permission_explanation_gallery

        Manifest.permission.RECORD_AUDIO -> Res.string.permission_explanation_microphone

        else -> Res.string.permission_explanation_other
    }
