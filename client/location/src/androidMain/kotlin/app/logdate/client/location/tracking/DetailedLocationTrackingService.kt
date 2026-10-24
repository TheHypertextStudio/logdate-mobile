package app.logdate.client.location.tracking

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.location.R
import app.logdate.client.location.history.LocationTracker
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionType
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the [DetailedLocationTrackingService] if location permission is granted.
 *
 * @param permissionManager Used to verify permission before starting the foreground service.
 *   This prevents a [ForegroundServiceStartNotAllowedException] when the service cannot call
 *   [Service.startForeground] with type [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION].
 * @return `true` if the service was started, `false` if permission was missing or the system
 *   denied the foreground service launch.
 */
@Suppress("DEPRECATION")
fun Context.startDetailedLocationTrackingService(permissionManager: PermissionManager): Boolean {
    if (!permissionManager.isPermissionGranted(PermissionType.LOCATION)) {
        Napier.w("Detailed location tracking not started: missing location permission")
        return false
    }
    val intent =
        Intent(this, DetailedLocationTrackingService::class.java).apply {
            action = DetailedLocationTrackingService.ACTION_START
        }
    return runCatching {
        startForegroundService(intent)
        true
    }.getOrElse { error ->
        if (isForegroundServiceStartNotAllowed(error)) {
            Napier.w("Detailed location tracking start denied by foreground service policy", error)
            false
        } else {
            throw error
        }
    }
}

/** Stops the [DetailedLocationTrackingService] if it is running. */
@Suppress("DEPRECATION")
fun Context.stopDetailedLocationTrackingService() {
    val intent =
        Intent(this, DetailedLocationTrackingService::class.java).apply {
            action = DetailedLocationTrackingService.ACTION_STOP
        }
    stopService(intent)
}

private fun isForegroundServiceStartNotAllowed(error: Throwable): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return false
    }

    return isForegroundServiceStartNotAllowedApi31(error)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun isForegroundServiceStartNotAllowedApi31(error: Throwable): Boolean = error is ForegroundServiceStartNotAllowedException

/**
 * Foreground service that actively samples the device location at a fixed interval.
 *
 * @deprecated Replaced by [ActivityAwareLocationService] which adapts GPS accuracy and
 *   frequency based on detected movement instead of polling at a fixed rate.
 */
@Deprecated(
    "Replaced by ActivityAwareLocationService",
    replaceWith = ReplaceWith("ActivityAwareLocationService"),
)
class DetailedLocationTrackingService :
    Service(),
    KoinComponent {
    companion object {
        const val ACTION_START = "app.logdate.location.action.START_DETAILED_TRACKING"
        const val ACTION_STOP = "app.logdate.location.action.STOP_DETAILED_TRACKING"
        private val CHANNEL_ID = LogDateNotificationChannelKey.LOCATION_HISTORY.id
        private const val NOTIFICATION_ID = 1904
        private const val SAMPLE_INTERVAL_SECONDS = 60L

        /** Maximum time to wait for a single location fix before skipping the sample. */
        private const val LOCATION_FIX_TIMEOUT_SECONDS = 20L
    }

    private val ioDispatcher: CoroutineDispatcher by inject(named("io-dispatcher"))
    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }
    private val locationProvider: ClientLocationProvider by inject()
    private val locationTracker: LocationTracker by inject()
    private var trackingJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                trackingJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startDetailedTracking()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        trackingJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDetailedTracking() {
        if (trackingJob != null) {
            return
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        trackingJob =
            serviceScope.launch {
                while (isActive) {
                    runCatching {
                        val location =
                            withTimeoutOrNull(LOCATION_FIX_TIMEOUT_SECONDS.seconds) {
                                locationProvider.getCurrentLocation()
                            }

                        if (location != null) {
                            locationTracker.logLocation(
                                location = location,
                                metadata =
                                    mapOf(
                                        "capturePipeline" to LocationCapturePipeline.HIGH_DETAIL,
                                        "captureSource" to LocationCaptureSource.FOREGROUND_STREAM,
                                    ),
                            )
                        }
                    }.onFailure { error ->
                        Napier.w("Detailed location tracking sample failed", error)
                    }

                    delay(SAMPLE_INTERVAL_SECONDS.seconds)
                }
            }
    }

    private fun buildNotification(): Notification {
        LogDateNotificationRegistrar(this).registerChannel(LogDateNotificationChannelKey.LOCATION_HISTORY)
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent()
        launchIntent.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_NAV_SOURCE, NAV_SOURCE_LOCATION_HISTORY)
        }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.location_history_notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }
}
