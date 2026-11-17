package app.logdate.wear.playback

import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.presentation.timeline.WearPlaybackUiState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays one voice note at a time on the watch, with pause, resume and skip.
 *
 * A note the watch does not hold is fetched from the phone first. Nothing starts when the watch
 * has no way to play sound, and playback the system suppresses for an unsuitable output stops and
 * says so, so the user is never left with a note that appears to play in silence.
 */
class WearVoiceNotePlayer(
    private val scope: CoroutineScope,
    private val engine: WearPlaybackEngine,
    private val outputs: WearAudioOutputs,
    private val resolver: WearSyncedAudioResolver,
) {
    private val _state = MutableStateFlow<WearPlaybackUiState>(WearPlaybackUiState.Idle)
    val state: StateFlow<WearPlaybackUiState> = _state.asStateFlow()

    val outputState: StateFlow<AudioOutputState> = outputs.outputState

    private var resolveJob: Job? = null

    init {
        scope.launch {
            engine.suppressedForUnsuitableOutput.collect { suppressed ->
                val current = _state.value
                if (suppressed && current is WearPlaybackUiState.Active && !current.isPaused) {
                    engine.stop()
                    _state.value = WearPlaybackUiState.BlockedOutput(current.noteId)
                }
            }
        }
    }

    fun play(note: JournalNote.Audio) {
        resolveJob?.cancel()
        if (_state.value is WearPlaybackUiState.Active) engine.stop()

        if (outputs.outputState.value is AudioOutputState.Unavailable) {
            Napier.w { "No audio output available, cannot play note ${note.uid}" }
            _state.value = WearPlaybackUiState.BlockedOutput(note.uid)
            return
        }

        _state.value = WearPlaybackUiState.Preparing(note.uid)
        resolveJob = scope.launch { resolveAndStart(note) }
    }

    private suspend fun resolveAndStart(note: JournalNote.Audio) {
        val uri =
            resolver.resolvePlayableUri(note).getOrElse {
                _state.value = WearPlaybackUiState.Error(note.uid)
                return
            }
        val latest = _state.value
        if (latest !is WearPlaybackUiState.Preparing || latest.noteId != note.uid) return

        _state.value = WearPlaybackUiState.Active(noteId = note.uid, progress = 0f, durationMs = note.durationMs)
        engine.start(
            uri = uri,
            noteId = note.uid,
            onProgress = { progress ->
                val state = _state.value
                if (state is WearPlaybackUiState.Active && state.noteId == note.uid && !state.isPaused) {
                    _state.value = state.copy(progress = progress)
                }
            },
            onCompleted = { _state.value = WearPlaybackUiState.Idle },
        )
    }

    fun pauseOrResume() {
        val current = _state.value as? WearPlaybackUiState.Active ?: return
        if (current.isPaused) {
            engine.resume()
            _state.value = current.copy(isPaused = false)
        } else {
            engine.pause()
            _state.value = current.copy(isPaused = true)
        }
    }

    /** Moves the position by [deltaMs], staying inside the recording. Does nothing when its length is unknown. */
    fun seekBy(deltaMs: Long) {
        val current = _state.value as? WearPlaybackUiState.Active ?: return
        if (current.durationMs <= 0L) return
        val target = (current.progress + deltaMs.toFloat() / current.durationMs).coerceIn(0f, 1f)
        engine.seekTo(target)
        _state.value = current.copy(progress = target)
    }

    fun stop() {
        resolveJob?.cancel()
        resolveJob = null
        engine.stop()
        _state.value = WearPlaybackUiState.Idle
    }
}
