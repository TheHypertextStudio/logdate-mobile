package app.logdate.feature.core.settings.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.logdate.client.notifications.LogDateNotificationChannelKey

@Composable
internal actual fun rememberReminderSettingsAccess(): ReminderSettingsAccess {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var notificationRequested by remember { androidx.compose.runtime.mutableStateOf(false) }
    var locationRequested by remember { androidx.compose.runtime.mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return remember(revision, notificationRequested, locationRequested) {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val notificationPermission = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val locationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = LogDateNotificationChannelKey.MOMENT_REMINDERS.id
        val channel = manager.getNotificationChannel(channelId)
        val groupBlocked = channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        ReminderSettingsAccess(
            notificationsAllowed =
                notificationPermission &&
                    manager.areNotificationsEnabled() &&
                    channel?.importance != NotificationManager.IMPORTANCE_NONE &&
                    !groupBlocked,
            locationAllowed =
                locationPermission &&
                    granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
                    context.getSystemService(LocationManager::class.java).isLocationEnabled,
            resolveNotifications = {
                if (!notificationPermission && !notificationRequested) {
                    notificationRequested = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(
                            if (manager.areNotificationsEnabled() &&
                                channel != null &&
                                !groupBlocked
                            ) {
                                Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                            } else {
                                Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            },
                        ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
                    )
                }
            },
            resolveLocation = {
                if (!locationPermission && !locationRequested) {
                    locationRequested = true
                    location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else if (!context.getSystemService(LocationManager::class.java).isLocationEnabled) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            },
        )
    }
}
