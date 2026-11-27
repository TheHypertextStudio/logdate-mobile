package app.logdate.client.location.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.logdate.client.location.history.LocationTracker
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.time.Clock
import android.location.Location as AndroidLocation

/**
 * Receives background location updates from the system and saves them to [LocationTracker].
 *
 * These are "passive" updates: the device only delivers a location fix when some other app has
 * already requested one, so this receiver adds zero extra battery drain. Updates arrive as
 * broadcast intents sent by [OptimizedBackgroundLocationRegistrar].
 *
 * Because a [BroadcastReceiver] is normally killed as soon as [onReceive] returns, this class
 * calls [goAsync] to keep the process alive while the location is persisted on a background
 * coroutine.
 *
 * @see OptimizedBackgroundLocationRegistrar
 */
class OptimizedBackgroundLocationReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val settings: LocationTrackingSettingsRepository by inject()
    private val locationTracker: LocationTracker by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io-dispatcher"))
    private val clock: Clock by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != OPTIMIZED_BACKGROUND_LOCATION_UPDATE_ACTION) {
            return
        }

        val locationResult = LocationResult.extractResult(intent)
        if (locationResult == null) {
            val availability = LocationAvailability.extractLocationAvailability(intent)
            if (availability?.isLocationAvailable == false) {
                Napier.d("Passive background location update arrived without an available location fix")
            }
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                locationResult.locations.forEach { androidLocation ->
                    logPassiveLocation(androidLocation)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun logPassiveLocation(androidLocation: AndroidLocation) {
        if (!settings.getSettings().backgroundTrackingEnabled) return
        if (!androidLocation.isValidForCapture() || !androidLocation.isFreshForStream(MAX_PASSIVE_FIX_AGE_MS)) {
            return
        }
        val observedAt = androidLocation.observedAt(clock.now())

        locationTracker
            .logLocation(
                location = androidLocation.toLogDateLocation(),
                timestamp = observedAt,
                metadata =
                    androidLocation.captureMetadata(
                        clock.now(),
                        LocationCapturePipeline.OPTIMIZED_BACKGROUND,
                        LocationCaptureSource.PASSIVE_UPDATE,
                    ),
            ).onFailure { error ->
                Napier.w("Failed to persist passive background location update", error)
            }
    }

    private companion object {
        const val MAX_PASSIVE_FIX_AGE_MS = 300_000L
    }

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
}
