package app.logdate.client.media.audio.transcription

import android.content.Context
import app.logdate.client.media.audio.SpeechFeatureProviderLoader
import app.logdate.client.media.audio.download.ModelDownloadStatus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stable base-module facade for the transcription engine shipped in the
 * install-time speech feature.
 *
 * Live transcription is always advertised as a product capability so audio
 * recording attempts to start it. A malformed installation that is missing
 * the feature produces an explicit [TranscriptionFailure.NotAvailable] result
 * instead of silently skipping transcription.
 */
internal class InstallTimeTranscriptionService(
    private val scope: CoroutineScope,
    delegateFactory: () -> TranscriptionService?,
) : TranscriptionService {
    constructor(
        context: Context,
        scope: CoroutineScope,
        providerLoader: SpeechFeatureProviderLoader,
    ) : this(
        scope = scope,
        delegateFactory = {
            providerLoader.load()?.createTranscription(context, scope)
        },
    )

    private val delegate: TranscriptionService? = delegateFactory()
    private val results = MutableSharedFlow<TranscriptionResult>(replay = 1)
    private var forwardingJob: Job? = null

    private val notSupportedDownloadStatus: StateFlow<ModelDownloadStatus> =
        MutableStateFlow(ModelDownloadStatus.NotSupported).asStateFlow()

    override fun getTranscriptionFlow(): SharedFlow<TranscriptionResult> = results.asSharedFlow()

    override suspend fun startLiveTranscription(): TranscriptionStartResult {
        val active = delegate
        if (active == null) {
            Napier.e("Live transcription unavailable because the install-time speech feature is missing")
            results.emit(TranscriptionResult.Error(TranscriptionFailure.NotAvailable))
            return TranscriptionStartResult.Failed(TranscriptionFailure.NotAvailable)
        }

        forwardingJob?.cancel()
        forwardingJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                active.getTranscriptionFlow().collect(results::emit)
            }
        return active.startLiveTranscription()
    }

    override suspend fun stopLiveTranscription(): TranscriptionResult {
        val active = delegate
        if (active != null) return active.stopLiveTranscription()
        return TranscriptionResult.Error(TranscriptionFailure.NotAvailable).also { results.emit(it) }
    }

    override suspend fun transcribeAudioFile(audioUri: String): TranscriptionResult =
        delegate?.transcribeAudioFile(audioUri)
            ?: TranscriptionResult.Error(TranscriptionFailure.NotAvailable)

    override suspend fun cancelTranscription() {
        delegate?.cancelTranscription()
    }

    override fun updatePreferredInputDevice(deviceId: String?): Boolean = delegate?.updatePreferredInputDevice(deviceId) == true

    override fun getSupportedLanguages(): List<String> = delegate?.getSupportedLanguages().orEmpty()

    override fun setLanguage(languageCode: String) {
        delegate?.setLanguage(languageCode)
    }

    override val supportsLiveTranscription: Boolean = true

    override val supportsFileTranscription: Boolean
        get() = delegate?.supportsFileTranscription == true

    override suspend fun resetTranscription() {
        delegate?.resetTranscription()
    }

    override suspend fun warmUp() {
        delegate?.warmUp()
    }

    override val isOfflineModelAvailable: Boolean
        get() = delegate?.isOfflineModelAvailable == true

    override val offlineModelDownloadStatus: StateFlow<ModelDownloadStatus>
        get() = delegate?.offlineModelDownloadStatus ?: notSupportedDownloadStatus

    override fun checkOfflineModelAvailability() {
        delegate?.checkOfflineModelAvailability()
    }

    override fun startOfflineModelDownload() {
        delegate?.startOfflineModelDownload()
    }

    override fun release() {
        forwardingJob?.cancel()
        forwardingJob = null
        delegate?.release()
    }
}
