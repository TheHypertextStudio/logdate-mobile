package app.logdate.wear.v2.recorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.ui.platform.LogDateHaptics
import app.logdate.wear.capture.Feeling
import app.logdate.wear.capture.FeelingSaver
import app.logdate.wear.recording.AfterSaveController
import app.logdate.wear.recording.AudioMomentSaver
import app.logdate.wear.recording.AudioSaveResult
import app.logdate.wear.recording.RecordingStartFailure
import app.logdate.wear.recording.WearAudioRecordingManager
import app.logdate.wear.recording.WearRecorder
import app.logdate.wear.sync.WearNoteRemovalNotifier
import app.logdate.wear.v2.continuity.WatchRecordingContinuity
import app.logdate.wear.v2.permissions.PermissionPrompts
import app.logdate.wear.v2.permissions.PermissionResult
import app.logdate.wear.v2.permissions.WatchPermission
import app.logdate.wear.v2.recorder.gesture.RecordGestureListener
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The recorder the new watch app shares. It belongs to the app, so a locked recording outlives the
 * screen that started it, and it follows Record's gesture:
 *
 * - A press starts the recorder at once, so the first word is never lost, but shows nothing.
 * - A tap makes it a locked recording; holding past the long-press timeout is hold to talk, and
 *   sliding up to the lock locks it.
 * - If the pager takes the touch first, the recording is discarded without a trace.
 *
 * Hold to talk saves when the finger lifts, and a locked recording on Stop. After a save, Undo works
 * for five seconds and the five feelings show for four. The microphone is asked for when the gesture
 * decides without it, and notifications once, on the first locked recording. Nothing recorded is
 * dropped silently: each failure becomes [RecorderState.Failed] with its reason, and a recording
 * still held when the activity finishes is saved on [applicationScope]. A locked recording remains
 * active on the watch face and can be reopened from its ongoing notification.
 *
 * A saved note's audio is named by a `logdate-media://` reference; [mediaFiles] resolves it to the
 * file Undo deletes.
 */
