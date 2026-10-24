package app.logdate.wear.v2.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.ui.platform.LogDateHaptics
import app.logdate.wear.sync.WearNoteRemovalNotifier
import app.logdate.wear.v2.playback.CrownScrubber
import app.logdate.wear.v2.playback.MomentPlayer
import app.logdate.wear.v2.playback.PlaybackState
import app.logdate.wear.v2.playback.noteId
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.uuid.Uuid

/**
 * One moment: its note, how it plays, and deleting it. [noteId] is null for a link whose ID could
 * not be read, which shows the moment as missing.
 *
 * Deleting a moment that has audio on the watch also removes that audio through [discardAudio], and
 * tells the phone through [removalNotifier]: the sync outbox drops a removal that meets a create the
 * phone has not confirmed yet, so a note the phone already has would stay there.
 */
class MomentViewModel(
    private val noteId: Uuid?,
    private val notesRepository: JournalNotesRepository,
    private val player: MomentPlayer,
    private val haptics: LogDateHaptics,
    private val removalNotifier: WearNoteRemovalNotifier,
    private val discardAudio: suspend (JournalNote.Audio) -> Unit,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : ViewModel() {
    private val load = MutableStateFlow<NoteLoad>(NoteLoad.Loading)
    private val deleteFailed = MutableStateFlow(false)
    private val mutableDeleted = MutableStateFlow(false)
    private val scrubber = CrownScrubber()
    private var deleteJob: Job? = null

    /** True once the moment is deleted, so its screen closes. */
    val deleted: StateFlow<Boolean> = mutableDeleted.asStateFlow()

    val uiState: StateFlow<MomentUiState> =
        combine(load, player.state, player.speed, player.skipSilence, deleteFailed) { loaded, playback, speed, skipSilence, failed ->
            when (loaded) {
                NoteLoad.Loading -> {
                    MomentUiState.Loading
                }

                NoteLoad.Missing -> {
                    MomentUiState.Missing
                }

                is NoteLoad.Found -> {
                    MomentUiState.Ready(
                        note = loaded.note,
                        timeZone = timeZone(),
                        playback = playback.takeIf { it.noteId == loaded.note.uid } ?: PlaybackState.Idle,
                        speed = speed,
                        skipSilence = skipSilence,
                        deleteFailed = failed,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MomentUiState.Loading)

    init {
        viewModelScope.launch { load.value = loadNote() }
    }

    fun playPause() {
        val audio = foundNote() as? JournalNote.Audio ?: return
        player.playOrPause(audio)
    }

    /** Moves through the playing recording, with one detent tick for each turn that crosses a step. */
    fun onCrown(pixels: Float) {
        val playing = player.state.value
        if (playing !is PlaybackState.Active || playing.noteId != noteId) {
            scrubber.reset()
            return
        }
        val steps = scrubber.onRotate(pixels)
        if (steps == 0) return
        player.seekBy(steps * scrubber.stepMs)
        haptics.detentTick()
    }

    fun cycleSpeed() = player.cycleSpeed()

    fun setSkipSilence(enabled: Boolean) = player.setSkipSilence(enabled)

    /**
     * Deletes the moment here and, through sync, on the phone. Once started it finishes even if the
     * screen is left, so a delete is never half done.
     */
    fun delete() {
        val note = foundNote() ?: return
        if (deleteJob?.isActive == true) return
        deleteFailed.value = false
        deleteJob = viewModelScope.launch { withContext(NonCancellable) { deleteNote(note) } }
    }

    private suspend fun deleteNote(note: JournalNote) {
        player.stopIfPlaying(note.uid)
        if (!remove(note)) {
            haptics.actionRejected()
            deleteFailed.value = true
            return
        }
        attempt("tell the phone moment ${note.uid} was deleted") { removalNotifier.notifyRemoved(note.uid) }
        if (note is JournalNote.Audio) attempt("remove the audio of moment ${note.uid} from the watch") { discardAudio(note) }
        haptics.confirmDestruction()
        load.value = NoteLoad.Missing
        mutableDeleted.value = true
    }

    private fun foundNote(): JournalNote? = (load.value as? NoteLoad.Found)?.note

    private suspend fun loadNote(): NoteLoad {
        val id = noteId ?: return NoteLoad.Missing
        val note =
            try {
                notesRepository.getNoteById(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Couldn't read moment $id on the watch", e)
                null
            }
        return note?.let(NoteLoad::Found) ?: NoteLoad.Missing
    }

    private suspend fun remove(note: JournalNote): Boolean =
        try {
            notesRepository.remove(note)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Couldn't delete moment ${note.uid} on the watch", e)
            false
        }

    private suspend fun attempt(
        what: String,
        step: suspend () -> Unit,
    ) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Couldn't $what", e)
        }
    }

    private sealed interface NoteLoad {
        data object Loading : NoteLoad

        data object Missing : NoteLoad

        data class Found(
            val note: JournalNote,
        ) : NoteLoad
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
