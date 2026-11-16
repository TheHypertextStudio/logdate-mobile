package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionService
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the voice notes settings screen. Combines the live download status
 * of both on-device audio models — Whisper for transcript refinement, CED
 * for ambient sound detection — into a single UI state the screen renders
 * across the audio settings overview and capability pages.
 */
class VoiceNotesSettingsViewModel(
    private val transcriptionService: TranscriptionService,
    private val audioTaggingService: AudioTaggingService,
    private val preferences: LogdatePreferencesDataSource? = null,
    private val flags: FeatureFlagStore? = null,
) : ViewModel() {
    private val quietSaveWriteFailed = MutableStateFlow(false)

    /** UI state for one downloadable on-device model row. */
    data class ModelRowState(
        val status: ModelDownloadStatus,
    )

    data class UiState(
        val transcription: ModelRowState = ModelRowState(ModelDownloadStatus.Checking),
        val tagging: ModelRowState = ModelRowState(ModelDownloadStatus.Checking),
        val supportsLiveTranscription: Boolean = false,
        val quietSaveAvailable: Boolean = false,
        val quietSaveEnabled: Boolean = true,
        val quietSaveWriteFailed: Boolean = false,
    )

    val uiState: StateFlow<UiState> =
        combine(
            transcriptionService.offlineModelDownloadStatus,
            audioTaggingService.modelDownloadStatus,
            preferences?.observeQuietSaveEnabled() ?: flowOf(true),
            flags?.observe(FeatureFlag.QUIET_SAVE) ?: flowOf(false),
            quietSaveWriteFailed,
        ) { transcription, tagging, enabled, available, failed ->
            UiState(
                transcription = ModelRowState(transcription),
                tagging = ModelRowState(tagging),
                supportsLiveTranscription = transcriptionService.supportsLiveTranscription,
                quietSaveAvailable = available,
                quietSaveEnabled = enabled,
                quietSaveWriteFailed = failed,
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

    fun setQuietSaveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences?.setQuietSaveEnabled(enabled)
                quietSaveWriteFailed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Could not save Quiet Save preference", e)
                quietSaveWriteFailed.value = true
            }
        }
    }

    fun downloadTranscriptionModel() {
        transcriptionService.startOfflineModelDownload()
    }

    fun downloadTaggingModel() {
        audioTaggingService.startModelDownload()
    }
}
