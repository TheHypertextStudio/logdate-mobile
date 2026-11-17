package app.logdate.client.location

import android.content.Context
import androidx.core.location.LocationCompat
import app.logdate.client.location.tracking.observedAt
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import android.location.Location as AndroidLocation

/**
 * Android implementation of location provider using Google Play Services.
 *
 * Provides real-time location updates for personal location logging with
 * configurable accuracy and update intervals.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val permissionManager: app.logdate.client.permissions.PermissionManager,
) : ClientLocationProvider {
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val _currentLocation = MutableSharedFlow<Location>(replay = 1)
    override val currentLocation: SharedFlow<Location> = _currentLocation.asSharedFlow()

    init {
        tryEmitLastKnownLocation()
    }

    override suspend fun getCurrentLocation(): Location = getCurrentFix().location

    override suspend fun getCurrentFix(): LocationFix {
        if (!hasLocationPermission()) {
            throw SecurityException("Location permission not granted. Please enable location access in your device settings.")
        }

        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellationTokenSource.cancel() }

            try {
                fusedLocationClient
                    .getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cancellationTokenSource.token,
                    ).addOnSuccessListener { androidLocation ->
                        if (androidLocation != null) {
                            val fix = androidLocation.toLocationFix()
                            _currentLocation.tryEmit(fix.location)
                            continuation.resume(fix)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("Unable to get current location"),
                            )
                        }
                    }.addOnFailureListener { exception ->
                        continuation.resumeWithException(exception)
                    }
            } catch (e: SecurityException) {
                continuation.resumeWithException(e)
            }
        }
    }

    override suspend fun refreshLocation() {
        if (hasLocationPermission()) {
            getCurrentLocation()
        }
    }

    override fun hasLocationPermission(): Boolean =
        permissionManager.isPermissionGranted(app.logdate.client.permissions.PermissionType.LOCATION)

    private fun AndroidLocation.toLocationFix(): LocationFix =
        LocationFix(
            location = toLogDateLocation(),
            observedAt = observedAt(Clock.System.now()),
            accuracyMeters = accuracy.takeIf { hasAccuracy() },
            speedMetersPerSecond = speed.takeIf { hasSpeed() },
            bearingDegrees = bearing.takeIf { hasBearing() },
            isMock = LocationCompat.isMock(this),
        )

    private fun AndroidLocation.toLogDateLocation(): Location =
        Location(
            latitude = latitude,
            longitude = longitude,
            altitude =
                LocationAltitude(
                    value = if (hasAltitude()) altitude else 0.0,
                    units = AltitudeUnit.METERS,
                ),
        )

    /**
     * Tries to emit the last known location immediately if available and permissions are granted.
     * This helps provide immediate location data for the UI.
     */
    private fun tryEmitLastKnownLocation() {
        if (!hasLocationPermission()) return

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { androidLocation ->
                    androidLocation?.let { location ->
                        val logDateLocation = location.toLogDateLocation()
                        _currentLocation.tryEmit(logDateLocation)
                    }
                }.addOnFailureListener {
                    // Last known location not available, that's okay
                }
        } catch (e: SecurityException) {
            // Permission not available, that's okay
        }
    }
}
