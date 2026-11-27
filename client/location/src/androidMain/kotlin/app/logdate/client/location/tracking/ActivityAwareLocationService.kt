package app.logdate.client.location.tracking

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.location.R
import app.logdate.client.location.history.LocationReading
import app.logdate.client.location.history.LocationReadingRecorder
import app.logdate.client.location.history.LocationTracker
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionType
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import android.location.Location as AndroidLocation

/**
 * Starts the [ActivityAwareLocationService] if location permission is granted.
 *
 * @return `true` if the service was started, `false` if permission was missing or the system
 *   denied the foreground service launch.
 */
fun Context.startActivityAwareLocationService(permissionManager: PermissionManager): Boolean {
    if (!permissionManager.isPermissionGranted(PermissionType.LOCATION)) {
        Napier.w("Activity-aware location tracking not started: missing location permission")
        return false
    }
    val intent =
        Intent(this, ActivityAwareLocationService::class.java).apply {
            action = ActivityAwareLocationService.ACTION_START
        }
    return runCatching {
        startForegroundService(intent)
        true
    }.getOrElse { error ->
        if (isForegroundServiceStartNotAllowed(error)) {
            Napier.w("Activity-aware location tracking start denied by foreground service policy", error)
            false
        } else {
            throw error
        }
    }
}

