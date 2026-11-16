package app.logdate.feature.journals.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import app.logdate.client.repository.audio.AudioTagRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.feature.editor.audio.AudioLabelResolver
import app.logdate.feature.editor.audio.formatAudioLabelAsync
import app.logdate.ui.audio.AudioContext
import app.logdate.ui.audio.AudioContextProcessor
import app.logdate.util.formatDateLocalized
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.audio_note_viewer_error_load_failed
import logdate.client.feature.journal.generated.resources.audio_note_viewer_error_not_audio
import logdate.client.feature.journal.generated.resources.audio_note_viewer_error_not_found
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ViewModel that manages audio-specific rendering and playback state.
 */
class AudioNoteViewerViewModel(
    private val noteId: Uuid,
    private val notesRepository: JournalNotesRepository,
    private val audioContextProcessor: AudioContextProcessor,
    private val durationResolver: AudioDurationResolver,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val getJournalMembership: GetJournalMembershipUseCase,
    private val audioTagRepository: AudioTagRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AudioNoteViewerUiState>(AudioNoteViewerUiState.Loading)
    val uiState: StateFlow<AudioNoteViewerUiState> = _uiState.asStateFlow()

    private val statusProvider = audioPlaybackManager as? AudioPlaybackStatusProvider
    private var audioProcessingJob: Job? = null
    private var cachedAudioContext: AudioContext? = null
    private var cachedAudioMediaRef: String? = null
    private var cachedAudioDurationMs: Long = 0L
    private var cachedPlaybackState = AudioPlaybackUiState()
    private var cachedLocationName: String? = null
    private var cachedLatitude: Double? = null
    private var cachedLongitude: Double? = null
    private var cachedJournalNames: List<String> = emptyList()
    private var cachedDetectedSounds: List<String> = emptyList()
    private val labelResolver = AudioLabelResolver()

    init {
        observeNote()
        observePlaybackStatus()
        observeJournalMembership()
        observeDetectedSounds()
    }

    private fun observeDetectedSounds() {
        viewModelScope.launch {
            audioTagRepository.observeTagsForNote(noteId).collect { tags ->
                // Tags arrive ordered by confidence desc from the DAO. Cap at the
                // top six so the chip row stays a quick glance, not a label dump.
                cachedDetectedSounds =
                    tags
                        .filter { it.confidence >= MIN_CHIP_CONFIDENCE }
                        .take(MAX_CHIPS)
                        .map { it.soundName }
                _uiState.update { state ->
                    val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                    ready.copy(detectedSounds = cachedDetectedSounds)
                }
            }
        }
    }

    private fun observeNote() {
        viewModelScope.launch {
            notesRepository
                .observeNoteById(noteId)
                .catch { error ->
                    _uiState.value =
                        AudioNoteViewerUiState.Error(
                            Res.string.audio_note_viewer_error_load_failed.toUiText(error.message.toString()),
                        )
                }.collect { note ->
                    if (note == null) {
                        _uiState.value = AudioNoteViewerUiState.Error(Res.string.audio_note_viewer_error_not_found.toUiText())
                    } else {
                        updateForNote(note)
                    }
                }
        }
    }

    private fun updateForNote(note: JournalNote) {
        val audioNote = note as? JournalNote.Audio
        if (audioNote == null) {
            _uiState.value = AudioNoteViewerUiState.Error(Res.string.audio_note_viewer_error_not_audio.toUiText())
            return
        }
        updateForAudio(audioNote)
    }

    private fun updateForAudio(note: JournalNote.Audio) {
        viewModelScope.launch {
            val durationMs =
                if (note.durationMs > 0) {
                    note.durationMs
                } else {
                    durationResolver.resolveDurationMs(note.mediaRef) ?: 0L
                }
            if (note.durationMs <= 0L && durationMs > 0L) {
                try {
                    notesRepository.updateAudioDurationIfUnknown(note.uid, note.mediaRef, durationMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not retain resolved audio duration", error)
                }
            }
            val shouldProcess =
                cachedAudioMediaRef != note.mediaRef ||
                    cachedAudioDurationMs != durationMs ||
                    cachedAudioContext == null

            if (shouldProcess) {
                cachedAudioMediaRef = note.mediaRef
                cachedAudioDurationMs = durationMs
                cachedLocationName = note.location?.displayName
                cachedLatitude = note.location?.effectiveLatitude
                cachedLongitude = note.location?.effectiveLongitude

                // Show the player with correct colors immediately; waveform fills in once
                // extraction completes in the background.
                val initialContext =
                    audioContextProcessor.buildInitialContext(
                        createdAt = note.creationTimestamp,
                        latitude = cachedLatitude,
                        longitude = cachedLongitude,
                    )
                cachedAudioContext = initialContext
                _uiState.value =
                    AudioNoteViewerUiState.Ready(
                        mediaRef = note.mediaRef,
                        durationMs = durationMs,
                        createdAt = note.creationTimestamp,
                        context = initialContext,
                        playbackState = cachedPlaybackState,
                        detectedSounds = cachedDetectedSounds,
                    )
                processAudioContext(note, durationMs)
            } else {
                _uiState.value =
                    AudioNoteViewerUiState.Ready(
                        mediaRef = note.mediaRef,
                        durationMs = durationMs,
                        createdAt = note.creationTimestamp,
                        context = cachedAudioContext ?: return@launch,
                        playbackState = cachedPlaybackState,
                        detectedSounds = cachedDetectedSounds,
                    )
            }
        }
    }

    private fun processAudioContext(
        note: JournalNote.Audio,
        durationMs: Long,
    ) {
        audioProcessingJob?.cancel()
        audioProcessingJob =
            viewModelScope.launch {
                val result =
                    runCatching {
                        audioContextProcessor.process(
                            audioUri = note.mediaRef,
                            durationMs = durationMs,
                            createdAt = note.creationTimestamp,
                            latitude = note.location?.effectiveLatitude,
                            longitude = note.location?.effectiveLongitude,
                        )
                    }.getOrNull()

                if (result == null) {
                    Napier.w { "Waveform extraction failed for ${note.mediaRef}; playing without waveform" }
                    return@launch
                }

                // Guard against applying a stale result if the user opened a different note
                // before extraction finished.
                if (note.mediaRef != cachedAudioMediaRef) return@launch

                cachedAudioContext = result
                _uiState.update { state ->
                    val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                    ready.copy(context = result)
                }
            }
    }

    private fun observeJournalMembership() {
        viewModelScope.launch {
            getJournalMembership(setOf(noteId)).collect { membershipMap ->
                cachedJournalNames = membershipMap[noteId]?.map { it.title }.orEmpty()
            }
        }
    }

    private fun observePlaybackStatus() {
        statusProvider ?: return
        viewModelScope.launch {
            statusProvider.playbackStatus.collect { status ->
                val currentNoteId = status.metadata?.noteId
                val playingAnotherNote =
                    when {
                        currentNoteId != null -> currentNoteId != noteId
                        status.currentUri != null -> status.currentUri != cachedAudioMediaRef
                        else -> false
                    }
                if (playingAnotherNote) {
                    cachedPlaybackState = AudioPlaybackUiState()
                    _uiState.update { state ->
                        val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                        ready.copy(playbackState = cachedPlaybackState)
                    }
                    return@collect
                }
                val durationMs =
                    if (status.duration > Duration.ZERO) {
                        status.duration.inWholeMilliseconds
                    } else {
                        cachedAudioDurationMs
                    }
                cachedAudioDurationMs = durationMs
                cachedPlaybackState =
                    cachedPlaybackState.copy(
                        progress = status.progress,
                        isPlaying = status.isPlaying,
                        errorMessage = status.errorMessage,
                    )

                _uiState.update { state ->
                    val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                    ready.copy(
                        durationMs = durationMs,
                        playbackState = cachedPlaybackState,
                    )
                }
            }
        }
    }

    /**
     * Playback toggle for audio notes.
     */
    fun togglePlayback() {
        val content = _uiState.value as? AudioNoteViewerUiState.Ready ?: return
        if (content.playbackState.isPlaying) {
            audioPlaybackManager.pausePlayback()
            cachedPlaybackState = cachedPlaybackState.copy(isPlaying = false)
            _uiState.update { state ->
                val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                ready.copy(
                    playbackState = ready.playbackState.copy(isPlaying = false),
                )
            }
        } else {
            viewModelScope.launch { startPlayback(content) }
        }
    }

    /**
     * Seek request for audio playback progress.
     */
    fun seekTo(progress: Float) {
        val safeProgress = progress.coerceIn(0f, 1f)
        audioPlaybackManager.seekTo(safeProgress)
        cachedPlaybackState = cachedPlaybackState.copy(progress = safeProgress)
        _uiState.update { state ->
            val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
            ready.copy(
                playbackState = ready.playbackState.copy(progress = safeProgress),
            )
        }
    }

    /**
     * Skip request for audio playback by milliseconds.
     */
    fun skipByMillis(deltaMs: Long) {
        val content = _uiState.value as? AudioNoteViewerUiState.Ready ?: return
        if (content.durationMs <= 0L) return
        val currentMs = (content.playbackState.progress * content.durationMs.toFloat()).roundToLong()
        val targetMs = (currentMs + deltaMs).coerceIn(0L, content.durationMs)
        val ratio = targetMs.toFloat() / content.durationMs.toFloat()
        seekTo(ratio)
    }

    private suspend fun startPlayback(content: AudioNoteViewerUiState.Ready) {
        val subtitle =
            formatDateLocalized(
                content.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date,
            )
        val labelResult =
            labelResolver.resolve(
                createdAt = content.createdAt,
                locationName = cachedLocationName,
                latitude = cachedLatitude,
                longitude = cachedLongitude,
            )
        val title = formatAudioLabelAsync(labelResult)
        val palette = content.context.palette
        val metadata =
            AudioPlaybackMetadata(
                title = title,
                subtitle = subtitle,
                noteId = noteId,
                journalNames = cachedJournalNames,
                accentColor = palette.accentColor,
                immersiveBackground = palette.immersiveBackground,
                gradientStart = palette.waveformGradientStart,
                gradientEnd = palette.waveformGradientEnd,
            )
        audioPlaybackManager.startPlayback(
            uri = content.mediaRef,
            metadata = metadata,
            onProgressUpdated = { progress ->
                cachedPlaybackState =
                    cachedPlaybackState.copy(
                        progress = progress,
                        isPlaying = true,
                    )
                _uiState.update { state ->
                    val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                    ready.copy(
                        playbackState =
                            ready.playbackState.copy(
                                progress = progress,
                                isPlaying = true,
                            ),
                    )
                }
            },
            onPlaybackCompleted = {
                // A playback error also invokes this callback (not just natural end-of-track), so
                // only force progress to 100% on a real completion — otherwise the error banner
                // (driven by observePlaybackStatus) would be contradicted by a "fully played" bar.
                val failed = statusProvider?.playbackStatus?.value?.errorMessage != null
                val completedProgress = if (failed) cachedPlaybackState.progress else 1f
                cachedPlaybackState =
                    cachedPlaybackState.copy(
                        progress = completedProgress,
                        isPlaying = false,
                    )
                _uiState.update { state ->
                    val ready = state as? AudioNoteViewerUiState.Ready ?: return@update state
                    ready.copy(
                        playbackState =
                            ready.playbackState.copy(
                                progress = completedProgress,
                                isPlaying = false,
                            ),
                    )
                }
            },
        )
    }

    override fun onCleared() {
        super.onCleared()
        audioProcessingJob?.cancel()
    }

    private companion object {
        const val MIN_CHIP_CONFIDENCE = 0.4f
        const val MAX_CHIPS = 6
    }
}

/**
 * Playback status for audio notes.
 */
data class AudioPlaybackUiState(
    val progress: Float = 0f,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Type-safe UI state for audio note viewing.
 */
sealed interface AudioNoteViewerUiState {
    data object Loading : AudioNoteViewerUiState

    data class Error(
        val message: UiText,
    ) : AudioNoteViewerUiState

    /**
     * Ready state for audio notes with context and playback status.
     *
     * @param detectedSounds names of ambient sounds the on-device tagger
     *   found on this recording, ordered by confidence. Empty until the
     *   tagger has finished scanning (or always empty for older notes
     *   captured before tagging existed).
     */
    data class Ready(
        val mediaRef: String,
        val durationMs: Long,
        val createdAt: Instant,
        val context: AudioContext,
        val playbackState: AudioPlaybackUiState,
        val detectedSounds: List<String> = emptyList(),
    ) : AudioNoteViewerUiState
}
