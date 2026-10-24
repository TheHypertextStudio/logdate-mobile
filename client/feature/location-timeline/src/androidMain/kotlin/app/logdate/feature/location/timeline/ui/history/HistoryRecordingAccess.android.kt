@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import android.Manifest
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.location.tracking.ActivityAwareLocationService
import app.logdate.client.location.tracking.LocationTrackingManager
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_access_activity_action
import logdate.client.feature.location.timeline.generated.resources.history_access_activity_message
import logdate.client.feature.location.timeline.generated.resources.history_access_background_action
import logdate.client.feature.location.timeline.generated.resources.history_access_background_message
import logdate.client.feature.location.timeline.generated.resources.history_access_location_action
import logdate.client.feature.location.timeline.generated.resources.history_access_location_message
import logdate.client.feature.location.timeline.generated.resources.history_access_location_services_action
import logdate.client.feature.location.timeline.generated.resources.history_access_location_services_message
import logdate.client.feature.location.timeline.generated.resources.history_access_notifications_action
import logdate.client.feature.location.timeline.generated.resources.history_access_notifications_message
import logdate.client.feature.location.timeline.generated.resources.history_access_ready_action
import logdate.client.feature.location.timeline.generated.resources.history_access_ready_message
import org.koin.compose.koinInject

@Composable
internal actual fun rememberHistoryRecordingAccess(): HistoryRecordingAccess {
    val context = LocalContext.current
    val trackingManager = koinInject<LocationTrackingManager>()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val captureStatus by ActivityAwareLocationService.captureStatus.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return remember(revision, captureStatus) {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val motion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val settings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        val access =
            when {
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> {
                    HistoryRecordingAccess(
                        message = Res.string.history_access_location_message,
                        actionLabel = Res.string.history_access_location_action,
                        reducedMotion = motion,
                        resolve = {
                            request.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        },
                    )
                }

                context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true -> {
                    HistoryRecordingAccess(
                        message = Res.string.history_access_location_services_message,
                        actionLabel = Res.string.history_access_location_services_action,
                        reducedMotion = motion,
                        resolve = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    )
                }

                !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> {
                    HistoryRecordingAccess(
                        message = Res.string.history_access_background_message,
                        actionLabel = Res.string.history_access_background_action,
                        reducedMotion = motion,
                        resolve = settings,
                    )
                }

                !granted(Manifest.permission.ACTIVITY_RECOGNITION) -> {
                    HistoryRecordingAccess(
                        message = Res.string.history_access_activity_message,
                        actionLabel = Res.string.history_access_activity_action,
                        reducedMotion = motion,
                        resolve = { request.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) },
                    )
                }

                Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) -> {
                    HistoryRecordingAccess(
                        message = Res.string.history_access_notifications_message,
                        actionLabel = Res.string.history_access_notifications_action,
                        reducedMotion = motion,
                        resolve = { request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                    )
                }

                else -> {
                    HistoryRecordingAccess(
                        true,
                        Res.string.history_access_ready_message,
                        Res.string.history_access_ready_action,
                        motion,
                    )
                }
            }
        access.copy(
            openSettings = settings,
            resolve = if (access.ready) ({ trackingManager.startTracking() }) else access.resolve,
            captureStatus = captureStatus,
        )
    }
}
