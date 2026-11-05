package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the voice notes settings screen. Combines the live download status
 * of both on-device audio models — Whisper for transcript refinement, CED
 * for ambient sound detection — into a single UI state the screen renders
 * across the audio settings overview and capability pages.
 */
class VoiceNotesSettingsViewModel(
    private val transcriptionService: TranscriptionService,
    private val audioTaggingService: AudioTaggingService,
) : ViewModel() {
    /** UI state for one downloadable on-device model row. */
    data class ModelRowState(
        val status: ModelDownloadStatus,
    )

    data class UiState(
        val transcription: ModelRowState = ModelRowState(ModelDownloadStatus.Checking),
        val tagging: ModelRowState = ModelRowState(ModelDownloadStatus.Checking),
        val supportsLiveTranscription: Boolean = false,
    )

    val uiState: StateFlow<UiState> =
        combine(
            transcriptionService.offlineModelDownloadStatus,
            audioTaggingService.modelDownloadStatus,
        ) { transcription, tagging ->
            UiState(
                transcription = ModelRowState(transcription),
                tagging = ModelRowState(tagging),
                supportsLiveTranscription = transcriptionService.supportsLiveTranscription,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue =
                UiState(
                    transcription = ModelRowState(transcriptionService.offlineModelDownloadStatus.value),
                    tagging = ModelRowState(audioTaggingService.modelDownloadStatus.value),
                    supportsLiveTranscription = transcriptionService.supportsLiveTranscription,
                ),
        )

    fun downloadTranscriptionModel() {
        transcriptionService.startOfflineModelDownload()
    }

    fun downloadTaggingModel() {
        audioTaggingService.startModelDownload()
    }
}
