package app.logdate.client.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.activity.ActivityKind
import app.logdate.client.domain.activity.ActivityTracker
import app.logdate.client.domain.activity.ActivityTransition
import app.logdate.client.domain.activity.PhoneActivityRegistration
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.google.android.gms.location.ActivityTransition as PlayTransition

/**
 * The activity a Play services transition reports, or null for one the tracker does not follow, such
 * as standing still. Walking, running and cycling are all [ActivityKind.MOVING].
 */
internal fun activityKindOf(activityType: Int): ActivityKind? =
    when (activityType) {
        DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT, DetectedActivity.ON_BICYCLE -> ActivityKind.MOVING
        DetectedActivity.IN_VEHICLE -> ActivityKind.IN_VEHICLE
        else -> null
    }

/**
 * One Play services transition as an [ActivityTransition]. Its time is on the elapsed-realtime clock,
 * so it is placed against [now] by how long ago it happened, [elapsedNowNanos] minus [elapsedNanos].
 */
internal fun activityTransitionOf(
    activityType: Int,
    transitionType: Int,
    elapsedNanos: Long,
    now: Instant,
    elapsedNowNanos: Long,
): ActivityTransition? {
    val kind = activityKindOf(activityType) ?: return null
    val at = now - (elapsedNowNanos - elapsedNanos).coerceAtLeast(0).nanoseconds
    return ActivityTransition(kind, entered = transitionType == PlayTransition.ACTIVITY_TRANSITION_ENTER, at = at)
}

/**
 * Asks Play services for the phone's activity transitions while activity awareness is on and the
 * physical activity permission is granted, and stops asking otherwise.
 *
 * This request has its own [PendingIntent], so it neither replaces nor is replaced by the location
 * tracker's request for the same transitions.
 */
class PhoneActivityAwareness(
    private val context: Context,
    private val tracker: () -> ActivityTracker,
    private val flags: FeatureFlagStore,
    private val client: ActivityRecognitionClient = ActivityRecognition.getClient(context),
) {
    private var job: Job? = null

    val registration =
        PhoneActivityRegistration(
            optedIn = { flags.isEnabled(FeatureFlag.ACTIVITY_AWARENESS) },
            available = {
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
            },
            permitted = ::permissionGranted,
            register = ::register,
            unregister = ::unregister,
        )

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        tracker().start(scope)
        job =
            scope.launch {
                val consentChanges =
                    flags
                        .observe(FeatureFlag.ACTIVITY_AWARENESS)
                        .distinctUntilChanged()
                        .map { Unit }
                        .catch {
                            Napier.w("Couldn't observe the phone activity choice", it)
                            emit(Unit)
                        }
                combine(consentChanges, registration.refreshRequests) { _, _ -> Unit }
                    .collect { registration.reconcile() }
            }
    }

    /** Checks the permission again, for when the person may have just granted or revoked it. */
    fun onActivityResumed() {
        registration.refresh()
    }

    private fun permissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun register(): Boolean {
        val transitions =
            FOLLOWED.flatMap { activity ->
                listOf(PlayTransition.ACTIVITY_TRANSITION_ENTER, PlayTransition.ACTIVITY_TRANSITION_EXIT).map { transition ->
                    PlayTransition
                        .Builder()
                        .setActivityType(activity)
                        .setActivityTransition(transition)
                        .build()
                }
            }
        val token = checkNotNull(pendingIntent(PendingIntent.FLAG_UPDATE_CURRENT))
        return try {
            val registered =
                withTimeoutOrNull(10.seconds) {
                    client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), token).await()
                    true
                } == true
            if (!registered) token.cancel()
            registered
        } catch (e: CancellationException) {
            token.cancel()
            throw e
        } catch (e: Exception) {
            token.cancel()
            Napier.w("Couldn't follow the phone's activity", e)
            false
        }
    }

    private suspend fun unregister() {
        val token = pendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        try {
            withTimeoutOrNull(5.seconds) { client.removeActivityTransitionUpdates(token).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Couldn't stop following the phone's activity", e)
        } finally {
            token.cancel()
        }
    }

    private fun pendingIntent(flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, PhoneActivityTransitionReceiver::class.java),
            flags or PendingIntent.FLAG_MUTABLE,
        )

    private companion object {
        const val REQUEST_CODE = 0x4ac7
        val FOLLOWED =
            listOf(
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.IN_VEHICLE,
            )
    }
}

/** Hands each transition Play services reports to the phone's [ActivityTracker]. */
class PhoneActivityTransitionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val tracker: ActivityTracker by inject()
    private val registration: PhoneActivityRegistration by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val now =
            kotlin.time.Clock.System
                .now()
        val elapsedNow = SystemClock.elapsedRealtimeNanos()
        val transitions =
            result.transitionEvents.mapNotNull { event ->
                activityTransitionOf(event.activityType, event.transitionType, event.elapsedRealTimeNanos, now, elapsedNow)
            }
        if (transitions.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                if (registration.canReceiveTransitions()) transitions.forEach { tracker.accept(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.w("Could not check or apply the phone activity transition", e)
            } finally {
                pending.finish()
            }
        }
    }
}
