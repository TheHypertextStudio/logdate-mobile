package app.logdate.client.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.logdate.client.MainActivity
import app.logdate.client.capture.ACTION_CAPTURE
import app.logdate.client.capture.EXTRA_CAPTURE_MODE
import app.logdate.client.launch.ApplicationStartupCoordinator
import app.logdate.client.launch.ApplicationStartupState
import app.logdate.client.media.audio.AudioRecordingManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import studio.hypertext.logdate.R

/**
 * The New entry Quick Settings tile. Tapping it closes the shade and opens the editor ready to write.
 *
 * Declared disabled in the manifest; `PlatformComponentToggler` enables it while the
 * QUICK_SETTINGS_TILES flag is on.
 */
class NewEntryTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_new_entry)
            updateTile()
        }
    }

    override fun onClick() {
        openCapture(mode = "text")
    }
}

/**
 * The Record voice Quick Settings tile. Tapping it opens the editor in voice mode, which starts
 * recording; while a recording runs the tile is highlighted, and tapping it brings that entry back,
 * in whichever LogDate window it is recording ([RecordingWindowTracker]).
 *
 * It is an active tile: [RecordVoiceTileUpdater] asks Android to refresh it when recording starts or
 * stops, so it is correct even while the shade was closed.
 */
class RecordVoiceTileService :
    TileService(),
    KoinComponent {
    private val recorder: AudioRecordingManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var readinessJob: Job? = null

    override fun onStartListening() {
        val startup = ApplicationStartupCoordinator.start(application)
        if (startup.state.value !is ApplicationStartupState.Ready) {
            qsTile?.apply {
                state = Tile.STATE_UNAVAILABLE
                label = getString(R.string.tile_record_voice)
                updateTile()
            }
            if (readinessJob?.isActive != true) {
                readinessJob =
                    serviceScope.launch {
                        startup.state.first { it is ApplicationStartupState.Ready }
                        onStartListening()
                    }
            }
            return
        }
        val appearance = voiceTileAppearance(recorder.isRecording, recorder.currentRecordingPaused)
        qsTile?.apply {
            state = if (appearance.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_record_voice)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle =
                    when (appearance.status) {
                        VoiceTileStatus.Idle -> null
                        VoiceTileStatus.Recording -> getString(R.string.tile_record_voice_recording)
                        VoiceTileStatus.Paused -> getString(R.string.tile_record_voice_paused)
                    }
            }
            updateTile()
        }
    }

    override fun onStopListening() {
        readinessJob?.cancel()
        readinessJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (ApplicationStartupCoordinator.start(application).state.value !is ApplicationStartupState.Ready) {
            openCapture(mode = "voice")
            return
        }
        when (voiceTileAction(recorder.isRecording)) {
            VoiceTileAction.StartRecording -> openCapture(mode = "voice")
            VoiceTileAction.ShowRecording -> openRecording()
        }
    }
}

/**
 * Keeps the Record voice tile in step with the recorder by asking Android to call
 * [RecordVoiceTileService.onStartListening] whenever recording starts, stops, pauses, or resumes.
 */
class RecordVoiceTileUpdater(
    private val context: Context,
    private val recorder: AudioRecordingManager,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job =
            scope.launch {
                combine(recorder.getRecordingStateFlow(), recorder.getRecordingPausedFlow()) { recording, paused -> recording to paused }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { requestRefresh() }
            }
    }

    private fun requestRefresh() {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, RecordVoiceTileService::class.java))
        }.onFailure { error -> Napier.d("Record voice tile is not available to refresh: ${error.message}") }
    }
}

private fun TileService.openCapture(mode: String) {
    launchFromTile(
        Intent(ACTION_CAPTURE)
            .setClass(this, MainActivity::class.java)
            .putExtra(EXTRA_CAPTURE_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        requestKey = "tile-capture-$mode",
    )
}

private fun TileService.openRecording() {
    launchFromTile(
        Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_RECORDING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        requestKey = "tile-show-recording",
    )
}

/**
 * Starts [intent] and closes the shade, using the PendingIntent form Android 14 requires. Each
 * [requestKey] names one PendingIntent, so repeated taps reuse it rather than registering a new one.
 */
@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.launchFromTile(
    intent: Intent,
    requestKey: String,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                requestKey.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        startActivityAndCollapse(pendingIntent)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
