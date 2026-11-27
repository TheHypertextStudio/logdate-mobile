package app.logdate.client.location.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.github.aakira.napier.Napier

/**
 * Receives Play Services geofence exit callbacks for `"logdate:stay_exit"` so the foreground
 * location tracker wakes back up from [LocationProfile.STILL] immediately on building departure.
 */
class StayExitGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Napier.w("Stay exit geofence error code=${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val matched =
            event.triggeringGeofences.orEmpty().any { it.requestId == STAY_EXIT_GEOFENCE_ID }
        if (!matched) return
        ActivityAwareLocationService.instance?.onStayExitGeofenceTriggered(event.triggeringLocation)
    }

    companion object {
        const val STAY_EXIT_GEOFENCE_ID = "logdate:stay_exit"
    }
}
