package app.logdate.client.location.history

import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Stores streamed location fixes from one coroutine, in arrival order. Fixes that arrive while a
 * write is running are stored together in the next write, and whether recording is on comes from
 * the observed settings instead of a storage read for every fix.
 */
class LocationReadingRecorder(
    scope: CoroutineScope,
    settings: LocationTrackingSettingsRepository,
    private val tracker: LocationTracker,
) {
    private val recordingEnabled =
        settings
            .observeSettings()
            .map { it.backgroundTrackingEnabled }
            .stateIn(scope, SharingStarted.Eagerly, null)
    private val pending = Channel<LocationReading>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (first in pending) {
                val batch =
                    buildList {
                        add(first)
                        while (true) add(pending.tryReceive().getOrNull() ?: break)
                    }
                if (!recordingEnabled.filterNotNull().first()) continue
                tracker
                    .logLocations(batch)
                    .onFailure { error -> Napier.w("Failed to persist ${batch.size} locations", error) }
            }
        }
    }

    fun record(readings: List<LocationReading>) {
        readings.forEach { pending.trySend(it) }
    }
}
