package app.logdate.wear.presentation.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.playback.AudioOutputState
import app.logdate.wear.playback.WearVoiceNotePlayer
import app.logdate.wear.presentation.timeline.WearPlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

data class MemoryPlayerUiState(
    val isLoaded: Boolean = false,
    /** The memory being played, or null once loaded when it no longer exists. */
    val memory: VoiceMemoryItem? = null,
    val playback: WearPlaybackUiState = WearPlaybackUiState.Idle,
    val output: AudioOutputState = AudioOutputState.SpeakerOnly,
)

/**
 * Opens one voice memory at a time, starts playing it, and drives play or pause and the 10 second
 * skips. There is one instance for the whole app, like the timeline's, so the screen calls [open]
 * when it appears and [close] when it leaves, and playback never outlives the screen.
 */
class WearMemoryPlayerViewModel(
    private val notesRepository: JournalNotesRepository,
    playerFactory: (CoroutineScope) -> WearVoiceNotePlayer,
) : ViewModel() {
    companion object {
        const val SKIP_MS = 10_000L
    }

    private val player = playerFactory(viewModelScope)
    private var note: JournalNote.Audio? = null
    private var openJob: Job? = null
    private val loaded = MutableStateFlow<Pair<Boolean, VoiceMemoryItem?>>(false to null)

    val uiState: StateFlow<MemoryPlayerUiState> =
        combine(loaded, player.state, player.outputState) { (isLoaded, memory), playback, output ->
            MemoryPlayerUiState(isLoaded = isLoaded, memory = memory, playback = playback, output = output)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MemoryPlayerUiState())

    fun open(noteId: Uuid) {
        openJob?.cancel()
        if (player.state.value != WearPlaybackUiState.Idle) player.stop()
        note = null
        loaded.value = false to null
        openJob =
            viewModelScope.launch {
                val audio = notesRepository.getNoteById(noteId) as? JournalNote.Audio
                note = audio
                loaded.value = true to audio?.let { VoiceMemoryItem(it.uid, it.creationTimestamp, it.durationMs) }
                audio?.let(player::play)
            }
    }

    fun close() {
        openJob?.cancel()
        if (player.state.value != WearPlaybackUiState.Idle) player.stop()
        note = null
        loaded.value = false to null
    }

    fun onPlayPause() {
        val audio = note ?: return
        when (player.state.value) {
            is WearPlaybackUiState.Active -> player.pauseOrResume()
            is WearPlaybackUiState.Preparing -> Unit
            WearPlaybackUiState.Idle, is WearPlaybackUiState.Error, is WearPlaybackUiState.BlockedOutput -> player.play(audio)
        }
    }

    fun onSkipBack() = player.seekBy(-SKIP_MS)

    fun onSkipForward() = player.seekBy(SKIP_MS)

    override fun onCleared() {
        close()
        super.onCleared()
    }
}
