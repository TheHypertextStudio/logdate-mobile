package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class ReminderSettingsAccess(
    val notificationsAllowed: Boolean,
    val locationAllowed: Boolean,
    val resolveNotifications: () -> Unit,
    val resolveLocation: () -> Unit,
)

@Composable
internal expect fun rememberReminderSettingsAccess(): ReminderSettingsAccess

@Composable
internal fun rememberReminderAccessRevision(): Int {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return revision
}
