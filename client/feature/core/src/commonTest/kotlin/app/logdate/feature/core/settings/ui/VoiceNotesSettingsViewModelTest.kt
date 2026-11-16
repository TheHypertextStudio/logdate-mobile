package app.logdate.feature.core.settings.ui

import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.tagging.NoopAudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNotesSettingsViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun retainedOverviewStaysCurrentWhileItsDetailIsOpen() =
        runTest {
            val service = FakeTranscriptionService(ModelDownloadStatus.Idle)
            val viewModel = VoiceNotesSettingsViewModel(service, NoopAudioTaggingService)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()
            collector.cancel()
            advanceTimeBy(6_000)
            runCurrent()
            service.offlineModelDownloadStatus.value = ModelDownloadStatus.Completed
            runCurrent()
            assertEquals(ModelDownloadStatus.Completed, viewModel.uiState.value.transcription.status)
        }

    @Test
    fun installedModelIsReadyBeforeUiCollectionStarts() {
        val viewModel = viewModel(ModelDownloadStatus.Completed)
        assertEquals(ModelDownloadStatus.Completed, viewModel.uiState.value.transcription.status)
        assertEquals(ModelDownloadStatus.NotSupported, viewModel.uiState.value.tagging.status)
    }

    @Test
    fun reopeningSettingsPreservesActiveDownloadImmediately() {
        val progress = ModelDownloadStatus.Downloading(100, 200)
        val service = FakeTranscriptionService(progress)
        val first = VoiceNotesSettingsViewModel(service, NoopAudioTaggingService)
        val reopened = VoiceNotesSettingsViewModel(service, NoopAudioTaggingService)
        assertEquals(progress, first.uiState.value.transcription.status)
        assertEquals(progress, reopened.uiState.value.transcription.status)
    }

    @Test
    fun unknownAvailabilityStartsAsChecking() {
        val viewModel = viewModel(ModelDownloadStatus.Checking)
        assertEquals(ModelDownloadStatus.Checking, viewModel.uiState.value.transcription.status)
    }

    private fun viewModel(status: ModelDownloadStatus) =
        VoiceNotesSettingsViewModel(
            FakeTranscriptionService(status),
            NoopAudioTaggingService,
        )

    private class FakeTranscriptionService(
        status: ModelDownloadStatus,
    ) : TranscriptionService {
        override val offlineModelDownloadStatus = MutableStateFlow(status)
        override val supportsLiveTranscription = false
        override val supportsFileTranscription = true

        override fun getTranscriptionFlow() = MutableSharedFlow<TranscriptionResult>()

        override suspend fun startLiveTranscription() = TranscriptionStartResult.Failed(TranscriptionFailure.NotSupported)

        override suspend fun stopLiveTranscription() = TranscriptionResult.Error(TranscriptionFailure.NotSupported)

        override suspend fun transcribeAudioFile(audioUri: String) = TranscriptionResult.Error(TranscriptionFailure.NotSupported)

        override suspend fun cancelTranscription() = Unit

        override fun getSupportedLanguages() = emptyList<String>()

        override fun setLanguage(languageCode: String) = Unit

        override suspend fun resetTranscription() = Unit

        override fun release() = Unit
    }
}
