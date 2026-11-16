@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.activity

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.logdate.client.permissions.AndroidPermissionManager
import app.logdate.feature.core.settings.ui.rememberReminderAccessRevision
import io.github.aakira.napier.Napier

@Composable
internal actual fun rememberPhoneActivityPermission(onGranted: () -> Unit): PhoneActivityPermission {
    val context = LocalContext.current
    val revision = rememberReminderAccessRevision()
    var requesting by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permission = Manifest.permission.ACTIVITY_RECOGNITION

    fun granted() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accepted ->
            val wasRequested = requesting
            requesting = false
            if (wasRequested && accepted && granted()) onGranted()
        }
    val hasAccess = remember(context, revision, requesting) { granted() }

    return PhoneActivityPermission(
        granted = hasAccess,
        requesting = requesting,
        denied = requested && !requesting && !hasAccess,
        request = {
            if (!requested && !requesting) {
                requested = true
                requesting = true
                try {
                    launcher.launch(permission)
                } catch (e: IllegalStateException) {
                    requesting = false
                    Napier.w("Couldn't open the physical activity permission request", e)
                }
            }
        },
        openSettings = { AndroidPermissionManager(context).openPermissionSettings() },
    )
}
