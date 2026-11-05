package app.logdate.client.media.audio.tagging

import android.content.Context
import app.logdate.client.media.audio.SpeechFeatureProviderLoader
import app.logdate.client.media.audio.download.ModelDownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** Base-module facade for optional audio tagging supplied by the install-time speech feature. */
internal class InstallTimeAudioTaggingService(
    delegateFactory: () -> AudioTaggingService?,
) : AudioTaggingService {
    constructor(
        context: Context,
        providerLoader: SpeechFeatureProviderLoader,
    ) : this(
        delegateFactory = {
            providerLoader.load()?.createAudioTagging(context)
        },
    )

    private val delegate = delegateFactory()
    private val notSupportedDownloadStatus: StateFlow<ModelDownloadStatus> =
        MutableStateFlow(ModelDownloadStatus.NotSupported).asStateFlow()

    override val isAvailable: Boolean
        get() = delegate?.isAvailable == true

    override suspend fun warmUp(): Boolean = delegate?.warmUp() ?: false

    override fun tagAudio(audioUri: String): Flow<AudioTaggingResult> =
        delegate?.tagAudio(audioUri) ?: flowOf(AudioTaggingResult.Unavailable)

    override val modelDownloadStatus: StateFlow<ModelDownloadStatus>
        get() = delegate?.modelDownloadStatus ?: notSupportedDownloadStatus

    override fun checkModelAvailability() {
        delegate?.checkModelAvailability()
    }

    override fun startModelDownload() {
        delegate?.startModelDownload()
    }

    override fun release() {
        delegate?.release()
    }
}