class RecorderViewModel(
    private val recorder: WearRecorder,
    private val saver: AudioMomentSaver,
    notesRepository: JournalNotesRepository,
    removalNotifier: WearNoteRemovalNotifier,
    feelingSaver: FeelingSaver,
    private val haptics: LogDateHaptics,
    private val permissions: PermissionPrompts,
    private val applicationScope: CoroutineScope,
    deleteFile: (String) -> Unit = { path -> File(path).delete() },
    mediaFiles: MediaFileResolver? = null,
    continuity: WatchRecordingContinuity? = null,
    private val moodAfterRecording: suspend () -> Boolean = { true },
) : ViewModel(),
    RecordGestureListener,
    RecorderActions {
    /** What the gesture has decided about the recording the recorder started. */
    private enum class GestureDecision {
        NONE,
        PENDING,
        HOLD,
        LOCK,
        RELEASED,
    }

    private val afterSave =
        AfterSaveController(
            scope = viewModelScope,
            notesRepository = notesRepository,
            removalNotifier = removalNotifier,
            deleteFile = deleteFile,
            mediaFiles = mediaFiles,
            feelingSaver = feelingSaver,
        )
    private val mutableState =
        MutableStateFlow<RecorderState>(
            if (recorder.isRecording.value) {
                RecorderState.Live(
                    mode = LiveMode.LOCKED,
                    paused = recorder.isPaused.value,
                    pausedByInterruption = recorder.pausedByInterruption.value,
                    lockProgress = 1f,
                    elapsed = recorder.elapsed.value,
                )
            } else {
                RecorderState.Ready
            },
        )
    private val permissionRequestChannel = Channel<WatchPermission>(Channel.BUFFERED)

    private var decision = if (recorder.isRecording.value) GestureDecision.LOCK else GestureDecision.NONE

    /** Whether the recorder's start succeeded, or null while it is still running. */
    private var started: Boolean? = null
    private var waitingForMicrophone = false
    private var nearLimitWarned = false
    private var unsavedPath: String? = null
    private var discardConfirmJob: Job? = null

    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    /** Permissions the screen should ask for now, at the point of use. */
    val permissionRequests: Flow<WatchPermission> = permissionRequestChannel.receiveAsFlow()

    init {
        observeRecorder()
        observeAfterSave()
        continuity?.attach(viewModelScope, state) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (state.value !is RecorderState.Live) return@withContext false
                stopAndSave()
                kotlinx.coroutines.withTimeoutOrNull(4_000) {
                    state.first {
                        it is RecorderState.Saved || it is RecorderState.Failed || it is RecorderState.TooShort
                    } is RecorderState.Saved
                } ?: false
            }
        }
    }

    override fun onPressed() {
        if (!mutableState.value.canStartRecording) return
        if (!permissions.isGranted(WatchPermission.MICROPHONE)) {
            waitingForMicrophone = true
            return
        }
        beginStart(GestureDecision.PENDING)
    }

    override fun onYielded() {
        waitingForMicrophone = false
        if (decision != GestureDecision.PENDING) return
        decision = GestureDecision.NONE
        when (started) {
            // The start is still running; it discards the recording when it finishes.
            null -> {
                Unit
            }

            true -> {
                mutableState.value = RecorderState.Ready
                viewModelScope.launch { recorder.discard() }
            }

            false -> {
                mutableState.value = RecorderState.Ready
            }
        }
    }

    override fun onTapped() = decide(GestureDecision.LOCK)

    override fun onCommitted() = decide(GestureDecision.HOLD)

    override fun onLockProgress(progress: Float) {
        if (decision != GestureDecision.HOLD) return
        updateLive { live -> live.copy(lockProgress = progress) }
    }

    override fun onLocked() {
        if (decision != GestureDecision.HOLD) return
        decision = GestureDecision.LOCK
        haptics.recordingLocked()
        updateLive { live -> live.copy(mode = LiveMode.LOCKED, lockProgress = 1f) }
        offerNotifications()
    }

    override fun onReleased() {
        if (decision != GestureDecision.HOLD) return
        decision = GestureDecision.RELEASED
        if (started == true) stopAndSave()
    }

    override fun stop() {
        val live = mutableState.value as? RecorderState.Live ?: return
        if (live.mode != LiveMode.LOCKED) return
        stopAndSave()
    }

    override fun togglePause() {
        val live = mutableState.value as? RecorderState.Live ?: return
        if (live.mode != LiveMode.LOCKED) return
        cancelDiscardConfirmation()
        viewModelScope.launch { if (live.paused) recorder.resume() else recorder.pause() }
    }

    override fun discard() {
        val live = mutableState.value as? RecorderState.Live ?: return
        if (live.mode != LiveMode.LOCKED) return
        if (!live.confirmingDiscard) {
            askToConfirmDiscard(live)
            return
        }
        discardConfirmJob?.cancel()
        decision = GestureDecision.NONE
        mutableState.value = RecorderState.Ready
        haptics.confirmDestruction()
        viewModelScope.launch { recorder.discard() }
    }

    override fun undo() {
        val saved = mutableState.value as? RecorderState.Saved ?: return
        mutableState.value = RecorderState.Ready
        viewModelScope.launch {
            if (afterSave.undo(saved.noteId)) haptics.confirmDestruction()
        }
    }

    override fun chooseFeeling(feeling: Feeling) {
        val saved = mutableState.value as? RecorderState.Saved ?: return
        if (!saved.feelingsOffered) return
        viewModelScope.launch {
            if (afterSave.chooseFeeling(feeling)) haptics.saveSucceeded()
        }
    }

    override fun retry() {
        val failed = mutableState.value as? RecorderState.Failed ?: return
        when (failed.failure) {
            RecorderFailure.SAVE_FAILED -> retrySave()
            RecorderFailure.MICROPHONE_DENIED -> permissionRequestChannel.trySend(WatchPermission.MICROPHONE)
            else -> mutableState.value = RecorderState.Ready
        }
    }

    override fun dismiss() {
        if (mutableState.value !is RecorderState.Failed) return
        mutableState.value = RecorderState.Ready
    }

    /** The answer to a permission this view model asked the screen to request. */
    fun onPermissionResult(
        permission: WatchPermission,
        result: PermissionResult,
    ) {
        if (permission != WatchPermission.MICROPHONE) return
        when (result) {
            PermissionResult.GRANTED -> if (mutableState.value.canStartRecording) beginStart(GestureDecision.LOCK)
            PermissionResult.DENIED -> fail(RecorderFailure.MICROPHONE_DENIED)
            PermissionResult.BLOCKED -> fail(RecorderFailure.MICROPHONE_BLOCKED)
        }
    }

    override fun onCleared() {
        val current = mutableState.value
        when {
            current is RecorderState.Live && current.mode == LiveMode.HOLDING -> {
                applicationScope.launch { stopAndSaveDetached() }
            }

            current is RecorderState.Live -> {
                Unit
            }

            current == RecorderState.Arming -> {
                val decided = decision == GestureDecision.HOLD || decision == GestureDecision.LOCK || decision == GestureDecision.RELEASED
                applicationScope.launch { if (decided) stopAndSaveDetached() else recorder.discard() }
            }

            current is RecorderState.Failed && current.failure == RecorderFailure.SAVE_FAILED -> {
                unsavedPath?.let { path -> applicationScope.launch { saver.save(path, recorder.elapsed.value) } }
            }
        }
        super.onCleared()
    }

    private fun decide(next: GestureDecision) {
        if (waitingForMicrophone) {
            waitingForMicrophone = false
            permissionRequestChannel.trySend(WatchPermission.MICROPHONE)
            return
        }
        if (decision != GestureDecision.PENDING) return
        decision = next
        applyDecision()
    }

    private fun beginStart(next: GestureDecision) {
        afterSave.close()
        decision = next
        started = null
        nearLimitWarned = false
        mutableState.value = RecorderState.Arming
        viewModelScope.launch {
            val ok = recorder.start()
            started = ok
            onStartFinished(ok)
        }
    }

    private suspend fun onStartFinished(ok: Boolean) {
        when (decision) {
            GestureDecision.NONE -> {
                if (ok) recorder.discard()
                mutableState.value = RecorderState.Ready
            }

            GestureDecision.PENDING -> {
                Unit
            }

            GestureDecision.HOLD, GestureDecision.LOCK -> {
                applyDecision()
            }

            GestureDecision.RELEASED -> {
                if (ok) stopAndSave() else failStart()
            }
        }
    }

    private fun applyDecision() {
        when (started) {
            null -> Unit
            false -> failStart()
            true -> goLive()
        }
    }

    private fun goLive() {
        val locked = decision == GestureDecision.LOCK
        haptics.recordingStarted()
        mutableState.value =
            RecorderState.Live(
                mode = if (locked) LiveMode.LOCKED else LiveMode.HOLDING,
                lockProgress = if (locked) 1f else 0f,
                elapsed = recorder.elapsed.value,
            )
        // The recorder can end between start() returning and now, and its flow does not repeat itself.
        if (!recorder.isRecording.value) {
            stopAndSave()
            return
        }
        if (locked) offerNotifications()
    }

    private fun failStart() {
        decision = GestureDecision.NONE
        fail(recorder.lastStartFailure.toFailure())
    }

    private fun offerNotifications() {
        if (permissions.takeOptionalPrompt(WatchPermission.NOTIFICATIONS)) permissionRequestChannel.trySend(WatchPermission.NOTIFICATIONS)
    }

    private fun askToConfirmDiscard(live: RecorderState.Live) {
        mutableState.value = live.copy(confirmingDiscard = true)
        discardConfirmJob?.cancel()
        discardConfirmJob =
            viewModelScope.launch {
                delay(DISCARD_CONFIRM_MS)
                updateLive { current -> current.copy(confirmingDiscard = false) }
            }
    }

    private fun cancelDiscardConfirmation() {
        discardConfirmJob?.cancel()
        updateLive { current -> current.copy(confirmingDiscard = false) }
    }

    private fun stopAndSave() {
        decision = GestureDecision.NONE
        discardConfirmJob?.cancel()
        mutableState.value = RecorderState.Saving
        haptics.recordingFinished()
        viewModelScope.launch {
            // Stopping and saving finish even if the activity finishes partway through.
            withContext(NonCancellable) {
                val path = recorder.stop()
                if (path == null) {
                    Napier.e("Recording ended without a file")
                    fail(RecorderFailure.RECORDING_LOST)
                    return@withContext
                }
                handle(saver.save(path, recorder.elapsed.value))
            }
        }
    }

    private fun retrySave() {
        val path = unsavedPath
        if (path == null) {
            mutableState.value = RecorderState.Ready
            return
        }
        mutableState.value = RecorderState.Saving
        viewModelScope.launch {
            withContext(NonCancellable) { handle(saver.save(path, recorder.elapsed.value)) }
        }
    }

    /** Stops the recorder and stores its file after the activity is gone, so there is no state to update. */
    private suspend fun stopAndSaveDetached() {
        val path = recorder.stop()
        if (path == null) {
            Napier.e("Recording ended without a file when the app closed")
            return
        }
        saver.save(path, recorder.elapsed.value)
    }

    private suspend fun handle(result: AudioSaveResult) {
        when (result) {
            is AudioSaveResult.Saved -> {
                onSaved(result.note)
            }

            AudioSaveResult.TooShort -> {
                onTooShort()
            }

            is AudioSaveResult.Failed -> {
                unsavedPath = result.path
                fail(RecorderFailure.SAVE_FAILED)
            }
        }
    }

    private suspend fun onSaved(note: JournalNote.Audio) {
        unsavedPath = null
        haptics.saveSucceeded()
        val offerFeelings = moodAfterRecording()
        afterSave.open(note.uid, offerFeelings = offerFeelings)
        mutableState.value =
            RecorderState.Saved(noteId = note.uid, duration = note.durationMs.milliseconds, feelingsOffered = offerFeelings)
    }

    private fun onTooShort() {
        unsavedPath = null
        haptics.actionRejected()
        mutableState.value = RecorderState.TooShort
        viewModelScope.launch {
            delay(TOO_SHORT_DISPLAY_MS)
            mutableState.update { current -> if (current == RecorderState.TooShort) RecorderState.Ready else current }
        }
    }

    private fun fail(failure: RecorderFailure) {
        haptics.actionRejected()
        mutableState.value = RecorderState.Failed(failure)
    }

    private fun observeRecorder() {
        viewModelScope.launch {
            recorder.audioLevel.collect { level ->
                updateLive { live -> if (live.paused) live else live.copy(levels = (live.levels + level).takeLast(MAX_LEVEL_SAMPLES)) }
            }
        }
        viewModelScope.launch {
            recorder.elapsed.collect { elapsed ->
                updateLive { live -> live.copy(elapsed = elapsed) }
                warnNearLimit(elapsed)
            }
        }
        viewModelScope.launch {
            combine(recorder.isPaused, recorder.pausedByInterruption) { paused, interrupted -> paused to interrupted }
                .collect { (paused, interrupted) -> onPauseChanged(paused, interrupted) }
        }
        viewModelScope.launch {
            recorder.isRecording.collect { recording ->
                if (!recording && mutableState.value is RecorderState.Live) stopAndSave()
            }
        }
    }

    /** The saved state follows the undo window: the feelings go with it, and Undo with its end. */
    private fun observeAfterSave() {
        viewModelScope.launch {
            afterSave.window.collect { window ->
                mutableState.update { current ->
                    if (current !is RecorderState.Saved) return@update current
                    if (window == null || window.noteId != current.noteId) return@update RecorderState.Ready
                    current.copy(feelingsOffered = window.feelingsOffered, feeling = window.feeling)
                }
            }
        }
    }

    private fun warnNearLimit(elapsed: Duration) {
        val live = mutableState.value as? RecorderState.Live ?: return
        if (nearLimitWarned || live.paused || elapsed < NEAR_LIMIT) return
        nearLimitWarned = true
        haptics.limitApproaching()
    }

    private fun onPauseChanged(
        paused: Boolean,
        interrupted: Boolean,
    ) {
        val live = mutableState.value as? RecorderState.Live ?: return
        if (paused == live.paused) {
            if (paused) mutableState.value = live.copy(pausedByInterruption = interrupted)
            return
        }
        if (paused) haptics.recordingPaused() else haptics.recordingResumed()
        mutableState.value = live.copy(paused = paused, pausedByInterruption = paused && interrupted, confirmingDiscard = false)
    }

    private inline fun updateLive(transform: (RecorderState.Live) -> RecorderState.Live) {
        mutableState.update { current -> if (current is RecorderState.Live) transform(current) else current }
    }

    private fun RecordingStartFailure?.toFailure(): RecorderFailure =
        when (this) {
            RecordingStartFailure.MICROPHONE_PERMISSION_DENIED -> RecorderFailure.MICROPHONE_DENIED
            RecordingStartFailure.NOT_ENOUGH_STORAGE -> RecorderFailure.NOT_ENOUGH_STORAGE
            RecordingStartFailure.RECORDER_UNAVAILABLE, null -> RecorderFailure.RECORDER_UNAVAILABLE
        }

    companion object {
        const val TOO_SHORT_DISPLAY_MS = 1_200L
        const val DISCARD_CONFIRM_MS = 3_000L
        private const val MAX_LEVEL_SAMPLES = 50
        private val NEAR_LIMIT = WearAudioRecordingManager.MAX_RECORDING_DURATION - 1.minutes
    }
}