/** Stops the [ActivityAwareLocationService] if it is running. */
fun Context.stopActivityAwareLocationService() {
    val intent =
        Intent(this, ActivityAwareLocationService::class.java).apply {
            action = ActivityAwareLocationService.ACTION_STOP
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
 * Foreground service that adapts GPS accuracy and frequency based on detected movement.
 *
 * Uses Google's Activity Recognition Transition API to detect whether the user is still,
 * walking, or in a vehicle, then reconfigures the active [LocationRequest][com.google.android.gms.location.LocationRequest]
 * with the appropriate [LocationProfile]. When the activity recognition permission is not
 * granted, falls back to the [LocationProfile.ON_FOOT] profile permanently.
 */
class ActivityAwareLocationService :
    Service(),
    KoinComponent {
    companion object {
        const val ACTION_START = "app.logdate.location.action.START_ACTIVITY_AWARE_TRACKING"
        const val ACTION_STOP = "app.logdate.location.action.STOP_ACTIVITY_AWARE_TRACKING"
        private val CHANNEL_ID = LogDateNotificationChannelKey.LOCATION_HISTORY.id
        private val NOTIFICATION_ID = LogDateNotificationChannelKey.LOCATION_HISTORY.notificationId ?: 1905
        private const val ACTIVITY_TRANSITION_REQUEST_CODE = 1906
        private const val STAY_EXIT_GEOFENCE_REQUEST_CODE = 1907
        private const val MAX_LIVE_FIX_AGE_MS = 60_000L
        private const val STAY_EXIT_GEOFENCE_RADIUS_METERS = 100f
        private val captureStatusTracker = LocationCaptureStatusTracker()
        val captureStatus: StateFlow<LocationCaptureStatus> = captureStatusTracker.status

        /** When the stream last delivered a fix; a subscription alone does not mean fixes arrive. */
        @Volatile
        var lastFixAt: Instant? = null
            private set

        /**
         * Weak reference to the running service instance so that [ActivityTransitionReceiver]
         * can forward transition events without binding.
         */
        @Volatile
        var instance: ActivityAwareLocationService? = null
            private set
    }

    private val ioDispatcher: CoroutineDispatcher by inject(named("io-dispatcher"))
    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val settings: LocationTrackingSettingsRepository by inject()
    private val locationTracker: LocationTracker by inject()
    private val permissionManager: PermissionManager by inject()
    private val readingRecorder by lazy { LocationReadingRecorder(serviceScope, settings, locationTracker) }
    private val activityHistoryRepository: ActivityHistoryRepository? by lazy { getKoin().getOrNull<ActivityHistoryRepository>() }
    private val deviceIdProvider: DeviceIdProvider? by lazy { getKoin().getOrNull<DeviceIdProvider>() }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var geofencingClient: GeofencingClient
    private var locationCallback: LocationCallback? = null
    private var currentProfile: LocationProfile? = null
    private val settlingTracker = StillnessSettlingTracker()
    private var stayExitGeofencePendingIntent: PendingIntent? = null

    @Volatile
    private var currentActivityType: String? = null

    @Volatile
    private var currentActivitySince: Instant? = null
    private val clock: Clock by inject()
    private var activityTransitionPendingIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        instance = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startTracking()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by [ActivityTransitionReceiver] when a movement transition is detected.
     */
    fun onActivityTransition(
        activityType: Int,
        transitionType: Int,
        observedAt: Instant = clock.now(),
    ) {
        if (currentActivitySince?.let { observedAt < it } == true) return
        currentActivitySince = observedAt
        if (transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            if (currentActivityType == activityType.activityLabel()) currentActivityType = null
            return
        }
        currentActivityType = activityType.activityLabel()

        val profile =
            when (activityType) {
                DetectedActivity.STILL -> LocationProfile.STILL

                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_FOOT,
                -> LocationProfile.ON_FOOT

                DetectedActivity.ON_BICYCLE -> LocationProfile.CYCLING

                DetectedActivity.IN_VEHICLE -> LocationProfile.IN_VEHICLE

                else -> LocationProfile.ON_FOOT
            }

        if (profile != LocationProfile.STILL) {
            settlingTracker.reset()
            removeStayExitGeofence()
        }
        Napier.i("Activity transition detected: activityType=$activityType, applying profile=$profile")
        applyLocationProfile(profile)
    }

    fun onStayExitGeofenceTriggered(triggeringLocation: AndroidLocation?) {
        settlingTracker.reset()
        removeStayExitGeofence()
        currentActivityType = "ON_FOOT"
        currentActivitySince = clock.now()
        mainHandler.post {
            applyLocationProfile(LocationProfile.ON_FOOT)
        }
        triggeringLocation?.let(::toReading)?.let { readingRecorder.record(listOf(it)) }
    }

    private fun startTracking() {
        if (locationCallback != null) {
            if (activityTransitionPendingIntent == null) registerActivityTransitions()
            return
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Android 14+ enforces FGS type pre-conditions at startForeground() time.
            // A SecurityException here means the app was not in an eligible state
            // (e.g., no visible activity) when the service tried to promote itself.
            // Stop gracefully rather than crashing; the service will be restarted
            // once the app returns to the foreground.
            Napier.w(e) { "Location FGS promotion denied; stopping service" }
            stopSelf()
            return
        }

        LocationRecordingActionReceiver.clearPausedNotification(this)
        registerActivityTransitions()
        seedStartupProfile()
        Napier.i("Activity-aware location subscription requested")
    }

    private fun seedStartupProfile() {
        serviceScope.launch {
            val seededProfile =
                try {
                    val deviceId = deviceIdProvider?.getDeviceId()?.value?.toString()
                    val latest =
                        if (deviceId != null) {
                            activityHistoryRepository?.latestForDevice(deviceId, clock.now() - 12.hours)
                        } else {
                            null
                        }
                    if (latest != null && latest.transitionType == "ENTER" && latest.activityType == "STILL") {
                        currentActivityType = "STILL"
                        currentActivitySince = latest.timestamp
                        LocationProfile.STILL
                    } else {
                        LocationProfile.ON_FOOT
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read persisted activity state for startup profile", error)
                    LocationProfile.ON_FOOT
                }
            mainHandler.post {
                if (currentProfile == null && locationCallback == null) {
                    applyLocationProfile(seededProfile)
                }
            }
        }
    }

    private fun stopTracking() {
        captureStatusTracker.stop()
        unregisterActivityTransitions()
        removeStayExitGeofence()
        settlingTracker.reset()
        locationCallback?.let { callback ->
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (e: SecurityException) {
                Napier.w("Failed to remove location updates", e)
            }
        }
        locationCallback = null
        currentProfile = null
        currentActivityType = null
        currentActivitySince = null
        Napier.i("Activity-aware location tracking stopped")
    }

    private fun applyLocationProfile(profile: LocationProfile) {
        if (profile == currentProfile) return
        val requestGeneration = captureStatusTracker.beginRequest()

        locationCallback?.let { callback ->
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (e: SecurityException) {
                Napier.w("Failed to remove location updates during profile switch", e)
            }
        }

        val callback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (result.locations.isEmpty()) return
                    lastFixAt = clock.now()
                    readingRecorder.record(result.locations.mapNotNull(::toReading))
                }
            }

        locationCallback = callback
        currentProfile = profile
        try {
            fusedLocationClient
                .requestLocationUpdates(
                    profile.toLocationRequest(),
                    callback,
                    Looper.getMainLooper(),
                ).addOnCompleteListener { task ->
                    if (!captureStatusTracker.completeRequest(requestGeneration, task.isSuccessful)) {
                        removeStaleLocationCallback(callback)
                    } else if (task.isSuccessful) {
                        Napier.d("Location profile subscription accepted: $profile")
                    } else {
                        stopAfterLocationRequestFailure(task.exception)
                    }
                }
        } catch (e: SecurityException) {
            if (captureStatusTracker.completeRequest(requestGeneration, succeeded = false)) {
                stopAfterLocationRequestFailure(e)
            }
        }
    }

    private fun stopAfterLocationRequestFailure(error: Exception?) {
        Napier.e("Location updates could not be registered; stopping service", error)
        locationCallback = null
        currentProfile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeStaleLocationCallback(callback: LocationCallback) {
        try {
            fusedLocationClient.removeLocationUpdates(callback)
        } catch (e: SecurityException) {
            Napier.w("Failed to remove superseded location updates", e)
        }
    }

    private fun toReading(androidLocation: AndroidLocation): LocationReading? {
        if (!androidLocation.isValidForCapture() || !androidLocation.isFreshForStream(MAX_LIVE_FIX_AGE_MS)) {
            return null
        }
        val recordedAt = clock.now()
        val observedAt = androidLocation.observedAt(recordedAt)
        lastFixAt = recordedAt
        val decision =
            settlingTracker.onFix(
                latitude = androidLocation.latitude,
                longitude = androidLocation.longitude,
                accuracyMeters = if (androidLocation.hasAccuracy()) androidLocation.accuracy else null,
                speedMetersPerSecond = if (androidLocation.hasSpeed()) androidLocation.speed else null,
                timestamp = observedAt,
            )
        when {
            decision.wakeToProfileName == "IN_VEHICLE" -> {
                removeStayExitGeofence()
                mainHandler.post { applyLocationProfile(LocationProfile.IN_VEHICLE) }
            }

            decision.wakeToProfileName != null -> {
                removeStayExitGeofence()
                mainHandler.post { applyLocationProfile(LocationProfile.ON_FOOT) }
            }

            decision.enterStill && currentProfile != LocationProfile.STILL -> {
                currentActivityType = "STILL"
                currentActivitySince = observedAt
                mainHandler.post { applyLocationProfile(LocationProfile.STILL) }
            }
        }
        decision.registerExitGeofenceAt?.let { (anchorLat, anchorLon) ->
            registerStayExitGeofence(anchorLat, anchorLon)
        }

        val location =
            Location(
                latitude = androidLocation.latitude,
                longitude = androidLocation.longitude,
                altitude =
                    LocationAltitude(
                        value = if (androidLocation.hasAltitude()) androidLocation.altitude else 0.0,
                        units = AltitudeUnit.METERS,
                    ),
            )

        val metadata =
            androidLocation.captureMetadata(
                recordedAt,
                LocationCapturePipeline.HIGH_DETAIL,
                LocationCaptureSource.FOREGROUND_STREAM,
                currentActivityType.takeIf { currentActivitySince?.let { it <= observedAt } == true },
            )
        return LocationReading(location, observedAt, metadata)
    }

    @Suppress("MissingPermission")
    private fun registerStayExitGeofence(
        latitude: Double,
        longitude: Double,
    ) {
        if (!permissionManager.isPermissionGranted(PermissionType.LOCATION)) return
        val geofence =
            Geofence
                .Builder()
                .setRequestId(StayExitGeofenceReceiver.STAY_EXIT_GEOFENCE_ID)
                .setCircularRegion(latitude, longitude, STAY_EXIT_GEOFENCE_RADIUS_METERS)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay(0)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        val request =
            GeofencingRequest
                .Builder()
                .setInitialTrigger(0)
                .addGeofence(geofence)
                .build()
        val pendingIntent =
            stayExitGeofencePendingIntent ?: PendingIntent
                .getBroadcast(
                    this,
                    STAY_EXIT_GEOFENCE_REQUEST_CODE,
                    Intent(this, StayExitGeofenceReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).also { stayExitGeofencePendingIntent = it }
        runCatching {
            geofencingClient.addGeofences(request, pendingIntent).addOnFailureListener { error ->
                Napier.w("Failed to register stay exit geofence", error)
            }
        }
    }

    private fun removeStayExitGeofence() {
        runCatching {
            geofencingClient.removeGeofences(listOf(StayExitGeofenceReceiver.STAY_EXIT_GEOFENCE_ID))
        }
    }

    @Suppress("MissingPermission")
    private fun registerActivityTransitions() {
        if (!permissionManager.isPermissionGranted(PermissionType.ACTIVITY_RECOGNITION)) {
            Napier.i("Activity recognition permission not granted, using ON_FOOT profile as fallback")
            return
        }

        val transitions =
            listOf(
                DetectedActivity.STILL,
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_FOOT,
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_BICYCLE,
            ).flatMap { activityType ->
                listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { transition ->
                    ActivityTransition
                        .Builder()
                        .setActivityType(activityType)
                        .setActivityTransition(transition)
                        .build()
                }
            }

        val request = ActivityTransitionRequest(transitions)
        val pendingIntent =
            PendingIntent.getBroadcast(
                this,
                ACTIVITY_TRANSITION_REQUEST_CODE,
                Intent(this, ActivityTransitionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        activityTransitionPendingIntent = pendingIntent

        try {
            activityRecognitionClient
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Napier.i("Activity transition updates registered")
                }.addOnFailureListener { error ->
                    if (activityTransitionPendingIntent === pendingIntent) activityTransitionPendingIntent = null
                    Napier.w("Failed to register activity transitions, using ON_FOOT fallback", error)
                }
        } catch (error: SecurityException) {
            if (activityTransitionPendingIntent === pendingIntent) activityTransitionPendingIntent = null
            Napier.w("Activity recognition grant changed before registration, using ON_FOOT fallback", error)
        }
    }

    private fun unregisterActivityTransitions() {
        activityTransitionPendingIntent?.let { pendingIntent ->
            activityRecognitionClient
                .removeActivityTransitionUpdates(pendingIntent)
                .addOnSuccessListener {
                    Napier.d("Activity transition updates removed")
                }.addOnFailureListener { error ->
                    Napier.w("Failed to remove activity transitions", error)
                }
        }
        activityTransitionPendingIntent = null
    }

    private fun buildNotification(): Notification {
        LogDateNotificationRegistrar(this).registerChannel(LogDateNotificationChannelKey.LOCATION_HISTORY)
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
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
            .setContentTitle(getString(R.string.location_recording_notification_title))
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.location_recording_action_pause),
                LocationRecordingActionReceiver.actionIntent(this, LocationRecordingActionReceiver.ACTION_PAUSE),
            ).setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
